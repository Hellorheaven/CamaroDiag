package com.camarodiag.connectors

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.IOException

/** Isolated prototype CDC discovery on the selected USB device. */
object UsbDiscovery {
    private data class Endpoints(
        val control: UsbInterface, val data: UsbInterface, val input: UsbEndpoint, val output: UsbEndpoint
    )

    private fun endpoints(device: UsbDevice, controlId: Int, dataId: Int): Endpoints {
        if (controlId == dataId) throw IOException("CDC interface not supported")
        val interfaces = (0 until device.interfaceCount).map(device::getInterface)
        val control = interfaces.filter { it.id == controlId && it.interfaceClass == UsbConstants.USB_CLASS_COMM }
            .singleOrNull() ?: throw IOException("Missing or ambiguous CDC control")
        val data = interfaces.filter { it.id == dataId && it.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA }
            .singleOrNull() ?: throw IOException("Missing or ambiguous CDC data")
        val bulk = (0 until data.endpointCount).map(data::getEndpoint)
            .filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
        val input = bulk.filter { it.direction == UsbConstants.USB_DIR_IN }.singleOrNull()
            ?: throw IOException("Missing or ambiguous bulk input")
        val output = bulk.filter { it.direction == UsbConstants.USB_DIR_OUT }.singleOrNull()
            ?: throw IOException("Missing or ambiguous bulk output")
        if (input.maxPacketSize !in 1..1024 || output.maxPacketSize !in 1..1024)
            throw IOException("Invalid CDC packet size")
        return Endpoints(control, data, input, output)
    }

    @JvmStatic @Throws(IOException::class)
    fun verify(manager: UsbManager, selected: UsbDevice, controlId: Int, dataId: Int): Array<String> {
        fun current() {
            if (!manager.hasPermission(selected) || selected != manager.deviceList[selected.deviceName])
                throw IOException("Selected USB device disconnected or permission revoked")
        }
        current()
        val ep = endpoints(selected, controlId, dataId)
        val conn = manager.openDevice(selected) ?: throw IOException("Cannot open USB device")
        var claimedControl = false
        var claimedData = false
        try {
            current()
            claimedControl = conn.claimInterface(ep.control, false)
            if (!claimedControl) throw IOException("Cannot claim control interface")
            current()
            claimedData = conn.claimInterface(ep.data, false)
            if (!claimedData) throw IOException("Cannot claim data interface")
            current()
            val line = byteArrayOf(0, 0xc2.toByte(), 1, 0, 0, 0, 8)
            if (conn.controlTransfer(0x21, 0x20, 0, controlId, line, 7, 1000) != 7)
                throw IOException("CDC line coding rejected")
            current()
            if (conn.controlTransfer(0x21, 0x22, 1, controlId, null, 0, 1000) != 0)
                throw IOException("CDC control state rejected")
            current()
            return BinaryDiscovery(DiscoveryLink(conn, ep, ::current)).use { it.verify() }
        } finally {
            try {
                if (claimedData) conn.releaseInterface(ep.data)
            } finally {
                try {
                    if (claimedControl) conn.releaseInterface(ep.control)
                } finally {
                    conn.close()
                }
            }
        }
    }

    private class DiscoveryLink(
        private val conn: UsbDeviceConnection, private val ep: Endpoints, private val checkContext: () -> Unit
    ) : BinaryDiscovery.Link {
        private val pending = ByteArray(4096)
        private var cursor = 0
        private var limit = 0
        private var closed = false

        private fun current() {
            if (closed) throw IOException("Discovery channel closed")
            checkContext()
        }
        override fun read(buffer: ByteArray, offset: Int, count: Int, timeoutMs: Int): Int {
            require(offset >= 0 && count > 0 && offset <= buffer.size && count <= buffer.size - offset && timeoutMs > 0)
            current()
            if (cursor == limit) {
                limit = conn.bulkTransfer(ep.input, pending, pending.size, minOf(timeoutMs, 1500))
                current()
                cursor = 0
                if (limit !in 1..pending.size) throw IOException("USB disconnected, invalid transfer or timeout")
            }
            val n = minOf(count, limit - cursor)
            pending.copyInto(buffer, offset, cursor, cursor + n)
            cursor += n
            return n
        }
        override fun write(buffer: ByteArray, timeoutMs: Int) {
            require(buffer.isNotEmpty() && timeoutMs > 0)
            checkNoBufferedInput()
            if (conn.bulkTransfer(ep.output, buffer, buffer.size, minOf(timeoutMs, 1500)) != buffer.size)
                throw IOException("USB short write or timeout")
            current()
        }
        override fun checkNoBufferedInput() {
            current()
            if (cursor != limit) throw IOException("Unexpected buffered USB data")
        }
        override fun close() {
            closed = true
            pending.fill(0)
            cursor = 0
            limit = 0
        }
    }
}
