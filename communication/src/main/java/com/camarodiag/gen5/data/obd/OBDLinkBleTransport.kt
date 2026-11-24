package com.camarodiag.gen5.data.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import java.util.UUID

/**
 * OBDLink CX BLE serial transport.
 *
 * CX uses the documented OBDLink custom UART service FFF0, notifications FFF1
 * and writes FFF2. It is intentionally separate from Bluetooth Classic SPP.
 */
@SuppressLint("MissingPermission")
internal class OBDLinkBleTransport(private val context: Context) {
    private val serviceUuid = UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB")
    private val notifyUuid = UUID.fromString("0000FFF1-0000-1000-8000-00805F9B34FB")
    private val writeUuid = UUID.fromString("0000FFF2-0000-1000-8000-00805F9B34FB")
    private val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    @Volatile private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var mtuPayload = 20
    private var characteristicWriteType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    /** True only while the current GATT session has not reported a disconnect. */
    val isConnected: Boolean
        get() = gatt != null && connectionFailure == null

    // Fixed upper bound: an unexpectedly chatty adapter must never exhaust
    // the Android process heap while the UI pauses consumption.
    private val incoming = BleNotificationBuffer()
    private var pending = ByteArray(0)
    @Volatile private var connectionFailure: Throwable? = null

    private var connectResult: CompletableDeferred<Unit>? = null
    private var writeResult: CompletableDeferred<Unit>? = null
    /**
     * One GATT handshake at a time. Without this lock, a delayed failure from
     * a superseded connect() could call close() against the newly selected
     * BLE adapter and clear its notification or write state.
     */
    private val connectionMutex = Mutex()
    /** Serializes GATT writes so callbacks cannot complete the wrong operation. */
    private val writeMutex = Mutex()
    /** Only one reader may consume the shared response buffer at a time. */
    private val readMutex = Mutex()
    private val readWakeup = ObdReadWakeup()
    private var mtuResult: CompletableDeferred<Unit>? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            // Ignore callbacks from a GATT instance that was closed/replaced by a reconnect.
            if (g !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                val failure = ObdTransportDisconnectedException("OBDLink BLE connection failed (status $status)")
                connectionFailure = failure
                connectResult?.completeExceptionally(failure)
                writeResult?.completeExceptionally(failure)
                readWakeup.signal(failure)
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (!g.discoverServices()) {
                    connectResult?.completeExceptionally(IllegalStateException("BLE service discovery could not start"))
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val failure = ObdTransportDisconnectedException("OBDLink BLE disconnected")
                connectionFailure = failure
                connectResult?.completeExceptionally(failure)
                writeResult?.completeExceptionally(failure)
                // Wake a reader waiting for notifications so it can surface the disconnect.
                readWakeup.signal(failure)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                connectResult?.completeExceptionally(IllegalStateException("BLE service discovery failed ($status)"))
                return
            }
            val service = g.getService(serviceUuid)
            val notify = service?.getCharacteristic(notifyUuid)
            val write = service?.getCharacteristic(writeUuid)
            if (notify == null || write == null) {
                connectResult?.completeExceptionally(IllegalStateException("OBDLink BLE UART service FFF0/FFF1/FFF2 not found"))
                return
            }
            val notificationValue = when {
                notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ->
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                notify.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 ->
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                else -> {
                    connectResult?.completeExceptionally(IllegalStateException("OBDLink BLE receive characteristic does not support notifications or indications"))
                    return
                }
            }
            characteristicWriteType = when {
                write.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ->
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                write.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 ->
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                else -> {
                    connectResult?.completeExceptionally(IllegalStateException("OBDLink BLE transmit characteristic does not support writes"))
                    return
                }
            }
            writeCharacteristic = write
            if (!g.setCharacteristicNotification(notify, true)) {
                connectResult?.completeExceptionally(IllegalStateException("Unable to enable OBDLink BLE notifications"))
                return
            }
            val descriptor = notify.getDescriptor(cccdUuid)
            if (descriptor == null) {
                connectResult?.completeExceptionally(IllegalStateException("BLE notification descriptor not found"))
                return
            }
            val started = if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(descriptor, notificationValue) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = notificationValue
                @Suppress("DEPRECATION")
                g.writeDescriptor(descriptor)
            }
            if (!started) {
                connectResult?.completeExceptionally(IllegalStateException("Unable to subscribe to OBDLink BLE notifications"))
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (g !== gatt) return
            if (status == BluetoothGatt.GATT_SUCCESS) connectResult?.complete(Unit)
            else connectResult?.completeExceptionally(IllegalStateException("BLE notification subscription failed ($status)"))
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (g !== gatt) return
            if (status == BluetoothGatt.GATT_SUCCESS) mtuPayload = (mtu - 3).coerceAtLeast(20)
            mtuResult?.complete(Unit)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (g !== gatt) return
            if (status == BluetoothGatt.GATT_SUCCESS) writeResult?.complete(Unit)
            else writeResult?.completeExceptionally(IllegalStateException("BLE write failed ($status)"))
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (g !== gatt) return
            if (characteristic.uuid == notifyUuid) {
                readLegacyNotificationValue(characteristic)?.let { enqueueNotification(g, it) }
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (g !== gatt) return
            if (characteristic.uuid == notifyUuid) enqueueNotification(g, value)
        }
    }

    private fun enqueueNotification(session: BluetoothGatt, bytes: ByteArray) {
        if (gatt !== session) return
        if (!incoming.offer(bytes)) {
            // Dropping a byte from an ELM serial response changes its meaning.
            // Retire the transport instead of silently truncating the stream.
            invalidateSession(session, ObdTransportDisconnectedException(
                "OBDLink BLE receive buffer full or invalid notification"
            ))
        }
    }

    suspend fun connect(device: BluetoothDevice): Result<Unit> =
        connectionMutex.withLock {
            try {
                close()
                connectionFailure = null
                connectResult = CompletableDeferred()
                gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                    ?: throw IllegalStateException("Unable to create BLE connection")
                withTimeout(15_000) { connectResult!!.await() }
                mtuResult = CompletableDeferred()
                gatt?.requestMtu(247)
                withTimeoutOrNull(3_000) { mtuResult?.await() }
                Result.success(Unit)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                close()
                // A cancelled adapter switch must propagate to the ViewModel.
                // A timeout started by this BLE handshake, however, leaves the
                // caller job active and is a normal connection failure.
                if (!kotlinx.coroutines.currentCoroutineContext().isActive) throw cancelled
                Result.failure(cancelled)
            } catch (e: Exception) {
                close()
                Result.failure(e)
            }
        }

    suspend fun write(data: ByteArray) = writeMutex.withLock {
        var writeSession: BluetoothGatt? = null
        try {
            connectionFailure?.let {
                throw ObdTransportDisconnectedException("OBDLink BLE connection is unavailable", it)
            }
            val g = gatt ?: error("BLE transport not connected")
            writeSession = g
            val characteristic = writeCharacteristic ?: error("OBDLink BLE write characteristic unavailable")
            var offset = 0
            while (offset < data.size) {
                // A disconnect may arrive between BLE chunks.
                connectionFailure?.let {
                    throw ObdTransportDisconnectedException("OBDLink BLE disconnected during write", it)
                }
                check(gatt === g) { "BLE transport session was replaced during write" }
                val count = minOf(mtuPayload, data.size - offset)
                val chunk = data.copyOfRange(offset, offset + count)
                val result = CompletableDeferred<Unit>()
                writeResult = result
                val started = if (Build.VERSION.SDK_INT >= 33) {
                    g.writeCharacteristic(characteristic, chunk, characteristicWriteType) == BluetoothStatusCodes.SUCCESS
                } else {
                    writeLegacyCharacteristic(g, characteristic, chunk)
                }
                check(started) { "Unable to start OBDLink BLE write" }
                // Also wait for WRITE_TYPE_NO_RESPONSE: it skips the peer's ATT
                // acknowledgement, but Android still reports local GATT completion.
                // Sending the next chunk before this callback can make Android reject
                // it as GATT_BUSY and silently truncate an ELM command.
                withTimeout(4_000) { result.await() }
                check(gatt === g) { "BLE transport session was replaced during write" }
                offset += count
            }
        } catch (error: Throwable) {
            // GATT callbacks carry no operation identifier. After a timeout or
            // cancellation, a late callback could otherwise complete the next write.
            // Retire this GATT instance so callbacks from it can never affect a retry.
            writeSession?.let { invalidateSession(it, error) }
            throw error
        }
    }

    /**
     * Retires a GATT session after an ambiguous write outcome. A late callback from
     * this instance is ignored because callbacks verify identity against [gatt].
     * The failure remains visible until connect() starts a fresh session.
     */
    private fun invalidateSession(expectedGatt: BluetoothGatt, cause: Throwable) {
        // A reconnect may have replaced this session while the old coroutine was
        // unwinding. Never let an old write tear down the newly connected adapter.
        if (gatt !== expectedGatt) return
        connectionFailure = cause
        connectResult?.completeExceptionally(cause)
        writeResult?.completeExceptionally(cause)
        mtuResult?.completeExceptionally(cause)

        val failedGatt = gatt
        gatt = null
        writeCharacteristic = null
        characteristicWriteType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        mtuPayload = 20
        pending = ByteArray(0)
        readWakeup.signal(cause)

        connectResult = null
        writeResult = null
        mtuResult = null

        try { failedGatt?.disconnect() } catch (_: Exception) {}
        try { failedGatt?.close() } catch (_: Exception) {}
    }

    /**
     * Android before API 33 has no value parameter on onCharacteristicChanged,
     * so the deprecated getter is required only for that legacy callback.
     */
    @Suppress("DEPRECATION")
    private fun readLegacyNotificationValue(characteristic: BluetoothGattCharacteristic): ByteArray? =
        characteristic.value?.copyOf()

    /** Isolates deprecated characteristic setters used only by Android before API 33. */
    @Suppress("DEPRECATION")
    private fun writeLegacyCharacteristic(
        bluetoothGatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        chunk: ByteArray
    ): Boolean {
        characteristic.writeType = characteristicWriteType
        characteristic.value = chunk
        return bluetoothGatt.writeCharacteristic(characteristic)
    }

    suspend fun read(buffer: ByteArray, timeoutMs: Int): Int = readMutex.withLock {
        connectionFailure?.let { throw ObdTransportDisconnectedException("OBDLink BLE disconnected while reading", it) }
        val readSession = gatt
            ?: throw ObdTransportDisconnectedException("OBDLink BLE is not connected", connectionFailure)
        if (pending.isEmpty()) {
            val wakeup = readWakeup.beginWait()
            try {
                // close() can run after readSession is captured but before beginWait().
                // In that ordering its one-shot wakeup has no waiter to signal, so
                // verify the GATT identity again before entering the suspending select.
                if (gatt !== readSession) {
                    throw ObdTransportDisconnectedException(
                        "OBDLink BLE session changed before reading",
                        connectionFailure
                    )
                }
                // Recheck after registering to close the race with a disconnect callback.
                connectionFailure?.let(wakeup::complete)
                val packet = withTimeoutOrNull(timeoutMs.toLong()) {
                    select<ByteArray> {
                        // select is biased: prefer the close signal if both clauses are ready.
                        wakeup.onAwait { cause ->
                            throw ObdTransportDisconnectedException(
                                "OBDLink BLE disconnected while reading",
                                cause
                            )
                        }
                        incoming.packets.onReceive { it }
                    }
                } ?: run {
                    if (gatt !== readSession) {
                        throw ObdTransportDisconnectedException(
                            "OBDLink BLE session changed while reading",
                            connectionFailure
                        )
                    }
                    connectionFailure?.let {
                        throw ObdTransportDisconnectedException("OBDLink BLE disconnected while reading", it)
                    }
                    return@withLock 0
                }
                connectionFailure?.let {
                    throw ObdTransportDisconnectedException("OBDLink BLE disconnected while reading", it)
                }
                if (gatt !== readSession) {
                    throw ObdTransportDisconnectedException(
                        "OBDLink BLE session changed while reading",
                        connectionFailure
                    )
                }
                pending = packet
            } finally {
                readWakeup.endWait(wakeup)
            }
        }
        val count = minOf(buffer.size, pending.size)
        pending.copyInto(buffer, 0, 0, count)
        pending = pending.copyOfRange(count, pending.size)
        if (gatt !== readSession) {
            pending = ByteArray(0)
            throw ObdTransportDisconnectedException(
                "OBDLink BLE session changed while reading",
                connectionFailure
            )
        }
        count
    }

    fun close() {
        // Detach before invoking Bluetooth API teardown. Some vendor stacks
        // deliver synchronous disconnect/descriptor callbacks from disconnect()
        // or close(); none may mutate a replacement session's state.
        val closingGatt = gatt
        gatt = null
        // Wake readers without enqueuing a fake packet that a new session could consume.
        val closed = ObdTransportDisconnectedException("OBDLink BLE session was closed")
        readWakeup.signal(closed)
        connectResult?.completeExceptionally(closed)
        writeResult?.completeExceptionally(closed)
        mtuResult?.completeExceptionally(closed)
        try { closingGatt?.disconnect() } catch (_: Exception) {}
        try { closingGatt?.close() } catch (_: Exception) {}
        writeCharacteristic = null
        characteristicWriteType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        // A new GATT session starts at the default ATT MTU until negotiation succeeds.
        mtuPayload = 20
        pending = ByteArray(0)
        incoming.clear()
        connectResult = null
        writeResult = null
        connectionFailure = null
        mtuResult = null
    }
}


/**
 * Bounded loss-intolerant BLE serial receive buffer.
 * 128 packets * at most 514 bytes (maximum ATT payload at MTU 517) keeps
 * pending notifications under 65 KiB. Full queues fail closed: dropping bytes
 * and continuing would silently corrupt diagnostics or a command response.
 */
internal class BleNotificationBuffer(
    capacity: Int = 128
) {
    init { require(capacity in 1..1024) }
    val packets = Channel<ByteArray>(capacity)

    fun offer(bytes: ByteArray): Boolean =
        bytes.isNotEmpty() && bytes.size <= 514 &&
            packets.trySend(bytes.copyOf()).isSuccess

    fun clear() {
        while (packets.tryReceive().isSuccess) { }
    }
}

/** One-shot wakeup for the single active BLE reader; signals are never queued across sessions. */
internal class ObdReadWakeup {
    @Volatile private var waiting: CompletableDeferred<Throwable>? = null

    fun beginWait(): CompletableDeferred<Throwable> =
        CompletableDeferred<Throwable>().also { waiting = it }

    fun signal(cause: Throwable) {
        waiting?.complete(cause)
    }

    fun endWait(candidate: CompletableDeferred<Throwable>) {
        if (waiting === candidate) waiting = null
    }
}
