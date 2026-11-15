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
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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

    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var mtuPayload = 20
    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
    private var pending = ByteArray(0)

    private var connectResult: CompletableDeferred<Unit>? = null
    private var writeResult: CompletableDeferred<Unit>? = null
    private var descriptorResult: CompletableDeferred<Unit>? = null
    private var mtuResult: CompletableDeferred<Unit>? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                connectResult?.completeExceptionally(IllegalStateException("BLE connection failed (status $status)"))
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (!g.discoverServices()) {
                    connectResult?.completeExceptionally(IllegalStateException("BLE service discovery could not start"))
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectResult?.completeExceptionally(IllegalStateException("OBDLink BLE disconnected"))
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
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
            descriptorResult = CompletableDeferred()
            val started = if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(descriptor)
            }
            if (!started) {
                connectResult?.completeExceptionally(IllegalStateException("Unable to subscribe to OBDLink BLE notifications"))
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) connectResult?.complete(Unit)
            else connectResult?.completeExceptionally(IllegalStateException("BLE notification subscription failed ($status)"))
            descriptorResult?.complete(Unit)
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtuPayload = (mtu - 3).coerceAtLeast(20)
            mtuResult?.complete(Unit)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) writeResult?.complete(Unit)
            else writeResult?.completeExceptionally(IllegalStateException("BLE write failed ($status)"))
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            // Android versions below API 33 only expose the characteristic value through this callback.
            // API 33+ uses the overload below, which supplies an immutable value snapshot directly.
            if (characteristic.uuid == notifyUuid) {
                val legacyValue = characteristic.value
                if (legacyValue != null) incoming.trySend(legacyValue.copyOf())
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == notifyUuid) incoming.trySend(value.copyOf())
        }
    }

    suspend fun connect(device: BluetoothDevice): Result<Unit> {
        return try {
            close()
            connectResult = CompletableDeferred()
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                ?: throw IllegalStateException("Unable to create BLE connection")
            withTimeout(15_000) { connectResult!!.await() }
            mtuResult = CompletableDeferred()
            gatt?.requestMtu(247)
            withTimeoutOrNull(3_000) { mtuResult?.await() }
            Result.success(Unit)
        } catch (e: Exception) {
            close()
            Result.failure(e)
        }
    }

    suspend fun write(data: ByteArray) {
        val g = gatt ?: error("BLE transport not connected")
        val characteristic = writeCharacteristic ?: error("OBDLink BLE write characteristic unavailable")
        var offset = 0
        while (offset < data.size) {
            val count = minOf(mtuPayload, data.size - offset)
            val chunk = data.copyOfRange(offset, offset + count)
            writeResult = CompletableDeferred()
            val started = if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(characteristic, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                characteristic.value = chunk
                @Suppress("DEPRECATION")
                g.writeCharacteristic(characteristic)
            }
            check(started) { "Unable to start OBDLink BLE write" }
            withTimeout(4_000) { writeResult!!.await() }
            offset += count
        }
    }

    suspend fun read(buffer: ByteArray, timeoutMs: Int): Int {
        if (pending.isEmpty()) {
            val packet = withTimeoutOrNull(timeoutMs.toLong()) { incoming.receive() } ?: return 0
            pending = packet
        }
        val count = minOf(buffer.size, pending.size)
        pending.copyInto(buffer, 0, 0, count)
        pending = pending.copyOfRange(count, pending.size)
        return count
    }

    fun close() {
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        writeCharacteristic = null
        pending = ByteArray(0)
        while (incoming.tryReceive().isSuccess) { }
        connectResult = null
        writeResult = null
        descriptorResult = null
        mtuResult = null
    }
}
