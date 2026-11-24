package com.camarodiag.gen5.data.obd

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.EOFException
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.Locale
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Direct Android ISO 13400 read session. No PC bridge, ELM or CAN ID assumption.
 * A socket is owned by one operation and is never retained after failure.
 */
object DoIpDirectRead {
    enum class Operation { VIN, DID, DTC }
    data class Target private constructor(val address: InetAddress, val tester: Int, val ecu: Int,
        val expectedVin: String, val tls: Boolean) {
        companion object {
            fun parse(host: String, tester: String, ecu: String, vin: String, tls: Boolean): Target {
                val raw = host.trim()
                val address = if (raw.contains(':')) {
                    require(raw.matches(Regex("[0-9A-Fa-f:.]+(%[0-9]+)?"))) { "Numeric IPv6 address required." }
                    InetAddress.getByName(raw)
                } else {
                    val parts = raw.split('.')
                    require(parts.size == 4 && parts.all { it.matches(Regex("[0-9]{1,3}")) &&
                        it.toInt() in 0..255 }) { "Numeric IPv4 address required." }
                    InetAddress.getByAddress(parts.map { it.toInt().toByte() }.toByteArray())
                }
                require(!address.isAnyLocalAddress && !address.isMulticastAddress &&
                    address.hostAddress != "255.255.255.255") { "An explicit unicast gateway is required." }
                fun logical(text: String): Int {
                    require(text.trim().matches(Regex("[0-9A-Fa-f]{4}")))
                    return text.trim().toInt(16)
                }
                val source = logical(tester)
                val destination = logical(ecu)
                require(source != destination)
                val expected = vin.trim().uppercase(Locale.ROOT)
                require(expected.isEmpty() || validVin(expected))
                return Target(address, source, destination, expected, tls)
            }
        }
    }
    data class Gateway(val vin: String?, val logicalAddress: Int, val version: Int)
    data class Trace(val direction: String, val payloadHex: String)
    data class Outcome(val gateway: Gateway, val ecu: Int, val vin: String?,
        val data: ByteArray?, val dtcs: UdsDtcReport?, val trace: List<Trace>)

    private const val MAX_PAYLOAD = 1024 * 1024
    private data class Frame(val version: Int, val type: Int, val data: ByteArray)
    private fun validVin(value: String) = value.matches(Regex("[A-HJ-NPR-Z0-9]{17}"))
    private fun Byte.u8() = toInt() and 255
    private fun u16(bytes: ByteArray, offset: Int) = (bytes[offset].u8() shl 8) or bytes[offset+1].u8()
    private fun addr(value: Int) = byteArrayOf((value shr 8).toByte(), value.toByte())
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02X".format(Locale.ROOT, it.u8()) }
    private fun encode(version: Int, type: Int, data: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + data.size).put(version.toByte()).put((version xor 255).toByte())
            .putShort(type.toShort()).putInt(data.size).put(data).array()
    private fun length(header: ByteArray): Int {
        require(header.size == 8 && (header[0].u8() xor header[1].u8()) == 255)
        require(header[0].u8() in setOf(2, 3, 4))
        return ByteBuffer.wrap(header, 4, 4).int.also { require(it in 0..MAX_PAYLOAD) }
    }
    private fun parseFrame(header: ByteArray, data: ByteArray): Frame {
        require(length(header) == data.size)
        return Frame(header[0].u8(), u16(header, 2), data)
    }
    private fun remaining(deadline: Long): Int {
        val nanos = deadline - System.nanoTime()
        if (nanos <= 0) throw SocketTimeoutException("DoIP deadline expired.")
        return ((nanos + 999_999) / 1_000_000).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
    }
    private fun deadline(ms: Int) = System.nanoTime() + ms * 1_000_000L

    /** Closing the actual socket on cancellation interrupts blocking Java network I/O. */
    private suspend fun <T : Any> io(timeoutMs: Long, close: () -> Unit, work: () -> T): T =
        withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.IO) {
                suspendCancellableCoroutine { cont ->
                    cont.invokeOnCancellation { runCatching(close) }
                    if (cont.isActive) {
                        try { cont.resume(work()) }
                        catch (error: Exception) { if (cont.isActive) cont.resumeWithException(error) }
                    }
                }
            }
        } ?: throw SocketTimeoutException("DoIP operation timed out.")

    private suspend fun discover(target: Target, timeoutMs: Int): Gateway {
        val socket = DatagramSocket()
        try {
            return io(timeoutMs.toLong(), socket::close) {
                socket.connect(target.address, 13400)
                val packet = encode(255, 0x0001, byteArrayOf())
                socket.send(DatagramPacket(packet, packet.size, target.address, 13400))
                val deadline = deadline(timeoutMs)
                val buffer = ByteArray(1024)
                var found: Gateway? = null
                while (found == null) {
                    socket.soTimeout = remaining(deadline)
                    val received = DatagramPacket(buffer, buffer.size)
                    socket.receive(received)
                    remaining(deadline)
                    if (received.address != target.address || received.port != 13400 || received.length < 8) continue
                    val frame = parseFrame(buffer.copyOfRange(0, 8), buffer.copyOfRange(8, received.length))
                    if (frame.type == 0) throw IOException("DoIP gateway rejected identification.")
                    if (frame.type != 0x0004) continue
                    require(frame.data.size in setOf(32, 33)) { "Invalid DoIP identity announcement." }
                    val raw = frame.data.copyOfRange(0, 17)
                    val vin = if (raw.all { it == 0.toByte() } || raw.all { it.u8() == 255 }) null
                        else raw.toString(Charsets.US_ASCII).also { require(validVin(it)) }
                    if (vin != null && target.expectedVin.isNotEmpty()) require(vin == target.expectedVin) {
                        "Discovered DoIP vehicle differs from the selected VIN."
                    }
                    found = Gateway(vin, u16(frame.data, 17), frame.version)
                }
                found
            }
        } finally { socket.close() }
    }

    private class Session(private val socket: Socket, private val gateway: Gateway,
        private val target: Target, private val trace: MutableList<Trace>) {
        private fun readExact(size: Int, deadline: Long): ByteArray {
            val output = ByteArray(size)
            var offset = 0
            val stream = socket.getInputStream()
            while (offset < size) {
                socket.soTimeout = remaining(deadline)
                val read = stream.read(output, offset, size-offset)
                if (read < 0) throw EOFException("DoIP socket closed.")
                offset += read
            }
            remaining(deadline)
            return output
        }
        private fun send(type: Int, body: ByteArray, deadline: Long) {
            remaining(deadline)
            socket.getOutputStream().apply { write(encode(gateway.version, type, body)); flush() }
            remaining(deadline)
        }
        private fun receive(deadline: Long): Frame {
            val header = readExact(8, deadline)
            val frame = parseFrame(header, readExact(length(header), deadline))
            require(frame.version == gateway.version) { "DoIP protocol version changed." }
            if (frame.type == 0) throw IOException("DoIP generic negative acknowledgement.")
            return frame
        }
        private fun alive(frame: Frame, deadline: Long): Boolean {
            if (frame.type != 0x0007) return false
            require(frame.data.isEmpty())
            send(0x0008, addr(target.tester), deadline)
            return true
        }
        fun activate(timeout: Int) {
            val end = deadline(timeout)
            send(0x0005, addr(target.tester) + byteArrayOf(0,0,0,0,0), end)
            while (true) {
                val frame = receive(end)
                if (alive(frame, end)) continue
                require(frame.type == 0x0006 && frame.data.size in setOf(9,13) &&
                    u16(frame.data,0) == target.tester && u16(frame.data,2) == gateway.logicalAddress)
                when (frame.data[4].u8()) {
                    0x10 -> return
                    0x11 -> continue
                    else -> throw IOException("DoIP routing activation denied.")
                }
            }
        }
        fun exchange(request: ByteArray, timeout: Int): ByteArray {
            val end = deadline(timeout)
            check(trace.size < 128) { "Too many DoIP diagnostic messages." }
            trace += Trace("TX", hex(request))
            send(0x8001, addr(target.tester) + addr(target.ecu) + request, end)
            while (true) {
                val frame = receive(end)
                if (alive(frame, end)) continue
                require(frame.type in setOf(0x8001, 0x8002, 0x8003) && frame.data.size >= 5 &&
                    u16(frame.data,0) == target.ecu && u16(frame.data,2) == target.tester) {
                    "Uncorrelated DoIP diagnostic response."
                }
                if (frame.type == 0x8002 || frame.type == 0x8003) {
                    val echo = frame.data.copyOfRange(5, frame.data.size)
                    require(echo.size <= request.size && request.copyOf(echo.size).contentEquals(echo))
                    if (frame.type == 0x8003 || frame.data[4].u8() != 0)
                        throw IOException("DoIP diagnostic acknowledgement rejected.")
                    continue
                }
                val reply = frame.data.copyOfRange(4, frame.data.size)
                check(trace.size < 128 && reply.size <= 65536) { "DoIP diagnostic trace limit exceeded." }
                trace += Trace("RX", hex(reply))
                if (reply[0].u8() == 0x7F) {
                    require(reply.size == 3 && reply[1] == request[0])
                    if (reply[2].u8() == 0x78) continue // same absolute transaction deadline
                    throw UdsNegativeResponseException(reply[1].u8(), reply[2].u8())
                }
                require(reply[0].u8() == request[0].u8() + 0x40)
                if (request[0].u8() == 0x22)
                    require(reply.size >= 4 && reply[1] == request[1] && reply[2] == request[2])
                else require(reply.size >= 3 && reply[1] == request[1])
                return reply
            }
        }
    }

    /** One explicitly addressed operation. Unknown vehicle VIN cannot authorize
     * manufacturer DID/DTC queries; an F190 identification can establish it.
     * A missing gateway VIN is checked with F190 on the same logical ECU.
     */
    suspend fun read(target: Target, operation: Operation, did: Int = 0xF190,
        mask: Int = 255, timeoutMs: Int = 5000): Outcome {
        require(timeoutMs in 100..30_000 && did in 0..65535 && mask in 0..255)
        require(operation == Operation.VIN || target.expectedVin.isNotEmpty())
        val gateway = discover(target, timeoutMs)
        require(target.tester != gateway.logicalAddress)
        val trace = mutableListOf<Trace>()
        val base = Socket()
        try {
            return io(timeoutMs * 4L, { base.close() }) {
                base.connect(InetSocketAddress(target.address, if (target.tls) 3496 else 13400), timeoutMs)
                base.soTimeout = timeoutMs
                val wire = if (target.tls) {
                    val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                    val secure = factory.createSocket(base, target.address.hostAddress, 3496, true) as SSLSocket
                    secure.sslParameters = secure.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                    secure.enabledProtocols = secure.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
                    secure.startHandshake()
                    secure
                } else base
                wire.use {
                    val session = Session(wire, gateway, target, trace)
                    session.activate(timeoutMs)
                    fun readVin(): String {
                        val reply = session.exchange(byteArrayOf(0x22,0xF1.toByte(),0x90.toByte()), timeoutMs)
                        require(reply.size == 20)
                        val vin = reply.copyOfRange(3,20).toString(Charsets.US_ASCII)
                        require(validVin(vin) && (gateway.vin == null || gateway.vin == vin) &&
                            (target.expectedVin.isEmpty() || target.expectedVin == vin)) {
                            "ECU VIN differs from the selected vehicle or gateway."
                        }
                        return vin
                    }
                    var vin: String
                    if (operation == Operation.VIN || (operation == Operation.DID && did == 0xF190)) {
                        vin = readVin()
                        Outcome(gateway, target.ecu, vin, vin.toByteArray(Charsets.US_ASCII), null, trace.toList())
                    } else {
                        // The gateway's announcement is not proof of the selected ECU identity.
                        vin = readVin()
                        require(vin == target.expectedVin)
                        if (operation == Operation.DID) {
                            val reply = session.exchange(byteArrayOf(0x22, (did shr 8).toByte(), did.toByte()), timeoutMs)
                            Outcome(gateway, target.ecu, vin, reply.copyOfRange(3, reply.size), null, trace.toList())
                        } else {
                            val reply = session.exchange(byteArrayOf(0x19,0x02,mask.toByte()), timeoutMs)
                            val report = UdsDtcDecoder.decodeStatusMaskReport(reply.copyOfRange(2, reply.size), mask)
                            Outcome(gateway, target.ecu, vin, null, report, trace.toList())
                        }
                    }
                }
            }
        } finally { base.close() }
    }
}
