package com.keyling.hub.dongle

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import bridge.Hello
import bridge.Result
import bridge.Status
import com.keyling.hub.ble.BleLink
import com.keyling.hub.ble.BridgeUuids
import com.keyling.hub.ble.LinkLostException
import com.keyling.hub.protocol.Session
import com.keyling.hub.service.HubService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.Executor

sealed interface DongleState {
    data object NotPaired : DongleState
    data object NoPermission : DongleState
    data object BluetoothOff : DongleState
    data object Idle : DongleState
    data object Pairing : DongleState
    data object Connecting : DongleState
    class Connected(val hello: Hello, val session: Session) : DongleState {
        override fun toString() = "Connected(firmware ${hello.firmware_version})"
    }

    /** The dongle refuses this phone's bond, usually after "Forget phone" on the dongle. */
    data object BondLost : DongleState

    /** The phone no longer has a bond with the dongle, e.g. removed in Bluetooth settings. */
    data object PhoneBondMissing : DongleState
}

class DongleUnavailableException(val state: DongleState) :
    IOException("dongle not connected (${state::class.simpleName})")

private const val TAG = "DongleController"
private const val HELLO_TIMEOUT_MS = 5_000L
private const val BOND_TIMEOUT_MS = 90_000L
private const val BOND_RETRY_GRACE_MS = 5_000L
private const val BOND_POLL_MS = 500L

/** Hidden extra of ACTION_BOND_STATE_CHANGED with the reason a bond attempt ended. */
private const val EXTRA_UNBOND_REASON = "android.bluetooth.device.extra.REASON"
private const val CLIENT_CONNECT_TIMEOUT_MS = 15_000L
private val RECONNECT_DELAYS_MS = longArrayOf(1_000, 3_000, 10_000)
/** Connections dropped before Hello in a row that mean the dongle refuses this phone. */
private const val REFUSALS_BEFORE_BOND_LOST = 3

/** HCI "Authentication Failure", which the dongle uses to refuse a phone it has no bond for. */
private const val STATUS_AUTH_FAILURE = 0x05

/**
 * Owns the connection to the paired dongle for the whole process.
 *
 * While the connection loop runs, it connects, keeps the session up and reconnects after a drop;
 * it ends when the dongle stays unreachable, on [disconnect], or on an error that needs the user.
 * [HubService] keeps the process in the foreground while [running] is true.
 */
@SuppressLint("MissingPermission")
class DongleController(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = DongleStore(context)
    private val bluetooth = context.getSystemService(BluetoothManager::class.java)
    private val companions = context.getSystemService(CompanionDeviceManager::class.java)

    private val _state = MutableStateFlow<DongleState>(restingState())
    private val _status = MutableStateFlow<Status?>(null)
    private val _running = MutableStateFlow(false)
    private var loop: Job? = null

    val state: StateFlow<DongleState> = _state.asStateFlow()

    /** The dongle's latest Status while connected. */
    val status: StateFlow<Status?> = _status.asStateFlow()

    /** True while the connection loop runs. */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _typingClient = MutableStateFlow<String?>(null)

    /** The app whose text was sent last, or null for text from the hub itself. */
    val typingClient: StateFlow<String?> = _typingClient.asStateFlow()

    val isPaired: Boolean get() = store.address != null

    init {
        scope.launch { _state.collect { Log.i(TAG, "state $it") } }
    }

    private fun hasPermission() =
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun restingState(): DongleState = when {
        store.address == null -> DongleState.NotPaired
        !hasPermission() -> DongleState.NoPermission
        else -> DongleState.Idle
    }

    /** Starts the connection loop, if not running, and the foreground service that holds it. */
    @Synchronized
    fun connect() {
        if (loop?.isActive == true) return
        _running.value = true
        loop = scope.launch {
            try {
                _state.value = connectLoop()
            } finally {
                _status.value = null
                _running.value = false
            }
        }
        HubService.start(context)
    }

    /** Ends the connection loop and closes the connection. */
    @Synchronized
    fun disconnect() {
        Log.i(TAG, "disconnect requested", Throwable("caller"))
        loop?.cancel()
        loop = null
        _state.value = restingState()
    }

    /** Called when the system no longer sees the dongle: stop retrying unless connected. */
    fun onDongleDisappeared() {
        Log.i(TAG, "dongle disappeared in state ${_state.value}")
        if (_state.value !is DongleState.Connected) disconnect()
    }

    /** Waits for a session, connecting first if needed. */
    suspend fun session(timeoutMs: Long = CLIENT_CONNECT_TIMEOUT_MS): Session {
        (_state.value as? DongleState.Connected)?.let { return it.session }
        connect()
        val state = withTimeoutOrNull(timeoutMs) {
            _state.first { it is DongleState.Connected || !_running.value }
        } ?: _state.value
        return (state as? DongleState.Connected)?.session ?: throw DongleUnavailableException(state)
    }

    /** Types [text]; [client] names the app it came from, shown while the dongle types it. */
    suspend fun typeText(text: String, client: String? = null): Result {
        val session = session()
        _typingClient.value = client
        return session.typeText(text)
    }

    suspend fun abort() {
        (_state.value as? DongleState.Connected)?.session?.abort()
    }

    private suspend fun connectLoop(): DongleState {
        val address = store.address ?: return DongleState.NotPaired
        var failures = 0
        var refusals = 0
        while (true) {
            if (!hasPermission()) return DongleState.NoPermission
            val adapter = bluetooth.adapter
            if (adapter == null || !adapter.isEnabled) return DongleState.BluetoothOff
            val device = adapter.getRemoteDevice(address)
            if (device.bondState != BluetoothDevice.BOND_BONDED) return DongleState.PhoneBondMissing

            _state.value = DongleState.Connecting
            var gotHello = false
            try {
                val link = BleLink.connect(context, device)
                try {
                    val session = link.startSession(scope)
                    val hello = withTimeout(HELLO_TIMEOUT_MS) { session.hello() }
                    Log.i(TAG, "session ready: firmware ${hello.firmware_version}, capabilities ${hello.capabilities}")
                    gotHello = true
                    failures = 0
                    refusals = 0
                    _state.value = DongleState.Connected(hello, session)
                    val statusJob = scope.launch { session.status.collect { _status.value = it } }
                    try {
                        link.disconnected.await()
                    } finally {
                        statusJob.cancel()
                        _status.value = null
                    }
                } finally {
                    withContext(NonCancellable) { link.disconnect() }
                }
            } catch (e: LinkLostException) {
                Log.i(TAG, "link lost: ${e.message}")
                if (!gotHello && e.wasConnected) {
                    if (e.status == STATUS_AUTH_FAILURE || ++refusals >= REFUSALS_BEFORE_BOND_LOST) {
                        return DongleState.BondLost
                    }
                }
            } catch (e: IOException) {
                Log.i(TAG, "connection failed: ${e.message}")
            } catch (e: TimeoutCancellationException) {
                Log.i(TAG, "no Hello from the dongle")
            }
            if (failures >= RECONNECT_DELAYS_MS.size) return restingState()
            delay(RECONNECT_DELAYS_MS[failures++])
        }
    }

    // Pairing

    /** Shows the system picker for nearby dongles; [onChooser] receives the picker to launch. */
    fun associate(onChooser: (IntentSender) -> Unit, onError: (CharSequence) -> Unit) {
        val filter = BluetoothLeDeviceFilter.Builder()
            .setScanFilter(ScanFilter.Builder().setServiceUuid(ParcelUuid(BridgeUuids.SERVICE)).build())
            .build()
        val request = AssociationRequest.Builder()
            .addDeviceFilter(filter)
            .setSingleDevice(false)
            .build()
        val callback = object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) = onChooser(intentSender)

            @Deprecated("Replaced by onAssociationPending on API 33")
            override fun onDeviceFound(intentSender: IntentSender) = onChooser(intentSender)

            override fun onAssociationCreated(associationInfo: AssociationInfo) = Unit

            override fun onFailure(error: CharSequence?) = onError(error ?: "pairing failed")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            companions.associate(request, Executor(Runnable::run), callback)
        } else {
            @Suppress("DEPRECATION")
            companions.associate(request, callback, null)
        }
    }

    /**
     * Completes pairing with the result of the picker: bonds (the system shows the passkey
     * dialog), starts presence observation and connects. Returns false if bonding failed.
     */
    suspend fun onAssociationResult(data: Intent): Boolean {
        val association = parseAssociation(data)
        if (association == null) {
            Log.i(TAG, "no device in the picker result")
            return false
        }
        val (address, id) = association
        Log.i(TAG, "associated with $address (id $id), bonding")
        _state.value = DongleState.Pairing
        val device = bluetooth.adapter.getRemoteDevice(address)
        val bonded = withTimeoutOrNull(BOND_TIMEOUT_MS) { bond(device) } ?: false
        if (!bonded) {
            disassociate(address, id)
            _state.value = restingState()
            return false
        }
        store.address = address
        store.associationId = id
        observePresence(address, id)
        _state.value = DongleState.Idle
        connect()
        return true
    }

    private fun parseAssociation(data: Intent): Pair<String, Int>? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val info = data.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)
            val mac = info?.deviceMacAddress ?: return null
            return mac.toString().uppercase() to info.id
        }
        @Suppress("DEPRECATION")
        val device = when (val extra = data.getParcelableExtra<android.os.Parcelable>(CompanionDeviceManager.EXTRA_DEVICE)) {
            is ScanResult -> extra.device
            is BluetoothDevice -> extra
            else -> return null
        }
        return device.address to DongleStore.NO_ASSOCIATION_ID
    }

    /**
     * Bonds with [device]. A failed attempt may be followed by another one (the phone and the
     * dongle both start pairing on connect), so a failure only counts once no new attempt starts
     * within [BOND_RETRY_GRACE_MS]. The bond state is also polled in case a broadcast is missed.
     */
    private suspend fun bond(device: BluetoothDevice): Boolean {
        if (device.bondState == BluetoothDevice.BOND_BONDED) return true
        val states = Channel<Int>(Channel.UNLIMITED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val changed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                if (changed?.address != device.address) return
                val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                val previous = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
                val reason = intent.getIntExtra(EXTRA_UNBOND_REASON, -1)
                Log.i(TAG, "bond state $previous -> $state, reason $reason")
                states.trySend(state)
            }
        }
        // The Bluetooth stack sends this protected broadcast from its own process, which a
        // not-exported receiver may not get; no app can forge it.
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        try {
            if (!device.createBond()) {
                Log.i(TAG, "createBond refused, bond state ${device.bondState}")
                return device.bondState == BluetoothDevice.BOND_BONDED
            }
            var failedAt = 0L
            while (true) {
                val state = withTimeoutOrNull(BOND_POLL_MS) { states.receive() } ?: device.bondState
                when (state) {
                    BluetoothDevice.BOND_BONDED -> return true
                    BluetoothDevice.BOND_BONDING -> failedAt = 0L
                    BluetoothDevice.BOND_NONE -> if (failedAt == 0L) failedAt = SystemClock.elapsedRealtime()
                }
                if (failedAt != 0L && SystemClock.elapsedRealtime() - failedAt >= BOND_RETRY_GRACE_MS) {
                    Log.i(TAG, "bonding failed")
                    return false
                }
            }
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    private fun observePresence(address: String, id: Int) {
        try {
            if (Build.VERSION.SDK_INT >= 36 && id != DongleStore.NO_ASSOCIATION_ID) {
                companions.startObservingDevicePresence(
                    ObservingDevicePresenceRequest.Builder().setAssociationId(id).build(),
                )
            } else {
                @Suppress("DEPRECATION")
                companions.startObservingDevicePresence(address)
            }
        } catch (e: Exception) {
            Log.w(TAG, "cannot observe dongle presence", e)
        }
    }

    /**
     * Unpairs: stops the connection and presence observation and removes the association.
     * Returns true if the phone's bond was removed too; otherwise the user has to remove the
     * dongle in Bluetooth settings.
     */
    fun forget(): Boolean {
        disconnect()
        val address = store.address ?: return true
        val id = store.associationId
        var bondRemoved = false
        try {
            if (Build.VERSION.SDK_INT >= 36 && id != DongleStore.NO_ASSOCIATION_ID) {
                companions.stopObservingDevicePresence(
                    ObservingDevicePresenceRequest.Builder().setAssociationId(id).build(),
                )
                bondRemoved = companions.removeBond(id)
            } else {
                @Suppress("DEPRECATION")
                companions.stopObservingDevicePresence(address)
            }
        } catch (e: Exception) {
            Log.w(TAG, "cannot stop observing dongle presence", e)
        }
        disassociate(address, id)
        store.clear()
        _state.value = DongleState.NotPaired
        return bondRemoved
    }

    private fun disassociate(address: String, id: Int) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && id != DongleStore.NO_ASSOCIATION_ID) {
                companions.disassociate(id)
            } else {
                @Suppress("DEPRECATION")
                companions.disassociate(address)
            }
        } catch (e: Exception) {
            Log.w(TAG, "cannot remove association", e)
        }
    }

    /** Re-reads permissions and pairing, for when the app returns to the foreground. */
    fun refresh() {
        if (!_running.value && _state.value != DongleState.Pairing) _state.value = restingState()
    }
}
