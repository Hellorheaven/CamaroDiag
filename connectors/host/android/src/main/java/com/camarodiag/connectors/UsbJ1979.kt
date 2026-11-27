package com.camarodiag.connectors

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.IOException
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.util.function.BooleanSupplier

/** Dedicated OTG CU channel. No discovery/ELM fallback after authentication failure. */
object UsbJ1979 {
    @JvmStatic
    @Throws(IOException::class)
    fun capture(manager: UsbManager?, device: UsbDevice?, controlId: Int, dataId: Int,
                tx: Int, rx: Int, extended: Boolean, budget: Int, credential: ByteArray?,
                raw: OutputStream?, stillSelected: BooleanSupplier?): J1979UsbCapture.Result {
        require(J1979UsbAuth.physicalPair(tx, rx, extended) && budget in 1..600000) { "Invalid CU/1 profile" }
        return open(manager, device, controlId, dataId, credential, raw, stillSelected) { link, key, output, _ ->
            J1979UsbCapture(link).run(tx, rx, extended, budget, key, output)
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun captureFreeze(manager: UsbManager?, device: UsbDevice?, controlId: Int, dataId: Int,
                      profile: J1979FreezeUsbAuth.Profile?, credential: ByteArray?,
                      raw: OutputStream?, stillSelected: BooleanSupplier?): J1979FreezeUsbCapture.Result {
        requireNotNull(profile) { "Explicit CU/2 profile required" }
        return open(manager, device, controlId, dataId, credential, raw, stillSelected) { link, key, output, selected ->
            J1979FreezeUsbCapture(link).run(profile, key, output, selected)
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun captureLive(manager: UsbManager?, device: UsbDevice?, controlId: Int, dataId: Int,
                    profile: J1979LiveUsbAuth.Profile?, credential: ByteArray?,
                    raw: OutputStream?, stillSelected: BooleanSupplier?): J1979LiveNativeReplay.Result {
        requireNotNull(profile) { "Explicit CU/3 profile required" }
        return open(manager, device, controlId, dataId, credential, raw, stillSelected) { link, key, output, selected ->
            J1979LiveUsbCapture(link).run(profile, key, output, selected)
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun captureMaintenance(manager: UsbManager?, device: UsbDevice?, controlId: Int, dataId: Int,
                           profile: J1979MaintenanceUsb.Profile?, credential: ByteArray?,
                           raw: OutputStream?, stillSelected: BooleanSupplier?): MutableMap<String, Any> {
        requireNotNull(profile) { "Explicit CU/4 profile required" }
        return open(manager, device, controlId, dataId, credential, raw, stillSelected) { link, key, output, selected ->
            J1979MaintenanceUsb(link).run(profile, key, output, selected)
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun captureMaintenanceArchive(manager: UsbManager?, device: UsbDevice?, controlId: Int, dataId: Int,
                                  profile: J1979MaintenanceUsb.Profile?, credential: ByteArray?,
                                  raw: OutputStream?, stillSelected: BooleanSupplier?): MutableMap<String, Any> {
        requireNotNull(profile) { "Explicit CU/5 archive scope required" }
        return open(manager, device, controlId, dataId, credential, raw, stillSelected) { link, key, output, selected ->
            J1979MaintenanceUsb(link).runArchive(profile, key, output, selected)
        }
    }

    private data class Endpoints(
        val control: UsbInterface, val data: UsbInterface,
        val input: UsbEndpoint, val output: UsbEndpoint
    )

    private fun endpoints(device: UsbDevice, controlId: Int, dataId: Int): Endpoints {
        if (controlId == dataId) throw IOException("CDC interfaces not supported")
        var control: UsbInterface? = null
        var data: UsbInterface? = null
        for (index in 0 until device.interfaceCount) {
            val candidate = device.getInterface(index)
            if (candidate.id == controlId && candidate.interfaceClass == UsbConstants.USB_CLASS_COMM) {
                if (control != null) throw IOException("Ambiguous CDC control")
                control = candidate
            }
            if (candidate.id == dataId && candidate.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA) {
                if (data != null) throw IOException("Ambiguous CDC data")
                data = candidate
            }
        }
        if (control == null || data == null) throw IOException("CDC interfaces not supported")
        var input: UsbEndpoint? = null
        var output: UsbEndpoint? = null
        for (index in 0 until data.endpointCount) {
            val endpoint = data.getEndpoint(index)
            if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            when (endpoint.direction) {
                UsbConstants.USB_DIR_IN -> {
                    if (input != null) throw IOException("Ambiguous CDC input")
                    input = endpoint
                }
                UsbConstants.USB_DIR_OUT -> {
                    if (output != null) throw IOException("Ambiguous CDC output")
                    output = endpoint
                }
            }
        }
        if (input == null || output == null || input.maxPacketSize !in 1..1024 ||
            output.maxPacketSize !in 1..1024) throw IOException("Invalid CDC endpoints")
        return Endpoints(control, data, input, output)
    }

    private fun <T> open(
        manager: UsbManager?, device: UsbDevice?, controlId: Int, dataId: Int,
        credential: ByteArray?, raw: OutputStream?, stillSelected: BooleanSupplier?,
        session: (J1979UsbCapture.Link, ByteArray, OutputStream, BooleanSupplier) -> T
    ): T {
        require(manager != null && device != null && credential != null && credential.size == 32 &&
            raw != null && stillSelected != null && stillSelected.asBoolean) {
            "Explicit native USB context required"
        }
        fun current(): Boolean = stillSelected.asBoolean && manager.hasPermission(device) &&
            device == manager.deviceList[device.deviceName]
        if (!current()) throw IOException("USB permission or selected device context required")
        val endpoints = endpoints(device, controlId, dataId)
        val key = credential.copyOf()
        try {
            val connection = manager.openDevice(device) ?: throw IOException("Cannot open USB device")
            var claimedControl = false
            var claimedData = false
            try {
                if (!current()) throw IOException("USB context changed")
                claimedControl = connection.claimInterface(endpoints.control, false)
                if (!claimedControl) throw IOException("Cannot claim CDC control")
                if (!current()) throw IOException("USB context changed")
                claimedData = connection.claimInterface(endpoints.data, false)
                if (!claimedData) throw IOException("Cannot claim CDC data")
                val line = byteArrayOf(0, 0xc2.toByte(), 1, 0, 0, 0, 8)
                if (!current() || connection.controlTransfer(0x21, 0x20, 0, controlId, line, 7, 1000) != 7 ||
                    !current() || connection.controlTransfer(0x21, 0x22, 1, controlId, null, 0, 1000) != 0 ||
                    !current()) throw IOException("CDC initialization rejected")
                val liveContext = BooleanSupplier { current() }
                val link = CdcLink(connection, endpoints, liveContext)
                return try {
                    session(link, key, raw, liveContext)
                } finally {
                    link.close()
                }
            } finally {
                // All resources are owned by this scope, even when a release call throws.
                try {
                    if (claimedData) connection.releaseInterface(endpoints.data)
                } finally {
                    try {
                        if (claimedControl) connection.releaseInterface(endpoints.control)
                    } finally {
                        connection.close()
                    }
                }
            }
        } finally {
            key.fill(0)
        }
    }

    private class CdcLink(
        private val connection: UsbDeviceConnection, private val endpoints: Endpoints,
        private val context: BooleanSupplier
    ) : J1979UsbCapture.Link {
        private val pending = ByteArray(4096)
        private var cursor = 0
        private var limit = 0
        private var closed = false

        private fun now(): Long = System.nanoTime() / 1_000_000L

        private fun current() {
            if (closed || !context.asBoolean) throw IOException("USB session disconnected or revoked")
        }

        override fun read(data: ByteArray, offset: Int, size: Int, timeoutMs: Int): Int {
            require(offset >= 0 && size > 0 && offset <= data.size && size <= data.size - offset && timeoutMs > 0)
            current()
            val start = now()
            while (cursor == limit) {
                val elapsed = now() - start
                if (elapsed < 0 || elapsed >= timeoutMs) throw SocketTimeoutException("USB read deadline exceeded")
                current()
                val count = connection.bulkTransfer(endpoints.input, pending, pending.size,
                    minOf(100L, timeoutMs - elapsed).toInt())
                current()
                if (count <= 0) continue
                if (count > pending.size) throw IOException("Invalid USB transfer length")
                cursor = 0
                limit = count
            }
            val count = minOf(size, limit - cursor)
            pending.copyInto(data, offset, cursor, cursor + count)
            cursor += count
            return count
        }

        override fun write(data: ByteArray, timeoutMs: Int) {
            require(data.isNotEmpty() && timeoutMs > 0)
            current()
            if (connection.bulkTransfer(endpoints.output, data, data.size, minOf(timeoutMs, 1000)) != data.size)
                throw IOException("USB short write; no retry")
            current()
        }

        override fun checkNoBufferedInput() {
            current()
            if (cursor != limit) throw IOException("Unexpected buffered native USB bytes")
            // CDC has no EOF. This only detects immediately available trailing data.
            val count = connection.bulkTransfer(endpoints.input, pending, pending.size, 50)
            current()
            if (count > 0) throw IOException("Unexpected trailing native USB bytes")
        }

        override fun close() {
            closed = true
            pending.fill(0)
            cursor = 0
            limit = 0
        }
    }
}
