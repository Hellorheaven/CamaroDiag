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
    enum class Operation { VIN, DID, DTC, MONITOR, BACKUP }
    data class Target private constructor(val address: InetAddress, val tester: Int, val ecu: Int,
        val expectedVin: String, val tls: Boolean) {
        companion object {
            fun parse(host: String, tester: String, ecu: String, vin: String, tls: Boolean): Target {
                val address = parseHost(host)
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
    private fun parseHost(host: String): InetAddress {
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
        return address
    }
    enum class GatewayOperation { DISCOVER, STATUS, POWER }
    data class Gateway(val vin: String?, val logicalAddress: Int, val version: Int)
    data class GatewayStatus(val nodeType: Int, val maxSockets: Int, val currentSockets: Int, val maxDataSize: Long?)
    data class GatewayResult(val gateway: Gateway, val status: GatewayStatus? = null, val power: Int? = null)
    data class Trace(val direction: String, val payloadHex: String, val observedAtUtcMillis: Long = System.currentTimeMillis())
    data class Outcome(val gateway: Gateway, val ecu: Int, val vin: String?,
        val data: ByteArray?, val dtcs: UdsDtcReport?, val trace: List<Trace>,
        val samples: List<Sample> = emptyList(), val capture: DoIpMemoryBackup.Capture? = null)
    data class Sample(val index: Int, val elapsedMs: Long, val data: ByteArray)

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

    private suspend fun discover(address: InetAddress, expectedVin: String, timeoutMs: Int): Gateway {
        val socket = DatagramSocket()
        try {
            return io(timeoutMs.toLong(), socket::close) {
                socket.connect(address, 13400)
                val packet = encode(255, 0x0001, byteArrayOf())
                socket.send(DatagramPacket(packet, packet.size, address, 13400))
                val deadline = deadline(timeoutMs)
                val buffer = ByteArray(1024)
                var found: Gateway? = null
                while (found == null) {
                    socket.soTimeout = remaining(deadline)
                    val received = DatagramPacket(buffer, buffer.size)
                    socket.receive(received)
                    remaining(deadline)
                    if (received.address != address || received.port != 13400 || received.length < 8) continue
                    val frame = parseFrame(buffer.copyOfRange(0, 8), buffer.copyOfRange(8, received.length))
                    if (frame.type == 0) throw IOException("DoIP gateway rejected identification.")
                    if (frame.type != 0x0004) continue
                    require(frame.data.size in setOf(32, 33)) { "Invalid DoIP identity announcement." }
                    val raw = frame.data.copyOfRange(0, 17)
                    val vin = if (raw.all { it == 0.toByte() } || raw.all { it.u8() == 255 }) null
                        else raw.toString(Charsets.US_ASCII).also { require(validVin(it)) }
                    if (vin != null && expectedVin.isNotEmpty()) require(vin == expectedVin) {
                        "Discovered DoIP vehicle differs from the selected VIN."
                    }
                    found = Gateway(vin, u16(frame.data, 17), frame.version)
                }
                found
            }
        } finally { socket.close() }
    }

    /** Discovery and control messages never activate a diagnostic TCP route. */
    suspend fun inspectGateway(host: String, vin: String, operation: GatewayOperation,
        timeoutMs: Int = 5000): GatewayResult {
        require(timeoutMs in 100..30_000)
        val address = parseHost(host)
        val expected = vin.trim().uppercase(Locale.ROOT)
        require(expected.isEmpty() || validVin(expected))
        val gateway = discover(address, expected, timeoutMs)
        if (operation == GatewayOperation.DISCOVER) return GatewayResult(gateway)
        val socket = DatagramSocket()
        try {
            return io(timeoutMs.toLong(), socket::close) {
                socket.connect(address, 13400)
                val power = operation == GatewayOperation.POWER
                val request = encode(gateway.version, if (power) 0x4003 else 0x4001, byteArrayOf())
                socket.send(DatagramPacket(request, request.size, address, 13400))
                val end = deadline(timeoutMs)
                val buffer = ByteArray(1024)
                var result: GatewayResult? = null
                while (result == null) {
                    socket.soTimeout = remaining(end)
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    remaining(end)
                    if (packet.address != address || packet.port != 13400 || packet.length < 8) continue
                    val frame = parseFrame(buffer.copyOfRange(0, 8), buffer.copyOfRange(8, packet.length))
                    require(frame.version == gateway.version)
                    if (frame.type == 0) throw IOException("DoIP gateway rejected control query.")
                    if (frame.type != if (power) 0x4004 else 0x4002) continue
                    if (power) {
                        require(frame.data.size == 1 && frame.data[0].u8() in 0..2)
                        result = GatewayResult(gateway, power = frame.data[0].u8())
                    } else {
                        require(frame.data.size in setOf(3, 7) && frame.data[0].u8() in 0..1 &&
                            frame.data[2].u8() <= frame.data[1].u8())
                        val size = if (frame.data.size == 7)
                            ByteBuffer.wrap(frame.data, 3, 4).int.toLong() and 0xFFFFFFFFL else null
                        result = GatewayResult(gateway, GatewayStatus(frame.data[0].u8(),
                            frame.data[1].u8(), frame.data[2].u8(), size))
                    }
                }
                result
            }
        } finally { socket.close() }
    }

    private class Session(private val socket: Socket, private val gateway: Gateway,
        private val target: Target, private val trace: MutableList<Trace>,
        private val rollingTrace: Boolean = false, private val onTrace: (Trace) -> Unit = {}) {
        private fun record(direction: String, payload: ByteArray) {
            require(payload.size <= 65536)
            if (rollingTrace && trace.size >= 128) trace.removeAt(0)
            check(trace.size < 128) { "DoIP diagnostic trace limit exceeded." }
            val event = Trace(direction, hex(payload))
            trace += event
            onTrace(event)
        }
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
        /** Maintain routing while idle. A timeout is normal only before the
         * first header byte; partial or unsolicited diagnostic data is fatal. */
        fun waitBetweenSamples(intervalMs: Int, frameTimeoutMs: Int) {
            val end = deadline(intervalMs)
            while (System.nanoTime() < end) {
                socket.soTimeout = remaining(end)
                val first = try { socket.getInputStream().read() }
                    catch (_: SocketTimeoutException) { return }
                if (first < 0) throw EOFException("DoIP socket closed between samples.")
                val frameEnd = deadline(frameTimeoutMs)
                val header = byteArrayOf(first.toByte()) + readExact(7, frameEnd)
                val frame = parseFrame(header, readExact(length(header), frameEnd))
                require(frame.version == gateway.version && alive(frame, frameEnd)) {
                    "Unsolicited DoIP message between samples."
                }
            }
        }
        fun exchange(request: ByteArray, timeout: Int): ByteArray {
            val end = deadline(timeout)
            record("TX", request)
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
                record("RX", reply)
                if (reply[0].u8() == 0x7F) {
                    require(reply.size == 3 && reply[1] == request[0])
                    if (reply[2].u8() == 0x78) continue // same absolute transaction deadline
                    throw UdsNegativeResponseException(reply[1].u8(), reply[2].u8())
                }
                require(reply[0].u8() == request[0].u8() + 0x40)
                if (request[0].u8() == 0x22)
                    require(reply.size >= 4 && reply[1] == request[1] && reply[2] == request[2])
                else if (request[0].u8() == 0x19) require(reply.size >= 3 && reply[1] == request[1])
                else require(request[0].u8() == 0x23 && reply.size >= 2)
                return reply
            }
        }
    }

    /** One explicitly addressed operation. Unknown vehicle VIN cannot authorize
     * manufacturer DID/DTC queries; an F190 identification can establish it.
     * A missing gateway VIN is checked with F190 on the same logical ECU.
     */
    suspend fun read(target: Target, operation: Operation, did: Int = 0xF190,
        mask: Int = 255, timeoutMs: Int = 5000, sampleCount: Int = 10,
        intervalMs: Int = 1000, backupScope: DoIpMemoryBackup.Scope? = null,
        onBackupProgress: (Int, Int) -> Unit = { _, _ -> },
        onTrace: (Trace) -> Unit = {}, onSample: (Sample) -> Unit = {}): Outcome {
        require(timeoutMs in 100..30_000 && did in 0..65535 && mask in 0..255)
        require(operation == Operation.VIN || target.expectedVin.isNotEmpty())
        require(sampleCount in 1..30 && intervalMs in 250..5000)
        require((operation == Operation.BACKUP) == (backupScope != null))
        if (backupScope != null) {
            backupScope.validate()
            require(backupScope.vin == target.expectedVin && backupScope.tester == target.tester && backupScope.ecu == target.ecu)
        }
        val budget = if (operation == Operation.BACKUP) 600_000L
            else if (operation == Operation.MONITOR)
            timeoutMs * (sampleCount + 5L) + intervalMs * (sampleCount - 1L) else timeoutMs * 5L
        val gateway = discover(target.address, target.expectedVin, timeoutMs)
        require(target.tester != gateway.logicalAddress)
        if (backupScope != null) require(gateway.logicalAddress == backupScope.gateway)
        val trace = mutableListOf<Trace>()
        val base = Socket()
        try {
            return io(budget, { base.close() }) {
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
                    val session = Session(wire, gateway, target, trace, rollingTrace = operation == Operation.BACKUP,
                        onTrace = onTrace)
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
                    val vin: String
                    if (operation == Operation.VIN || (operation == Operation.DID && did == 0xF190)) {
                        vin = readVin()
                        Outcome(gateway, target.ecu, vin, vin.toByteArray(Charsets.US_ASCII), null, trace.toList())
                    } else {
                        // The gateway's announcement is not proof of the selected ECU identity.
                        vin = readVin()
                        require(vin == target.expectedVin)
                        if (operation == Operation.BACKUP) {
                            val scope = checkNotNull(backupScope)
                            fun identity() {
                                require(readVin() == scope.vin)
                                val response = session.exchange(byteArrayOf(0x22, (scope.identityDid shr 8).toByte(), scope.identityDid.toByte()), timeoutMs)
                                require(hex(response.copyOfRange(3, response.size)) == scope.identityHex) { "ECU identity differs from memory scope." }
                            }
                            fun pass(index: Int): ByteArray {
                                identity()
                                val data = ByteArray(scope.length)
                                var offset = 0
                                var blocks = 0
                                while (offset < data.size) {
                                    if (blocks > 0 && blocks % 32 == 0) identity()
                                    val count = minOf(scope.blockSize, data.size - offset)
                                    val address = scope.start + offset
                                    val request = byteArrayOf(0x23, if (count > 255) 0x24 else 0x14,
                                        (address shr 24).toByte(), (address shr 16).toByte(),
                                        (address shr 8).toByte(), address.toByte()) +
                                        if (count > 255) byteArrayOf((count shr 8).toByte(), count.toByte()) else byteArrayOf(count.toByte())
                                    val reply = session.exchange(request, timeoutMs)
                                    require(reply.size == count + 1) { "Incomplete scoped memory response." }
                                    reply.copyInto(data, offset, 1)
                                    offset += count
                                    blocks++
                                    if (blocks % 32 == 0 || offset == data.size) onBackupProgress(index, offset)
                                }
                                return data
                            }
                            val first = pass(1)
                            val second = pass(2)
                            identity()
                            val capture = DoIpMemoryBackup.createCapture(scope, first, second)
                            Outcome(gateway, target.ecu, vin, null, null, trace.toList(), capture = capture)
                        } else if (operation == Operation.MONITOR) {
                            val samples = mutableListOf<Sample>()
                            val start = System.nanoTime()
                            for (index in 1..sampleCount) {
                                val reply = session.exchange(byteArrayOf(0x22, (did shr 8).toByte(), did.toByte()), timeoutMs)
                                require(reply.size <= 4099) { "DID monitoring sample exceeds limit." }
                                val sample = Sample(index, (System.nanoTime() - start) / 1_000_000,
                                    reply.copyOfRange(3, reply.size))
                                samples += sample
                                onSample(sample.copy(data = sample.data.copyOf()))
                                if (index < sampleCount) session.waitBetweenSamples(intervalMs, timeoutMs)
                            }
                            require(readVin() == vin)
                            Outcome(gateway, target.ecu, vin, null, null, trace.toList(), samples.toList())
                        } else if (operation == Operation.DID) {
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
