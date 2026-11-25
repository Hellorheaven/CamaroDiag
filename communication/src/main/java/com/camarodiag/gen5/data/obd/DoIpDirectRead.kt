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
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
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
    enum class Operation { VIN, DID, DTC, DTC_COUNT, DTC_SNAPSHOT, DTC_EXTENDED, MONITOR, BACKUP, EXCHANGE, OBD_IDENTIFY, OBD_DID, OBD_MONITOR, OBD_WWH_MASK, OBD_WWH_PERMANENT, OBD_WWH_READINESS, MULTI_DID, OBD_MULTI_DID, SCALING_DID, OBD_DTC, OBD_DTC_COUNT, OBD_DTC_SNAPSHOT, OBD_DTC_EXTENDED, SUPPORTED_DTCS, SNAPSHOT_INDEX, MULTI_MONITOR, OBD_MULTI_MONITOR }
    /** Immutable optional parameters. Defaults retain the original global deadline. */
    data class SessionOptions(val timeoutMs: Int = 5000, val p2Ms: Int = timeoutMs,
        val p2StarMs: Int = timeoutMs, val routingType: Int = 0, val routingOemHex: String = "") {
        init {
            require(timeoutMs in 100..30_000 && p2Ms in 1..timeoutMs && p2StarMs in p2Ms..timeoutMs)
            require(routingType in 0..255)
            require(routingOemHex.isEmpty() || routingOemHex.matches(Regex("[0-9A-F]{8}")))
        }
        companion object {
            fun parse(timeout: String, p2: String, p2Star: String, routing: String, oem: String): SessionOptions {
                require(routing.matches(Regex("[0-9A-Fa-f]{2}")))
                return SessionOptions(timeout.toInt(), p2.toInt(), p2Star.toInt(),
                    routing.toInt(16), oem.uppercase(Locale.ROOT))
            }
        }
        internal fun activationBody(tester: Int): ByteArray =
            addr(tester) + byteArrayOf(routingType.toByte(), 0, 0, 0, 0) +
                if (routingOemHex.isEmpty()) byteArrayOf() else parseCommand(routingOemHex)
    }

    internal fun responseDeadline(overall: Long, now: Long, stageMs: Int): Long =
        minOf(overall, now + stageMs * 1_000_000L)

    @ConsistentCopyVisibility
    data class Target private constructor(val address: InetAddress, val tester: Int, val ecu: Int,
        val expectedVin: String, val tls: Boolean, val expectedGateway: Int? = null,
        val expectedIdentity: Gateway? = null) {
        companion object {
            fun parse(host: String, tester: String, ecu: String, vin: String, tls: Boolean,
                expectedGateway: Int? = null, expectedIdentity: Gateway? = null): Target {
                val address = parseHost(host)
                val source = logicalAddress(tester)
                val destination = logicalAddress(ecu)
                require(source != destination)
                require(expectedGateway == null || (expectedGateway in 0..65535 && source != expectedGateway))
                val expected = vin.trim().uppercase(Locale.ROOT)
                require(expected.isEmpty() || validVin(expected))
                require(expectedIdentity == null ||
                    (expectedIdentity.logicalAddress != source &&
                        (expectedGateway == null || expectedIdentity.logicalAddress == expectedGateway) &&
                        (expectedIdentity.vin == null || expectedIdentity.vin == expected)))
                return Target(address, source, destination, expected, tls,
                    expectedGateway ?: expectedIdentity?.logicalAddress, expectedIdentity)
            }
        }
    }

    /** SAE J1979-2 standardized DID range; availability/scaling is ECU-specific. */
    fun isObdDid(did: Int): Boolean = did in 0xF400..0xF8FF

    internal fun decodeObdProtocol(reply: ByteArray): Int {
        require(reply.size == 4 && reply[0].u8() == 0x62 && reply[1].u8() == 0xF8 && reply[2].u8() == 0x10)
        return reply[3].u8()
    }

    /** The common selector only exposes an OBD service route after the selected ECU
     * returned both the legislated VIN and the OBDonUDS identification value.
     * A DID on that route can still be unsupported; its reply remains authoritative. */
    internal fun selectObdReadRoute(target: Target, ecuVin: String, protocolValue: Int,
        requested: DiagnosticRouteSelector.Operation = DiagnosticRouteSelector.Operation.MONITOR): DiagnosticRouteSelector.Selection {
        val selector = DiagnosticRouteSelector
        val protocol = DiagnosticRouteSelector.Protocol(DiagnosticRouteSelector.Link.ETHERNET,
            DiagnosticRouteSelector.Framing.DOIP, DiagnosticRouteSelector.Service.OBD)
        val sameVin = target.expectedVin.isNotEmpty() && ecuVin == target.expectedVin
        val verified = sameVin && protocolValue == 1
        return selector.select(listOf(DiagnosticRouteSelector.Capability(protocol,
            if (verified) DiagnosticRouteSelector.Evidence.VERIFIED else DiagnosticRouteSelector.Evidence.UNKNOWN,
            true, setOf(DiagnosticRouteSelector.Operation.IDENTIFY, DiagnosticRouteSelector.Operation.MONITOR,
                DiagnosticRouteSelector.Operation.DTC))),
            DiagnosticRouteSelector.Target(target.expectedVin.ifEmpty { "unknown" },
                "%04X".format(target.ecu),
                if (sameVin) setOf(DiagnosticRouteSelector.Link.ETHERNET) else emptySet(),
                if (verified) setOf(protocol) else emptySet(), sameVin),
            requested)
    }

    fun parseCommand(text: String): ByteArray {
        val compact = text.filterNot(Char::isWhitespace)
        require(compact.length in 2..8182 && compact.length % 2 == 0 &&
            compact.all { it in "0123456789ABCDEFabcdef" })
        return ByteArray(compact.length / 2) { index ->
            compact.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    fun parseCommands(text: String): List<ByteArray> {
        val parts = text.split(';')
        require(parts.size in 1..16)
        return parts.map(::parseCommand).also { require(it.sumOf { it.size } <= 4091) }
    }

    /** Correlation checks do not restrict manufacturer-specific service IDs.
     * A positive service echo alone is not proof that a write was persisted. */
    internal fun requirePositiveResponse(request: ByteArray, reply: ByteArray) {
        require(request.isNotEmpty() && reply.isNotEmpty() &&
            reply[0].u8() == request[0].u8() + 0x40)
        fun echo(length: Int) {
            require(request.size >= 1 + length && reply.size >= 1 + length &&
                request.copyOfRange(1, 1 + length).contentEquals(reply.copyOfRange(1, 1 + length)))
        }
        when (request[0].u8()) {
            0x22 -> { echo(2); require(reply.size >= 4) }
            0x24, 0x2E, 0x2F -> echo(2)
            0x19 -> {
                require(request.size >= 2 && reply.size >= 2 && reply[1].u8() == (request[1].u8() and 0x7F))
                val subfunction = request[1].u8() and 0x7F
                if (subfunction == 0x03) {
                    require(request.size == 2 && (reply.size - 2) % 4 == 0)
                } else {
                    require(reply.size >= 3)
                    if (subfunction == 0x0A) require(request.size == 2 && (reply.size - 3) % 4 == 0)
                }
                if (subfunction in setOf(0x04, 0x06)) {
                    require(request.size == 6 && reply.size >= 6 &&
                        request.copyOfRange(2, 5).contentEquals(reply.copyOfRange(2, 5)))
                    val selected = request[5].u8()
                    if (reply.size >= 7 && selected != 0xFF && (subfunction != 0x06 || selected != 0xFE))
                        require(reply[6].u8() == selected) { "UDS DTC record mismatch." }
                }
                if (subfunction in setOf(0x42, 0x55, 0x56)) {
                    require(request.size >= 3 && request[2] == reply[2]) { "WWH group mismatch." }
                    if (subfunction == 0x56)
                        require(request.size == 4 && reply.size >= 6 && reply[5] == request[3]) {
                            "WWH readiness group mismatch."
                        }
                }
            }
            0x10, 0x11, 0x27, 0x28, 0x3E, 0x85 -> {
                require(request.size >= 2 && reply.size >= 2 &&
                    reply[1].u8() == (request[1].u8() and 0x7F))
            }
            0x31 -> { require(request.size >= 4 && reply.size >= 4 &&
                reply[1].u8() == (request[1].u8() and 0x7F) &&
                reply[2] == request[2] && reply[3] == request[3]) }
            0x36 -> echo(1)
            0x23 -> require(reply.size >= 2)
        }
    }

    private fun logicalAddress(text: String): Int {
        require(text.trim().matches(Regex("[0-9A-Fa-f]{4}")))
        return text.trim().toInt(16)
    }

    /** Resolve only the network endpoint. Logical addresses remain supplied by
     * the selected ECU/profile, never guessed from the VIN or gateway. */
    suspend fun resolveTarget(host: String, tester: String, ecu: String, vin: String,
        tls: Boolean, expectedGateway: Int? = null,
        onControlTrace: (ControlTrace) -> Unit = {}): Target =
        resolveTargetWithDiscovery(host, tester, ecu, vin, tls, expectedGateway) {
            discoverSelectedVehicle(it, onControlTrace = onControlTrace)
        }

    internal suspend fun resolveTargetWithDiscovery(host: String, tester: String, ecu: String,
        vin: String, tls: Boolean, expectedGateway: Int?,
        discoverVehicle: suspend (String) -> GatewayResult): Target {
        if (host.isNotBlank()) return Target.parse(host, tester, ecu, vin, tls, expectedGateway)
        val expected = vin.trim().uppercase(Locale.ROOT)
        val source = logicalAddress(tester)
        require(validVin(expected) && source != logicalAddress(ecu))
        require(expectedGateway == null || (expectedGateway in 0..65535 && source != expectedGateway))
        val found = discoverVehicle(expected)
        require(found.gateway.vin == expected)
        require(expectedGateway == null || found.gateway.logicalAddress == expectedGateway) {
            "Discovered gateway does not match the selected ECU profile."
        }
        return Target.parse(requireNotNull(found.host), tester, ecu, expected, tls,
            found.gateway.logicalAddress, found.gateway)
    }
    internal fun canonicalHost(host: String): String = requireNotNull(parseHost(host).hostAddress)

    fun sameHost(first: String, second: String): Boolean = canonicalHost(first) == canonicalHost(second)

    internal fun tlsServerName(address: InetAddress): String = requireNotNull(address.hostAddress).substringBefore('%')

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
        require(address !is Inet6Address || !address.isLinkLocalAddress || address.scopeId > 0) {
            "Link-local IPv6 requires an explicit interface scope."
        }
        return address
    }
    enum class GatewayOperation { DISCOVER, STATUS, POWER, DISCOVER_IPV6, LISTEN_IPV6, LISTEN_IPV4 }
    data class Gateway(val vin: String?, val logicalAddress: Int, val version: Int,
        val eid: String = "", val gid: String = "", val furtherAction: Int = 0,
        val syncStatus: Int? = null) {
        /** Correlation, not cryptographic authentication of the gateway. */
        fun matchesIdentity(other: Gateway): Boolean =
            vin == other.vin && logicalAddress == other.logicalAddress && version == other.version &&
                eid == other.eid && gid == other.gid
    }

    internal fun requireGatewayIdentity(target: Target, gateway: Gateway) {
        require(target.tester != gateway.logicalAddress)
        require(target.expectedGateway == null || target.expectedGateway == gateway.logicalAddress) {
            "Gateway logical address changed after route selection."
        }
        require(target.expectedIdentity?.matchesIdentity(gateway) != false) {
            "Gateway EID/GID, version or VIN changed after route selection."
        }
    }
    data class GatewayStatus(val nodeType: Int, val maxSockets: Int, val currentSockets: Int, val maxDataSize: Long?)
    data class GatewayResult(val gateway: Gateway, val status: GatewayStatus? = null,
        val power: Int? = null, val host: String? = null)
    data class Trace(val direction: String, val payloadHex: String, val observedAtUtcMillis: Long = System.currentTimeMillis())
    /** Complete DoIP control PDU (header + body), separate from UDS payload traces. */
    data class ControlTrace(val direction: String, val transport: String, val host: String,
        val payloadHex: String, val observedAtUtcMillis: Long = System.currentTimeMillis())
    data class Outcome(val gateway: Gateway, val ecu: Int, val vin: String?,
        val data: ByteArray?, val dtcs: UdsDtcReport?, val trace: List<Trace>,
        val samples: List<Sample> = emptyList(), val capture: DoIpMemoryBackup.Capture? = null,
        val dtcCount: UdsDtcCountReport? = null, val dtcDetail: UdsDtcDetail? = null,
        val commandReplies: List<ByteArray> = emptyList(), val obdProtocolValue: Int? = null,
        val wwhReport: DoIpWwhDtc.Report? = null,
        val multiValues: List<DoIpMultiDid.Value> = emptyList(),
        val snapshotIndex: List<UdsSnapshotReference>? = null)
    data class Sample(val index: Int, val elapsedMs: Long, val data: ByteArray,
        val values: List<DoIpMultiDid.Value> = emptyList())

    private const val MAX_PAYLOAD = 1024 * 1024
    private const val DOIP_UDP_PORT = 13400
    private data class Frame(val version: Int, val type: Int, val data: ByteArray)
    private fun validVin(value: String) = value.matches(Regex("[A-HJ-NPR-Z0-9]{17}"))
    private fun Byte.u8() = toInt() and 255
    private fun u16(bytes: ByteArray, offset: Int) = (bytes[offset].u8() shl 8) or bytes[offset+1].u8()
    private fun addr(value: Int) = byteArrayOf((value shr 8).toByte(), value.toByte())
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02X".format(Locale.ROOT, it.u8()) }
    private fun encode(version: Int, type: Int, data: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + data.size).put(version.toByte()).put((version xor 255).toByte())
            .putShort(type.toShort()).putInt(data.size).put(data).array()
    private fun controlTrace(direction: String, transport: String, host: InetAddress,
        bytes: ByteArray, emit: (ControlTrace) -> Unit) {
        require(bytes.size <= 65544) { "DoIP control PDU exceeds trace limit." }
        emit(ControlTrace(direction, transport, host.hostAddress.orEmpty(), hex(bytes)))
    }
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

    private suspend fun discover(address: InetAddress, expectedVin: String, timeoutMs: Int,
        onControlTrace: (ControlTrace) -> Unit, port: Int = DOIP_UDP_PORT, eid: String? = null): Gateway {
        require(eid == null || eid.matches(Regex("[0-9A-Fa-f]{12}")))
        val requestedEid = eid?.uppercase(Locale.ROOT)
        val socket = DatagramSocket()
        try {
            return io(timeoutMs.toLong(), socket::close) {
                socket.connect(address, port)
                val packet = if (requestedEid == null) encode(255, 0x0001, byteArrayOf())
                    else encode(255, 0x0002, parseCommand(requestedEid))
                socket.send(DatagramPacket(packet, packet.size, address, port))
                controlTrace("TX", "UDP", address, packet, onControlTrace)
                val deadline = deadline(timeoutMs)
                val buffer = ByteArray(1024)
                var found: Gateway? = null
                while (found == null) {
                    socket.soTimeout = remaining(deadline)
                    val received = DatagramPacket(buffer, buffer.size)
                    socket.receive(received)
                    remaining(deadline)
                    if (received.address != address || received.port != port || received.length < 8) continue
                    controlTrace("RX", "UDP", address, buffer.copyOf(received.length), onControlTrace)
                    val frame = parseFrame(buffer.copyOfRange(0, 8), buffer.copyOfRange(8, received.length))
                    if (frame.type == 0) throw IOException("DoIP gateway rejected identification.")
                    if (frame.type != 0x0004) continue
                    require(frame.data.size in setOf(32, 33)) { "Invalid DoIP identity announcement." }
                    val observedEid = hex(frame.data.copyOfRange(19, 25))
                    if (requestedEid != null && requestedEid != observedEid) continue
                    val raw = frame.data.copyOfRange(0, 17)
                    val vin = if (raw.all { it == 0.toByte() } || raw.all { it.u8() == 255 }) null
                        else raw.toString(Charsets.US_ASCII).also { require(validVin(it)) }
                    if (vin != null && expectedVin.isNotEmpty()) require(vin == expectedVin) {
                        "Discovered DoIP vehicle differs from the selected VIN."
                    }
                    found = Gateway(vin, u16(frame.data, 17), frame.version,
                        observedEid, hex(frame.data.copyOfRange(25, 31)), frame.data[31].u8(),
                        frame.data.getOrNull(32)?.u8())
                }
                found
            }
        } finally { socket.close() }
    }

    /** An explicit VIN-directed IPv4 discovery, never a diagnostic request.
     * Collect for the entire observation window: a first answer alone is not
     * sufficient to choose a gateway when several entities claim the VIN. */
    suspend fun discoverSelectedVehicle(vin: String, timeoutMs: Int = 3000,
        onControlTrace: (ControlTrace) -> Unit = {}): GatewayResult =
        discoverSelectedVehicleAt(vin, timeoutMs, InetAddress.getByName("255.255.255.255"),
            DOIP_UDP_PORT, onControlTrace)

    internal suspend fun discoverSelectedVehicleAt(vin: String, timeoutMs: Int,
        destination: InetAddress, port: Int, onControlTrace: (ControlTrace) -> Unit): GatewayResult {
        val expected = vin.trim().uppercase(Locale.ROOT)
        require(validVin(expected) && timeoutMs in 100..30_000 && port in 1..65535)
        require(!destination.isAnyLocalAddress && !destination.isMulticastAddress)
        val socket = DatagramSocket()
        try {
            return io(timeoutMs.toLong() + 1000, socket::close) {
                socket.broadcast = true
                val request = encode(255, 0x0003, expected.toByteArray(Charsets.US_ASCII))
                socket.send(DatagramPacket(request, request.size, destination, port))
                controlTrace("TX", "UDP", destination, request, onControlTrace)
                collectSelectedGateway(socket, expected, timeoutMs, port, null, onControlTrace)
            }
        } finally { socket.close() }
    }

    internal fun scopedDiscoverySource(address: InetAddress, interfaceIndex: Int?): String? {
        if (address.isAnyLocalAddress || address.isMulticastAddress) return null
        if (interfaceIndex == null) {
            return if (address !is Inet6Address) address.hostAddress else null
        }
        if (address !is Inet6Address || !address.isLinkLocalAddress || address.scopeId != interfaceIndex) return null
        return address.hostAddress
    }

    /** No diagnostic traffic, collect the entire window to detect ambiguity. */
    private fun collectSelectedGateway(socket: DatagramSocket, expected: String, timeoutMs: Int,
        port: Int, interfaceIndex: Int?, onControlTrace: (ControlTrace) -> Unit): GatewayResult {
        val end = deadline(timeoutMs)
        val matches = linkedMapOf<Pair<String, Gateway>, Gateway>()
        val buffer = ByteArray(1024)
        while (System.nanoTime() < end) {
            val wait = try { remaining(end) } catch (_: SocketTimeoutException) { break }
            socket.soTimeout = wait
            val packet = DatagramPacket(buffer, buffer.size)
            try { socket.receive(packet) } catch (_: SocketTimeoutException) { break }
            if (System.nanoTime() >= end) break
            if (packet.port != port || packet.length < 8) continue
            val host = scopedDiscoverySource(packet.address, interfaceIndex) ?: continue
            controlTrace("RX", "UDP", packet.address, buffer.copyOf(packet.length), onControlTrace)
            val frame = try {
                parseFrame(buffer.copyOfRange(0, 8), buffer.copyOfRange(8, packet.length))
            } catch (_: IllegalArgumentException) { continue }
            if (frame.type != 0x0004 || frame.data.size !in setOf(32, 33)) continue
            val observedVin = frame.data.copyOfRange(0, 17).toString(Charsets.US_ASCII)
            if (observedVin != expected) continue
            val gateway = Gateway(observedVin, u16(frame.data, 17), frame.version,
                hex(frame.data.copyOfRange(19, 25)), hex(frame.data.copyOfRange(25, 31)),
                frame.data[31].u8(), frame.data.getOrNull(32)?.u8())
            // Distinguish the actual endpoint, EID/GID, version, and logical address.
            // Further-action and synchronization changes are not identity changes.
            val key = host to gateway.copy(furtherAction = 0, syncStatus = null)
            matches[key] = gateway
            require(matches.size <= 16) { "Too many DoIP gateways for the selected VIN." }
        }
        require(matches.size == 1) { "DoIP gateway not found or ambiguous for the selected VIN." }
        val selected = matches.entries.single()
        return GatewayResult(selected.value, host = selected.key.first)
    }

    internal suspend fun listenSelectedVehicleIpv4(vin: String, timeoutMs: Int, port: Int,
        onControlTrace: (ControlTrace) -> Unit): GatewayResult {
        val expected = vin.trim().uppercase(Locale.ROOT)
        require(validVin(expected) && timeoutMs in 100..30_000 && port in 1..65535)
        val socket = DatagramSocket(null)
        try {
            socket.bind(InetSocketAddress("0.0.0.0", port))
            return io(timeoutMs.toLong() + 1000, socket::close) {
                collectSelectedGateway(socket, expected, timeoutMs, port, null, onControlTrace)
            }
        } finally { socket.close() }
    }

    data class DiscoveryInterface(val index: Int, val name: String)

    /** List only interfaces capable of scoped link-local multicast. Selection remains explicit. */
    suspend fun availableDiscoveryInterfaces(): List<DiscoveryInterface> = withContext(Dispatchers.IO) {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@withContext emptyList()
        java.util.Collections.list(interfaces).mapNotNull { network ->
            try {
                if (network.index > 0 && network.isUp && !network.isLoopback && network.supportsMulticast() &&
                    java.util.Collections.list(network.inetAddresses).any { it is Inet6Address && it.isLinkLocalAddress })
                    DiscoveryInterface(network.index, network.name)
                else null
            } catch (_: java.net.SocketException) { null }
        }.sortedBy { it.index }
    }

    private suspend fun discoverSelectedVehicleIpv6(vin: String, interfaceIndex: Int, passive: Boolean,
        timeoutMs: Int, onControlTrace: (ControlTrace) -> Unit): GatewayResult {
        val expected = vin.trim().uppercase(Locale.ROOT)
        require(validVin(expected) && timeoutMs in 100..30_000 && interfaceIndex > 0)
        val network = requireNotNull(NetworkInterface.getByIndex(interfaceIndex)) { "Unknown network interface." }
        require(network.isUp && network.supportsMulticast()) { "Interface cannot carry IPv6 multicast." }
        val group = Inet6Address.getByAddress(null, InetAddress.getByName("ff02::1").address, interfaceIndex)
        val socket = MulticastSocket(null)
        try {
            socket.bind(InetSocketAddress("::", if (passive) DOIP_UDP_PORT else 0))
            socket.networkInterface = network
            if (passive) socket.joinGroup(InetSocketAddress(group, DOIP_UDP_PORT), network)
            return io(timeoutMs.toLong() + 1000, socket::close) {
                if (!passive) {
                    socket.timeToLive = 1
                    val request = encode(255, 0x0003, expected.toByteArray(Charsets.US_ASCII))
                    socket.send(DatagramPacket(request, request.size, group, DOIP_UDP_PORT))
                    controlTrace("TX", "UDP", group, request, onControlTrace)
                }
                collectSelectedGateway(socket, expected, timeoutMs, DOIP_UDP_PORT, interfaceIndex, onControlTrace)
            }
        } finally { socket.close() }
    }

    /** Discovery and control messages never activate a diagnostic TCP route. */
    suspend fun inspectGateway(host: String, vin: String, operation: GatewayOperation,
        timeoutMs: Int = 5000, eid: String? = null, selectedGateway: Gateway? = null,
        onControlTrace: (ControlTrace) -> Unit = {}): GatewayResult {
        require(eid == null || (host.isNotBlank() && operation == GatewayOperation.DISCOVER))
        return when (operation) {
            GatewayOperation.LISTEN_IPV4 -> {
                require(host.isBlank())
                listenSelectedVehicleIpv4(vin, timeoutMs, DOIP_UDP_PORT, onControlTrace)
            }
            GatewayOperation.DISCOVER_IPV6, GatewayOperation.LISTEN_IPV6 ->
                discoverSelectedVehicleIpv6(vin, host.toInt(), operation == GatewayOperation.LISTEN_IPV6,
                    timeoutMs, onControlTrace)
            else -> if (host.isBlank() && operation == GatewayOperation.DISCOVER)
                discoverSelectedVehicle(vin, timeoutMs, onControlTrace)
            else inspectGatewayAt(host, vin, operation, timeoutMs, DOIP_UDP_PORT, onControlTrace, eid, selectedGateway)
        }
    }

    /** The public operation always uses the standardized port; this seam also
     * permits a loopback simulator to bind an OS-assigned port without collisions. */
    internal suspend fun inspectGatewayAt(host: String, vin: String, operation: GatewayOperation,
        timeoutMs: Int, port: Int, onControlTrace: (ControlTrace) -> Unit): GatewayResult =
        inspectGatewayAt(host, vin, operation, timeoutMs, port, onControlTrace, null)

    internal suspend fun inspectGatewayAt(host: String, vin: String, operation: GatewayOperation,
        timeoutMs: Int, port: Int, onControlTrace: (ControlTrace) -> Unit,
        eid: String?, selectedGateway: Gateway? = null): GatewayResult {
        require(timeoutMs in 100..30_000 && port in 1..65535)
        require(eid == null || operation == GatewayOperation.DISCOVER)
        require(operation in setOf(GatewayOperation.DISCOVER, GatewayOperation.STATUS, GatewayOperation.POWER))
        val address = parseHost(host)
        val expected = vin.trim().uppercase(Locale.ROOT)
        require(expected.isEmpty() || validVin(expected))
        val gateway = discover(address, expected, timeoutMs, onControlTrace, port, eid)
        if (operation == GatewayOperation.DISCOVER) return GatewayResult(gateway)
        require(selectedGateway == null || selectedGateway.matchesIdentity(gateway)) {
            "DoIP gateway changed before the control query."
        }
        val socket = DatagramSocket()
        try {
            return io(timeoutMs.toLong(), socket::close) {
                socket.connect(address, port)
                val power = operation == GatewayOperation.POWER
                val request = encode(gateway.version, if (power) 0x4003 else 0x4001, byteArrayOf())
                socket.send(DatagramPacket(request, request.size, address, port))
                controlTrace("TX", "UDP", address, request, onControlTrace)
                val end = deadline(timeoutMs)
                val buffer = ByteArray(1024)
                var result: GatewayResult? = null
                while (result == null) {
                    socket.soTimeout = remaining(end)
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    remaining(end)
                    if (packet.address != address || packet.port != port || packet.length < 8) continue
                    controlTrace("RX", "UDP", address, buffer.copyOf(packet.length), onControlTrace)
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
        private val rollingTrace: Boolean = false, private val onTrace: (Trace) -> Unit = {},
        private val onControlTrace: (ControlTrace) -> Unit = {},
        private val options: SessionOptions = SessionOptions(),
        private val isReadContextCurrent: () -> Boolean = { true }) {
        private fun recordControl(direction: String, bytes: ByteArray) =
            controlTrace(direction, if (target.tls) "TLS" else "TCP", target.address, bytes, onControlTrace)
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
            check(isReadContextCurrent()) { "DoIP operation context changed." }
            remaining(deadline)
            val pdu = encode(gateway.version, type, body)
            socket.getOutputStream().apply { write(pdu); flush() }
            if (type != 0x8001) recordControl("TX", pdu)
            remaining(deadline)
        }
        private fun receive(deadline: Long): Frame {
            val header = readExact(8, deadline)
            val data = readExact(length(header), deadline)
            val frame = parseFrame(header, data)
            if (frame.type != 0x8001) recordControl("RX", header + data)
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
            send(0x0005, options.activationBody(target.tester), end)
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
                val data = readExact(length(header), frameEnd)
                val frame = parseFrame(header, data)
                if (frame.type != 0x8001) recordControl("RX", header + data)
                require(frame.version == gateway.version && alive(frame, frameEnd)) {
                    "Unsolicited DoIP message between samples."
                }
            }
        }
        fun exchange(request: ByteArray, timeout: Int): ByteArray {
            val end = deadline(timeout)
            send(0x8001, addr(target.tester) + addr(target.ecu) + request, end)
            record("TX", request)
            var responseEnd = responseDeadline(end, System.nanoTime(), options.p2Ms)
            while (true) {
                val frame = receive(responseEnd)
                remaining(responseEnd)
                if (alive(frame, responseEnd)) continue
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
                    if (reply[2].u8() == 0x78) {
                        responseEnd = responseDeadline(end, System.nanoTime(), options.p2StarMs)
                        continue
                    }
                    throw UdsNegativeResponseException(reply[1].u8(), reply[2].u8())
                }
                requirePositiveResponse(request, reply)
                check(isReadContextCurrent()) { "DoIP operation context changed." }
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
        onTrace: (Trace) -> Unit = {}, onSample: (Sample) -> Unit = {},
        onControlTrace: (ControlTrace) -> Unit = {},
        command: ByteArray? = null, approval: EcuWriteConsentFlow.Approval? = null,
        isContextCurrent: () -> Boolean = { false }, dtc: Int = 0, record: Int = 1,
        commands: List<ByteArray>? = null, onCommandReply: (Int, ByteArray) -> Unit = { _, _ -> },
        clientTls: DoIpClientTls? = null, sessionOptions: SessionOptions? = null,
        wwhSeverity: Int = 255, wwhReadinessGroup: Int? = null,
        multiLayout: List<DoIpMultiDid.Field>? = null,
        isReadContextCurrent: () -> Boolean = { true }): Outcome {
        check(isReadContextCurrent()) { "DoIP operation context changed." }
        val options = sessionOptions ?: SessionOptions(timeoutMs)
        require(options.timeoutMs == timeoutMs)
        clientTls?.requireTarget(target)
        require(timeoutMs in 100..30_000 && did in 0..65535 && mask in 0..255)
        require(operation == Operation.VIN || target.expectedVin.isNotEmpty())
        require(sampleCount in 1..30 && intervalMs in 250..5000)
        require(operation !in setOf(Operation.OBD_DID, Operation.OBD_MONITOR) || isObdDid(did))
        require((operation == Operation.BACKUP) == (backupScope != null))
        require(command == null || commands == null)
        val fixedCommands = (commands ?: command?.let { listOf(it) })?.map { it.copyOf() }
        require((operation == Operation.EXCHANGE) == (fixedCommands != null && approval != null))
        val multiMonitor = operation in setOf(Operation.MULTI_MONITOR, Operation.OBD_MULTI_MONITOR)
        val monitoring = multiMonitor || operation in setOf(Operation.MONITOR, Operation.OBD_MONITOR)
        val multi = multiMonitor || operation in setOf(Operation.MULTI_DID, Operation.OBD_MULTI_DID)
        require(multi == (multiLayout != null))
        val layout = multiLayout?.let { if (multiMonitor) DoIpMultiDid.validateMonitor(it) else DoIpMultiDid.validate(it) }
        require(operation !in setOf(Operation.OBD_MULTI_DID, Operation.OBD_MULTI_MONITOR) ||
            requireNotNull(layout).all { isObdDid(it.did) })
        val multiRequest = layout?.let(DoIpMultiDid::request)
        val wwhKind = when (operation) {
            Operation.OBD_WWH_MASK -> DoIpWwhDtc.Kind.MASK
            Operation.OBD_WWH_PERMANENT -> DoIpWwhDtc.Kind.PERMANENT
            Operation.OBD_WWH_READINESS -> DoIpWwhDtc.Kind.READINESS
            else -> null
        }
        val wwhRequest = wwhKind?.let { DoIpWwhDtc.request(it, mask, wwhSeverity, wwhReadinessGroup) }
        val obdDtc = operation in setOf(Operation.OBD_DTC, Operation.OBD_DTC_COUNT,
            Operation.OBD_DTC_SNAPSHOT, Operation.OBD_DTC_EXTENDED)
        val detailRequest = when (operation) {
            Operation.DTC_SNAPSHOT, Operation.OBD_DTC_SNAPSHOT -> UdsDtcDecoder.readDetailRequest(4, dtc, record)
            Operation.DTC_EXTENDED, Operation.OBD_DTC_EXTENDED -> UdsDtcDecoder.readDetailRequest(6, dtc, record)
            else -> null
        }
        if (operation == Operation.EXCHANGE) {
            require(fixedCommands != null && fixedCommands.size in 1..16 &&
                fixedCommands.all { it.size in 1..4091 } && fixedCommands.sumOf { it.size } <= 4091)
            EcuWriteConsentFlow.requireCurrentForTransmit(requireNotNull(approval), isContextCurrent)
        }
        if (backupScope != null) {
            backupScope.validate()
            require(backupScope.vin == target.expectedVin && backupScope.tester == target.tester && backupScope.ecu == target.ecu)
        }
        val budget = if (operation == Operation.BACKUP) 600_000L
            else if (operation == Operation.EXCHANGE) timeoutMs * (requireNotNull(fixedCommands).size + 4L)
            else if (monitoring)
            timeoutMs * (sampleCount + 7L) + intervalMs * (sampleCount - 1L)
            else if (wwhKind != null || multi || obdDtc || operation == Operation.OBD_DID) timeoutMs * 8L else timeoutMs * 5L
        check(isReadContextCurrent()) { "DoIP operation context changed." }
        val gateway = discover(target.address, target.expectedVin, timeoutMs, onControlTrace)
        check(isReadContextCurrent()) { "DoIP operation context changed." }
        requireGatewayIdentity(target, gateway)
        if (operation == Operation.EXCHANGE) require(gateway.vin == target.expectedVin)
        if (backupScope != null) require(gateway.logicalAddress == backupScope.gateway)
        val trace = mutableListOf<Trace>()
        val base = Socket()
        try {
            return io(budget, { base.close() }) {
                base.connect(InetSocketAddress(target.address, if (target.tls) 3496 else 13400), timeoutMs)
                base.soTimeout = timeoutMs
                val wire = if (target.tls) {
                    val factory = clientTls?.factory ?: (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    val secure = factory.createSocket(base, tlsServerName(target.address), 3496, true) as SSLSocket
                    secure.sslParameters = secure.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                    secure.enabledProtocols = secure.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
                    try {
                        secure.startHandshake()
                        clientTls?.requirePresented(secure)
                        secure
                    } catch (error: Exception) {
                        secure.close()
                        throw error
                    }
                } else base
                wire.use {
                    val session = Session(wire, gateway, target, trace, rollingTrace = operation == Operation.BACKUP || monitoring,
                        onTrace = onTrace, onControlTrace = onControlTrace, options = options,
                        isReadContextCurrent = isReadContextCurrent)
                    session.activate(timeoutMs)
                    fun readVin(vinDid: Int = 0xF190): String {
                        val reply = session.exchange(byteArrayOf(0x22, (vinDid shr 8).toByte(), vinDid.toByte()), timeoutMs)
                        require(reply.size == 20)
                        val vin = reply.copyOfRange(3,20).toString(Charsets.US_ASCII)
                        require(validVin(vin) && (gateway.vin == null || gateway.vin == vin) &&
                            (target.expectedVin.isEmpty() || target.expectedVin == vin)) {
                            "ECU VIN differs from the selected vehicle or gateway."
                        }
                        return vin
                    }
                    fun monitorDid(): List<Sample> {
                        val samples = mutableListOf<Sample>()
                        val start = System.nanoTime()
                        for (index in 1..sampleCount) {
                            val request = if (multiMonitor) requireNotNull(multiRequest)
                                else byteArrayOf(0x22, (did shr 8).toByte(), did.toByte())
                            val reply = session.exchange(request, timeoutMs)
                            require(reply.size in 4..4099) { "Missing or oversized DID monitoring sample." }
                            val values = if (multiMonitor) DoIpMultiDid.decode(requireNotNull(layout), reply) else emptyList()
                            val sample = Sample(index, (System.nanoTime() - start) / 1_000_000,
                                if (multiMonitor) reply.copyOf() else reply.copyOfRange(3, reply.size), values)
                            samples += sample
                            onSample(sample.copy(data = sample.data.copyOf(),
                                values = values.map { it.copy(data = it.data.copyOf()) }))
                            if (index < sampleCount) session.waitBetweenSamples(intervalMs, timeoutMs)
                        }
                        return samples.toList()
                    }
                    val vin: String
                    if (wwhKind != null || obdDtc || operation in setOf(Operation.OBD_IDENTIFY, Operation.OBD_DID, Operation.OBD_MONITOR, Operation.OBD_MULTI_DID, Operation.OBD_MULTI_MONITOR)) {
                        // OBD-only ECUs may not implement the generic F190 VIN DID.
                        // Do not silently assume F810 support from an unrelated UDS response.
                        vin = readVin(0xF802)
                        require(vin == target.expectedVin)
                        val protocolValue = decodeObdProtocol(session.exchange(
                            byteArrayOf(0x22, 0xF8.toByte(), 0x10), timeoutMs))
                        if (operation == Operation.OBD_IDENTIFY) {
                            require(readVin(0xF802) == vin)
                            require(decodeObdProtocol(session.exchange(byteArrayOf(0x22, 0xF8.toByte(), 0x10),
                                timeoutMs)) == protocolValue) { "OBD protocol identification changed during discovery." }
                        } else {
                            val routeOperation = if (wwhKind != null || obdDtc)
                                DiagnosticRouteSelector.Operation.DTC else DiagnosticRouteSelector.Operation.MONITOR
                            require(selectObdReadRoute(target, vin, protocolValue, routeOperation).routes.isNotEmpty()) {
                                "Selected ECU did not identify as OBDonUDS."
                            }
                        }
                        val data = if (operation == Operation.OBD_DID) {
                            when (did) {
                                0xF802 -> vin.toByteArray(Charsets.US_ASCII)
                                0xF810 -> byteArrayOf(protocolValue.toByte())
                                else -> session.exchange(byteArrayOf(0x22, (did shr 8).toByte(), did.toByte()),
                                    timeoutMs).let { it.copyOfRange(3, it.size) }
                            }
                        } else null
                        if (operation == Operation.OBD_DID) {
                            require(!requireNotNull(data).isEmpty()) { "Missing DID data." }
                            require(readVin(0xF802) == vin)
                            require(decodeObdProtocol(session.exchange(byteArrayOf(0x22, 0xF8.toByte(), 0x10),
                                timeoutMs)) == protocolValue) { "OBDonUDS identity changed during DID read." }
                        }
                        val samples = if (operation in setOf(Operation.OBD_MONITOR, Operation.OBD_MULTI_MONITOR)) {
                            val measured = monitorDid()
                            require(readVin(0xF802) == vin)
                            require(decodeObdProtocol(session.exchange(byteArrayOf(0x22, 0xF8.toByte(), 0x10),
                                timeoutMs)) == protocolValue) { "OBDonUDS identity changed during monitoring." }
                            measured
                        } else emptyList()
                        val wwhReport = wwhKind?.let {
                            val report = DoIpWwhDtc.decode(it,
                                session.exchange(requireNotNull(wwhRequest), timeoutMs), wwhReadinessGroup)
                            require(readVin(0xF802) == vin)
                            require(decodeObdProtocol(session.exchange(byteArrayOf(0x22, 0xF8.toByte(), 0x10),
                                timeoutMs)) == protocolValue)
                            report
                        }
                        val multiValues = if (operation == Operation.OBD_MULTI_DID) {
                            val values = DoIpMultiDid.decode(requireNotNull(layout),
                                session.exchange(requireNotNull(multiRequest), timeoutMs))
                            require(readVin(0xF802) == vin)
                            require(decodeObdProtocol(session.exchange(byteArrayOf(0x22, 0xF8.toByte(), 0x10),
                                timeoutMs)) == protocolValue)
                            values
                        } else emptyList()
                        val dtcReport = if (operation == Operation.OBD_DTC) {
                            val reply = session.exchange(byteArrayOf(0x19, 0x02, mask.toByte()), timeoutMs)
                            UdsDtcDecoder.decodeStatusMaskReport(reply.copyOfRange(2, reply.size), mask)
                        } else null
                        val dtcCount = if (operation == Operation.OBD_DTC_COUNT)
                            UdsDtcDecoder.decodeCountByStatusMask(session.exchange(
                                byteArrayOf(0x19, 0x01, mask.toByte()), timeoutMs)) else null
                        val dtcDetail = if (obdDtc && detailRequest != null)
                            UdsDtcDecoder.decodeDetail(detailRequest[1].u8(), dtc,
                                session.exchange(detailRequest, timeoutMs)) else null
                        if (obdDtc) {
                            require(readVin(0xF802) == vin)
                            require(decodeObdProtocol(session.exchange(byteArrayOf(0x22, 0xF8.toByte(), 0x10),
                                timeoutMs)) == protocolValue) { "OBDonUDS identity changed during DTC read." }
                        }
                        Outcome(gateway, target.ecu, vin, data, dtcReport, trace.toList(),
                            samples = samples, obdProtocolValue = protocolValue, wwhReport = wwhReport,
                            multiValues = multiValues, dtcCount = dtcCount, dtcDetail = dtcDetail)
                    } else if (operation == Operation.VIN || (operation == Operation.DID && did == 0xF190)) {
                        vin = readVin()
                        Outcome(gateway, target.ecu, vin, vin.toByteArray(Charsets.US_ASCII), null, trace.toList())
                    } else {
                        // The gateway's announcement is not proof of the selected ECU identity.
                        vin = readVin()
                        require(vin == target.expectedVin)
                        if (operation == Operation.EXCHANGE) {
                            val replies = mutableListOf<ByteArray>()
                            for ((index, payload) in requireNotNull(fixedCommands).withIndex()) {
                                EcuWriteConsentFlow.requireCurrentForTransmit(requireNotNull(approval), isContextCurrent)
                                val reply = session.exchange(payload, timeoutMs)
                                EcuWriteConsentFlow.requireCurrentForTransmit(requireNotNull(approval), isContextCurrent)
                                replies += reply
                                onCommandReply(index + 1, reply.copyOf())
                            }
                            Outcome(gateway, target.ecu, vin, replies.last(), null, trace.toList(), commandReplies = replies.toList())
                        } else if (operation == Operation.SUPPORTED_DTCS) {
                            val report = UdsDtcDecoder.decodeSupportedDtcs(session.exchange(byteArrayOf(0x19, 0x0A), timeoutMs))
                            require(readVin() == vin)
                            Outcome(gateway, target.ecu, vin, null, report, trace.toList())
                        } else if (operation == Operation.SNAPSHOT_INDEX) {
                            val index = UdsDtcDecoder.decodeSnapshotIndex(session.exchange(byteArrayOf(0x19, 3), timeoutMs))
                            require(readVin() == vin)
                            Outcome(gateway, target.ecu, vin, null, null, trace.toList(), snapshotIndex = index)
                        } else if (operation == Operation.BACKUP) {
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
                        } else if (operation in setOf(Operation.MONITOR, Operation.MULTI_MONITOR)) {
                            val samples = monitorDid()
                            require(readVin() == vin)
                            Outcome(gateway, target.ecu, vin, null, null, trace.toList(), samples)
                        } else if (operation == Operation.MULTI_DID) {
                            val values = DoIpMultiDid.decode(requireNotNull(layout),
                                session.exchange(requireNotNull(multiRequest), timeoutMs))
                            require(readVin() == vin)
                            Outcome(gateway, target.ecu, vin, null, null, trace.toList(), multiValues = values)
                        } else if (operation in setOf(Operation.DID, Operation.SCALING_DID)) {
                            val sid = if (operation == Operation.SCALING_DID) 0x24.toByte() else 0x22.toByte()
                            val reply = session.exchange(byteArrayOf(sid, (did shr 8).toByte(), did.toByte()), timeoutMs)
                            require(reply.size > 3) { "Missing DID data." }
                            require(readVin() == vin)
                            Outcome(gateway, target.ecu, vin, reply.copyOfRange(3, reply.size), null, trace.toList())
                        } else if (detailRequest != null) {
                            val reply = session.exchange(detailRequest, timeoutMs)
                            val detail = UdsDtcDecoder.decodeDetail(detailRequest[1].u8(), dtc, reply)
                            require(readVin() == vin) { "ECU VIN changed during DTC read." }
                            Outcome(gateway, target.ecu, vin, null, null, trace.toList(), dtcDetail = detail)
                        } else if (operation == Operation.DTC_COUNT) {
                            val reply = session.exchange(byteArrayOf(0x19,0x01,mask.toByte()), timeoutMs)
                            val count = UdsDtcDecoder.decodeCountByStatusMask(reply)
                            require(readVin() == vin) { "ECU VIN changed during DTC read." }
                            Outcome(gateway, target.ecu, vin, null, null, trace.toList(), dtcCount = count)
                        } else {
                            val reply = session.exchange(byteArrayOf(0x19,0x02,mask.toByte()), timeoutMs)
                            val report = UdsDtcDecoder.decodeStatusMaskReport(reply.copyOfRange(2, reply.size), mask)
                            require(readVin() == vin) { "ECU VIN changed during DTC read." }
                            Outcome(gateway, target.ecu, vin, null, report, trace.toList())
                        }
                    }
                }
            }
        } finally { base.close() }
    }
}
