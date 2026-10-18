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
import com.keyling.hub.protocol.ProtocolException
import com.keyling.hub.protocol.Session
import com.keyling.hub.protocol.Transport
import com.keyling.hub.protocol.writeSize
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

private const val REQUESTED_MTU = 517
private const val BUSY_RETRIES = 50
private const val BUSY_RETRY_MS = 10L
private const val DISCONNECT_TIMEOUT_MS = 2000L

class GattException(operation: String, val status: Int) :
    IOException("$operation failed (GATT status $status)")

/** The connection went down; [status] is the status Android reported with it. */
class LinkLostException(val status: Int) : IOException("link lost (GATT status $status)")

/**
 * A GATT connection to the dongle's bridge service.
 *
 * Android allows one outstanding GATT operation per connection, so every operation waits for
 * its callback before the next starts. Callers must hold BLUETOOTH_CONNECT.
 */
@SuppressLint("MissingPermission")
class BleLink private constructor(val device: BluetoothDevice) : Transport {
    private lateinit var gatt: BluetoothGatt
    private lateinit var cmd: BluetoothGattCharacteristic
    private lateinit var evt: BluetoothGattCharacteristic
    private lateinit var ctrl: BluetoothGattCharacteristic

    private val connected = CompletableDeferred<Unit>()
    private val _disconnected = CompletableDeferred<Int>()
    private val opMutex = Mutex()
    @Volatile private var op: CompletableDeferred<Int>? = null
    @Volatile private var session: Session? = null
    @Volatile private var mtu = 23

    /** Completes with Android's status when the connection goes down. */
    val disconnected: Deferred<Int> get() = _disconnected

    override val writeSize: Int get() = writeSize(mtu)

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> connected.complete(Unit)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    gatt.close()
                    val lost = LinkLostException(status)
                    connected.completeExceptionally(lost)
                    op?.completeExceptionally(lost)
                    session?.close(lost)
                    _disconnected.complete(status)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            op?.complete(status)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) this@BleLink.mtu = mtu
            op?.complete(status)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            op?.complete(status)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            op?.complete(status)
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
                link.disconnect()
                throw e
            }
            return link
        }
    }

    private suspend fun setUp() {
        operation("request MTU") { gatt.requestMtu(REQUESTED_MTU).toStatus() }
        operation("discover services") { gatt.discoverServices().toStatus() }
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
     */
    suspend fun startSession(scope: CoroutineScope): Session {
        val session = Session(this, scope)
        this.session = session
        if (!gatt.setCharacteristicNotification(evt, true)) {
            throw GattException("enable EVT notifications", -1)
        }
        val cccd = evt.getDescriptor(BridgeUuids.CCCD) ?: throw IOException("EVT has no CCCD")
        operation("enable EVT notifications") {
            writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        }
        return session
    }

    override suspend fun writeCmd(bytes: ByteArray) {
        operation("write CMD") {
            writeCharacteristic(cmd, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        }
    }

    override suspend fun writeCtrl() {
        operation("write CTRL") {
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
     * Runs one GATT operation: [start] issues it and returns whether Android accepted it, and
     * the operation's callback completes it with a status.
     */
    private suspend fun operation(name: String, start: () -> Int) = opMutex.withLock {
        if (_disconnected.isCompleted) throw LinkLostException(_disconnected.await())
        val done = CompletableDeferred<Int>()
        op = done
        try {
            var started = start()
            var retries = 0
            while (started == BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY && retries++ < BUSY_RETRIES) {
                delay(BUSY_RETRY_MS)
                started = start()
            }
            if (started != BluetoothStatusCodes.SUCCESS) throw GattException("$name (start)", started)
            val status = select {
                done.onAwait { it }
                _disconnected.onAwait { throw LinkLostException(it) }
            }
            if (status != BluetoothGatt.GATT_SUCCESS) throw GattException(name, status)
        } finally {
            op = null
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
