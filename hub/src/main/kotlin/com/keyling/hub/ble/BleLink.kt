package com.keyling.hub.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattConnectionSettings
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.util.Log
import com.keyling.hub.protocol.ProtocolException
import com.keyling.hub.protocol.Session
import com.keyling.hub.protocol.Transport
import com.keyling.hub.protocol.writeSize
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executor

object BridgeUuids {
    val SERVICE: UUID = UUID.fromString("7a1f0001-3c4e-4d8a-9b2e-5f6a7b8c9d0e")
    val CMD: UUID = UUID.fromString("7a1f0002-3c4e-4d8a-9b2e-5f6a7b8c9d0e")
    val EVT: UUID = UUID.fromString("7a1f0003-3c4e-4d8a-9b2e-5f6a7b8c9d0e")
    val CTRL: UUID = UUID.fromString("7a1f0004-3c4e-4d8a-9b2e-5f6a7b8c9d0e")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

private const val TAG = "BleLink"
private const val REQUESTED_MTU = 517
private const val BUSY_RETRIES = 50
private const val BUSY_RETRY_MS = 10L
private const val DISCONNECT_TIMEOUT_MS = 2_000L
private const val OP_TIMEOUT_MS = 10_000L
private const val MTU_TIMEOUT_MS = 3_000L

/** Encryption may run first when notifications are enabled, so this waits longer. */
private const val CCCD_TIMEOUT_MS = 30_000L

/** [GattException.status] when the operation's callback never came. */
const val STATUS_TIMEOUT = -2

class GattException(operation: String, val status: Int) :
    IOException(if (status == STATUS_TIMEOUT) "$operation timed out" else "$operation failed (GATT status $status)")

/**
 * The connection went down; [status] is the status Android reported with it. [wasConnected] is
 * false when the connection never came up.
 */
class LinkLostException(val status: Int, val wasConnected: Boolean = true) :
    IOException("link lost (GATT status $status)")

/**
 * A GATT connection to the dongle's bridge service.
 *
 * Android allows one outstanding GATT operation per connection, so every operation waits for
 * its callback before the next starts. Callers must hold BLUETOOTH_CONNECT.
 */
@SuppressLint("MissingPermission")
class BleLink private constructor(val device: BluetoothDevice) : Transport {
    private enum class Kind { MTU, DISCOVER, WRITE, DESCRIPTOR_WRITE }

    private class Pending(val kind: Kind) {
        val done = CompletableDeferred<Int>()
    }

    private lateinit var gatt: BluetoothGatt
    private lateinit var cmd: BluetoothGattCharacteristic
    private lateinit var evt: BluetoothGattCharacteristic
    private lateinit var ctrl: BluetoothGattCharacteristic

    private val connected = CompletableDeferred<Unit>()
    private val _disconnected = CompletableDeferred<Int>()
    private val opMutex = Mutex()
    @Volatile private var pending: Pending? = null
    @Volatile private var session: Session? = null
    @Volatile private var mtu = 23

    /** Completes with Android's status when the connection goes down. */
    val disconnected: Deferred<Int> get() = _disconnected

    override val writeSize: Int get() = writeSize(mtu)

    /** Completes the pending operation if it is of [kind]; callbacks of other kinds are unsolicited. */
    private fun finish(kind: Kind, status: Int) {
        val op = pending
        if (op?.kind == kind) op.done.complete(status) else Log.i(TAG, "unsolicited $kind callback, status $status")
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "connection state $newState, status $status")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> connected.complete(Unit)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    gatt.close()
                    val lost = LinkLostException(status, wasConnected = connected.isCompleted)
                    connected.completeExceptionally(lost)
                    pending?.done?.completeExceptionally(lost)
                    session?.close(lost)
                    _disconnected.complete(status)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.i(TAG, "services discovered, status $status")
            finish(Kind.DISCOVER, status)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "MTU $mtu, status $status")
            if (status == BluetoothGatt.GATT_SUCCESS) this@BleLink.mtu = mtu
            finish(Kind.MTU, status)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) Log.i(TAG, "write ${characteristic.uuid}, status $status")
            finish(Kind.WRITE, status)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.i(TAG, "descriptor write, status $status")
            finish(Kind.DESCRIPTOR_WRITE, status)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid != BridgeUuids.EVT) return
            val session = session ?: return
            try {
                session.onEvt(value)
            } catch (e: ProtocolException) {
                Log.w(TAG, "bad EVT stream", e)
                session.close(e)
                gatt.disconnect()
            }
        }

        @Deprecated("Called instead of the ByteArray overload before API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                onCharacteristicChanged(gatt, characteristic, characteristic.value)
            }
        }
    }

    companion object {
        /** Connects to [device], negotiates the MTU and finds the bridge service. */
        suspend fun connect(context: Context, device: BluetoothDevice): BleLink {
            Log.i(TAG, "connecting to ${device.address}, bond state ${device.bondState}")
            val link = BleLink(device)
            link.gatt = if (Build.VERSION.SDK_INT >= 37) {
                val settings = BluetoothGattConnectionSettings.Builder()
                    .setTransport(BluetoothDevice.TRANSPORT_LE)
                    .build()
                device.connectGatt(settings, Executor(Runnable::run), link.callback)
            } else {
                @Suppress("DEPRECATION")
                device.connectGatt(context, false, link.callback, BluetoothDevice.TRANSPORT_LE)
            } ?: throw IOException("could not start a connection to ${device.address}")
            try {
                link.connected.await()
                link.setUp()
            } catch (e: Throwable) {
                Log.i(TAG, "connection setup failed: $e")
                withContext(NonCancellable) { link.disconnect() }
                throw e
            }
            return link
        }
    }

    private suspend fun setUp() {
        try {
            operation(Kind.MTU, "request MTU", MTU_TIMEOUT_MS) { gatt.requestMtu(REQUESTED_MTU).toStatus() }
        } catch (e: GattException) {
            Log.i(TAG, "MTU request: ${e.message}; using MTU $mtu")
        }
        operation(Kind.DISCOVER, "discover services") { gatt.discoverServices().toStatus() }
        val service = gatt.getService(BridgeUuids.SERVICE)
            ?: throw IOException("${device.address} has no bridge service")
        fun characteristic(uuid: UUID) = service.getCharacteristic(uuid)
            ?: throw IOException("bridge service lacks $uuid")
        cmd = characteristic(BridgeUuids.CMD)
        evt = characteristic(BridgeUuids.EVT)
        ctrl = characteristic(BridgeUuids.CTRL)
    }

    /**
     * Starts a protocol session by enabling EVT notifications. The session closes when the link
     * goes down; [scope] runs its writer.
     *
     * For a bonded phone the dongle restores the subscription on encryption and may start a
     * session before Android is listening, losing its Hello. Disabling notifications first ends
     * that session, so enabling them starts a new one that arrives here.
     */
    suspend fun startSession(scope: CoroutineScope): Session {
        val session = Session(this, scope)
        if (!gatt.setCharacteristicNotification(evt, true)) {
            throw GattException("enable EVT notifications", -1)
        }
        val cccd = evt.getDescriptor(BridgeUuids.CCCD) ?: throw IOException("EVT has no CCCD")
        operation(Kind.DESCRIPTOR_WRITE, "disable EVT notifications", CCCD_TIMEOUT_MS) {
            writeDescriptor(cccd, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
        }
        this.session = session
        operation(Kind.DESCRIPTOR_WRITE, "enable EVT notifications") {
            writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        }
        Log.i(TAG, "session started, MTU $mtu")
        return session
    }

    override suspend fun writeCmd(bytes: ByteArray) {
        operation(Kind.WRITE, "write CMD") {
            writeCharacteristic(cmd, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        }
    }

    override suspend fun writeCtrl() {
        operation(Kind.WRITE, "write CTRL") {
            writeCharacteristic(ctrl, byteArrayOf(1), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        }
    }

    /** Disconnects and waits briefly for Android to confirm. */
    suspend fun disconnect() {
        if (_disconnected.isCompleted) return
        gatt.disconnect()
        if (withTimeoutOrNull(DISCONNECT_TIMEOUT_MS) { _disconnected.await() } == null) {
            gatt.close()
            val lost = LinkLostException(BluetoothGatt.GATT_SUCCESS)
            session?.close(lost)
            _disconnected.complete(BluetoothGatt.GATT_SUCCESS)
        }
    }

    /**
     * Runs one GATT operation: [start] issues it and returns Android's status for issuing it,
     * and the callback of [kind] completes it. Fails if no callback comes within [timeoutMs].
     */
    private suspend fun operation(kind: Kind, name: String, timeoutMs: Long = OP_TIMEOUT_MS, start: () -> Int) =
        opMutex.withLock {
            if (_disconnected.isCompleted) throw LinkLostException(_disconnected.await())
            val op = Pending(kind)
            pending = op
            try {
                var started = start()
                var retries = 0
                while (started == BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY && retries++ < BUSY_RETRIES) {
                    delay(BUSY_RETRY_MS)
                    started = start()
                }
                if (started != BluetoothStatusCodes.SUCCESS) throw GattException("$name (start)", started)
                val status = withTimeoutOrNull(timeoutMs) {
                    select<Int> {
                        op.done.onAwait { it }
                        _disconnected.onAwait { throw LinkLostException(it) }
                    }
                } ?: throw GattException(name, STATUS_TIMEOUT)
                if (status != BluetoothGatt.GATT_SUCCESS) throw GattException(name, status)
            } finally {
                pending = null
            }
        }

    private fun Boolean.toStatus() = if (this) BluetoothStatusCodes.SUCCESS else BluetoothStatusCodes.ERROR_UNKNOWN

    private fun writeCharacteristic(c: BluetoothGattCharacteristic, value: ByteArray, type: Int): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(c, value, type)
        } else {
            @Suppress("DEPRECATION")
            c.writeType = type
            @Suppress("DEPRECATION")
            c.value = value
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(c).toStatus()
        }

    private fun writeDescriptor(d: BluetoothGattDescriptor, value: ByteArray): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(d, value)
        } else {
            @Suppress("DEPRECATION")
            d.value = value
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(d).toStatus()
        }
}
