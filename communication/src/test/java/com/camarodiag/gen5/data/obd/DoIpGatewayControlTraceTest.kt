package com.camarodiag.gen5.data.obd

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import org.junit.Assert.*
import org.junit.Test

class DoIpGatewayControlTraceTest {
    private val vin = "1G1FA1RX0J0100001"
    private fun pdu(version: Int, type: Int, data: ByteArray = byteArrayOf()): ByteArray =
        ByteBuffer.allocate(8 + data.size).put(version.toByte()).put((version xor 255).toByte())
            .putShort(type.toShort()).putInt(data.size).put(data).array()

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02X".format(Locale.ROOT, it.toInt() and 255) }

    private fun identity(): ByteArray = pdu(2, 4, vin.toByteArray(Charsets.US_ASCII) +
        byteArrayOf(0x10, 0x00) + ByteArray(13))

    private fun respond(socket: DatagramSocket, request: ByteArray, reply: ByteArray) {
        val incoming = DatagramPacket(ByteArray(1024), 1024)
        socket.receive(incoming)
        assertArrayEquals(request, incoming.data.copyOf(incoming.length))
        socket.send(DatagramPacket(reply, reply.size, incoming.address, incoming.port))
    }

    @Test fun routingAndResponseOptionsAreBoundedAndPreserveWireBytes() {
        val defaults = DoIpDirectRead.SessionOptions()
        assertArrayEquals(byteArrayOf(0x0E, 0x80.toByte(), 0, 0, 0, 0, 0),
            defaults.activationBody(0x0E80))
        val custom = DoIpDirectRead.SessionOptions.parse("5000", "100", "2000", "e0", "01020304")
        assertArrayEquals(byteArrayOf(0x0E, 0x80.toByte(), 0xE0.toByte(), 0, 0, 0, 0, 1, 2, 3, 4),
            custom.activationBody(0x0E80))
        assertEquals(100_000_000L, DoIpDirectRead.responseDeadline(5_000_000_000L, 0, 100))
        assertEquals(5_000_000_000L, DoIpDirectRead.responseDeadline(5_000_000_000L, 4_000_000_000L, 2000))
        assertTrue(runCatching { DoIpDirectRead.SessionOptions.parse("99", "10", "50", "00", "") }.isFailure)
        assertTrue(runCatching { DoIpDirectRead.SessionOptions.parse("5000", "100", "50", "00", "") }.isFailure)
        assertTrue(runCatching { DoIpDirectRead.SessionOptions.parse("5000", "100", "6000", "00", "") }.isFailure)
        assertTrue(runCatching { DoIpDirectRead.SessionOptions.parse("5000", "100", "2000", "000", "") }.isFailure)
        assertTrue(runCatching { DoIpDirectRead.SessionOptions.parse("5000", "100", "2000", "00", "0102") }.isFailure)
    }

    @Test fun discoverySourceRequiresExpectedIpv6Scope() {
        val raw = InetAddress.getByName("fe80::2").address
        val matched = java.net.Inet6Address.getByAddress(null, raw, 3)
        val other = java.net.Inet6Address.getByAddress(null, raw, 4)
        assertEquals(matched.hostAddress, DoIpDirectRead.scopedDiscoverySource(matched, 3))
        assertNull(DoIpDirectRead.scopedDiscoverySource(other, 3))
        assertNull(DoIpDirectRead.scopedDiscoverySource(InetAddress.getByName("2001:db8::2"), 3))
        assertNull(DoIpDirectRead.scopedDiscoverySource(matched, null))
        assertEquals("127.0.0.1", DoIpDirectRead.scopedDiscoverySource(InetAddress.getByName("127.0.0.1"), null))
    }

    @Test fun scopedIpv6IdentityAndTlsNameRemainDistinct() {
        assertTrue(runCatching { DoIpDirectRead.Target.parse("fe80::2", "0E80", "1234", vin, true) }.isFailure)
        assertFalse(DoIpDirectRead.sameHost("fe80::2%3", "fe80::2%4"))
        val target = DoIpDirectRead.Target.parse("fe80::2%3", "0E80", "1234", vin, true)
        assertEquals(target.address.hostAddress?.substringBefore('%'), DoIpDirectRead.tlsServerName(target.address))
    }

    @Test fun eidDiscoveryCorrelatesResponseAndPreservesAnnouncementState(): Unit = runBlocking {
        val loopback = InetAddress.getByName("127.0.0.1")
        DatagramSocket(0, loopback).use { server ->
            server.soTimeout = 2000
            val executor = Executors.newSingleThreadExecutor()
            val eid = byteArrayOf(1, 2, 3, 4, 5, 6)
            val expected = pdu(255, 2, eid)
            val future = executor.submit {
                val request = DatagramPacket(ByteArray(1024), 1024)
                server.receive(request)
                assertArrayEquals(expected, request.data.copyOf(request.length))
                val other = pdu(2, 4, "1G1FA1RX0J0100002".toByteArray(Charsets.US_ASCII) +
                    byteArrayOf(0x10, 0) + ByteArray(13))
                server.send(DatagramPacket(other, other.size, request.address, request.port))
                val announcement = pdu(2, 4, vin.toByteArray(Charsets.US_ASCII) +
                    byteArrayOf(0x10, 0) + eid + ByteArray(6) + byteArrayOf(0x10, 0x10))
                server.send(DatagramPacket(announcement, announcement.size, request.address, request.port))
            }
            try {
                val found = DoIpDirectRead.inspectGatewayAt("127.0.0.1", vin,
                    DoIpDirectRead.GatewayOperation.DISCOVER, 2000, server.localPort, {}, "010203040506")
                assertEquals("010203040506", found.gateway.eid)
                assertEquals(0x10, found.gateway.furtherAction)
                assertEquals(0x10, found.gateway.syncStatus ?: -1)
                future.get(3, TimeUnit.SECONDS)
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun controlQueryRefusesSubstitutedGatewayBeforeSendingStatus(): Unit = runBlocking {
        val loopback = InetAddress.getByName("127.0.0.1")
        DatagramSocket(0, loopback).use { server ->
            server.soTimeout = 2000
            val executor = Executors.newSingleThreadExecutor()
            val future = executor.submit {
                respond(server, pdu(255, 1), identity())
                server.soTimeout = 300
                try {
                    server.receive(DatagramPacket(ByteArray(1024), 1024))
                    fail("Status must not be sent to a changed gateway")
                } catch (_: java.net.SocketTimeoutException) { /* expected */ }
            }
            try {
                val selected = DoIpDirectRead.Gateway(vin, 0x1000, 2, "010203040506", "000000000000")
                val result = runCatching {
                    DoIpDirectRead.inspectGatewayAt("127.0.0.1", vin,
                        DoIpDirectRead.GatewayOperation.STATUS, 1500, server.localPort, {}, null, selected)
                }
                assertTrue(result.isFailure)
                future.get(3, TimeUnit.SECONDS)
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun statusAndPowerRecordActualUdpBytesAndDirection() = runBlocking {
        val loopback = InetAddress.getByName("127.0.0.1")
        val cases = listOf(
            Triple(DoIpDirectRead.GatewayOperation.STATUS, pdu(2, 0x4001),
                pdu(2, 0x4002, byteArrayOf(0, 4, 1, 0, 0, 0x20, 0))),
            Triple(DoIpDirectRead.GatewayOperation.POWER, pdu(2, 0x4003),
                pdu(2, 0x4004, byteArrayOf(1)))
        )
        for ((operation, request, reply) in cases) {
            DatagramSocket(0, loopback).use { socket ->
                socket.soTimeout = 5000
                val executor = Executors.newSingleThreadExecutor()
                try {
                    val discovery = pdu(255, 1)
                    val announcement = identity()
                    val server = executor.submit {
                        respond(socket, discovery, announcement)
                        respond(socket, request, reply)
                    }
                    val events = mutableListOf<DoIpDirectRead.ControlTrace>()
                    val result = DoIpDirectRead.inspectGatewayAt("127.0.0.1", vin, operation,
                        5000, socket.localPort) { events.add(it) }
                    server.get(5, TimeUnit.SECONDS)
                    assertEquals(vin, result.gateway.vin)
                    assertEquals(0x1000, result.gateway.logicalAddress)
                    assertEquals(listOf("TX", "RX", "TX", "RX"), events.map { it.direction })
                    assertEquals(listOf(discovery, announcement, request, reply).map(::hex),
                        events.map { it.payloadHex })
                    assertTrue(events.all { it.transport == "UDP" && it.host == "127.0.0.1" })
                    if (operation == DoIpDirectRead.GatewayOperation.STATUS) {
                        assertEquals(4, result.status?.maxSockets)
                        assertEquals(8192L, result.status?.maxDataSize)
                    } else assertEquals(1, result.power)
                } finally {
                    executor.shutdownNow()
                }
            }
        }
    }

    @Test fun negativeGatewayAcknowledgementIsRecordedBeforeFailure() = runBlocking {
        val loopback = InetAddress.getByName("127.0.0.1")
        DatagramSocket(0, loopback).use { socket ->
            socket.soTimeout = 5000
            val executor = Executors.newSingleThreadExecutor()
            try {
                val discovery = pdu(255, 1)
                val negative = pdu(2, 0, byteArrayOf(1))
                val server = executor.submit { respond(socket, discovery, negative) }
                val events = mutableListOf<DoIpDirectRead.ControlTrace>()
                val result = runCatching {
                    DoIpDirectRead.inspectGatewayAt("127.0.0.1", vin, DoIpDirectRead.GatewayOperation.DISCOVER,
                        5000, socket.localPort) { events.add(it) }
                }
                server.get(5, TimeUnit.SECONDS)
                assertTrue(result.isFailure)
                assertEquals(listOf("TX", "RX"), events.map { it.direction })
                assertEquals(hex(negative), events.last().payloadHex)
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test fun vinDiscoverySelectsOnlyUniqueMatchingGateway() = runBlocking {
        val loopback = InetAddress.getByName("127.0.0.1")
        for (scenario in 0..2) {
            val ambiguous = scenario != 0
            DatagramSocket(0, loopback).use { socket ->
                socket.soTimeout = 5000
                val executor = Executors.newSingleThreadExecutor()
                try {
                    val discovery = pdu(255, 3, vin.toByteArray(Charsets.US_ASCII))
                    val wrongVin = pdu(2, 4, "1G1FA1RX0J0100002".toByteArray(Charsets.US_ASCII) +
                        byteArrayOf(0x10, 0x00) + ByteArray(13))
                    val otherGateway = pdu(2, 4, vin.toByteArray(Charsets.US_ASCII) +
                        byteArrayOf(0x10, 0x01) + ByteArray(13))
                    val conflictingEid = identity().also { it[8 + 19] = 1 }
                    val server = executor.submit {
                        val incoming = DatagramPacket(ByteArray(1024), 1024)
                        socket.receive(incoming)
                        assertArrayEquals(discovery, incoming.data.copyOf(incoming.length))
                        for (reply in listOf(wrongVin, identity(), identity(),
                            when (scenario) { 1 -> otherGateway; 2 -> conflictingEid; else -> identity() })) {
                            socket.send(DatagramPacket(reply, reply.size, incoming.address, incoming.port))
                        }
                    }
                    val events = mutableListOf<DoIpDirectRead.ControlTrace>()
                    val result = runCatching {
                        DoIpDirectRead.discoverSelectedVehicleAt(vin, 1500, loopback, socket.localPort) {
                            events.add(it)
                        }
                    }
                    server.get(5, TimeUnit.SECONDS)
                    assertEquals(hex(discovery), events.first().payloadHex)
                    assertEquals("TX", events.first().direction)
                    if (ambiguous) assertTrue(result.isFailure)
                    else {
                        val gateway = result.getOrThrow()
                        assertEquals("127.0.0.1", gateway.host)
                        assertEquals(vin, gateway.gateway.vin)
                        assertEquals(0x1000, gateway.gateway.logicalAddress)
                        assertEquals("000000000000", gateway.gateway.eid)
                        assertEquals("000000000000", gateway.gateway.gid)
                    }
                } finally {
                    executor.shutdownNow()
                }
            }
        }
    }

    @Test fun routeResolutionUsesDiscoveredGatewayAndKeepsExplicitLogicalAddresses() = runBlocking {
        val selected = DoIpDirectRead.resolveTargetWithDiscovery("", "0E80", "1001",
            vin, true, 0x1000) { requested ->
            assertEquals(vin, requested)
            DoIpDirectRead.GatewayResult(DoIpDirectRead.Gateway(vin, 0x1000, 2), host = "127.0.0.1")
        }
        assertEquals("127.0.0.1", selected.address.hostAddress)
        assertEquals(0x0E80, selected.tester)
        assertEquals(0x1001, selected.ecu)
        assertEquals(0x1000, selected.expectedGateway)
        assertEquals(DoIpDirectRead.Gateway(vin, 0x1000, 2), selected.expectedIdentity)
        assertTrue(selected.tls)
        val mismatch = runCatching {
            DoIpDirectRead.resolveTargetWithDiscovery("", "0E80", "1001", vin, true, 0x1002) {
                DoIpDirectRead.GatewayResult(DoIpDirectRead.Gateway(vin, 0x1000, 2), host = "127.0.0.1")
            }
        }
        assertTrue(mismatch.isFailure)
        val manual = DoIpDirectRead.resolveTargetWithDiscovery("127.0.0.1", "0E80", "1001",
            vin, false, 0x1000) { error("Manual endpoint must not invoke broadcast discovery") }
        assertEquals(0x1000, manual.expectedGateway)
        assertFalse(manual.tls)
        assertTrue(runCatching {
            DoIpDirectRead.resolveTargetWithDiscovery("", "0E80", "0E80", vin, true, null) {
                error("Invalid logical addresses must be rejected before discovery")
            }
        }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun selectedRouteRejectsChangedGatewayIdentityBeforeTcp() {
        val selected = DoIpDirectRead.Gateway(vin, 0x1000, 2, "112233445566", "010203040506")
        val target = DoIpDirectRead.Target.parse("127.0.0.1", "0E80", "1001", vin, true, 0x1000, selected)
        DoIpDirectRead.requireGatewayIdentity(target, selected)
        for (changed in listOf(selected.copy(eid = "112233445567"), selected.copy(gid = "010203040507"),
            selected.copy(version = 3), selected.copy(vin = null), selected.copy(logicalAddress = 0x1002)))
            assertTrue(runCatching { DoIpDirectRead.requireGatewayIdentity(target, changed) }.isFailure)
    }

    @Test fun obdOnUdsReadRouteRequiresMatchingVinAndProtocolIdentification() {
        val target = DoIpDirectRead.Target.parse("127.0.0.1", "0E80", "1001", vin, true)
        assertEquals(1, DoIpDirectRead.decodeObdProtocol(byteArrayOf(0x62, 0xF8.toByte(), 0x10, 1)))
        assertTrue(DoIpDirectRead.selectObdReadRoute(target, vin, 1).routes.isNotEmpty())
        assertTrue(DoIpDirectRead.selectObdReadRoute(target, vin, 1, DiagnosticRouteSelector.Operation.DTC).routes.isNotEmpty())
        assertTrue(DoIpDirectRead.selectObdReadRoute(target, vin, 2, DiagnosticRouteSelector.Operation.DTC).routes.isEmpty())
        assertTrue(DoIpDirectRead.selectObdReadRoute(target, vin, 1, DiagnosticRouteSelector.Operation.PROGRAM).routes.isEmpty())
        assertTrue(DoIpDirectRead.selectObdReadRoute(target, vin, 2).routes.isEmpty())
        assertTrue(DoIpDirectRead.selectObdReadRoute(target, "1G1FA1RX0J0100002", 1).routes.isEmpty())
        assertTrue(runCatching { DoIpDirectRead.decodeObdProtocol(byteArrayOf(0x62, 0xF8.toByte(), 0x11, 1)) }.isFailure)
        assertTrue(DoIpDirectRead.isObdDid(0xF400))
        assertTrue(DoIpDirectRead.isObdDid(0xF8FF))
        assertFalse(DoIpDirectRead.isObdDid(0xF190))
    }

    @Test fun decodedHistoricalRecordsUseActualUdpAndTcpPipeline(): Unit = runBlocking {
        data class Case(val operation: DoIpDirectRead.Operation, val request: String, val response: String,
            val count: Int, val obd: Boolean = false, val memory: Int? = null)
        val cases = listOf(
            Case(DoIpDirectRead.Operation.ALL_STORED_RECORDS, "1905FF", "5905010102030801F40D78", 1),
            Case(DoIpDirectRead.Operation.DECODED_SNAPSHOT, "1904010203FF", "5904010203080101F40D78", 1),
            Case(DoIpDirectRead.Operation.DECODED_EXTENDED, "1906010203FF", "59060102030801123490AA", 2),
            Case(DoIpDirectRead.Operation.MEMORY_DECODED_SNAPSHOT, "1918010203FF02", "591802010203080101F40D78", 1, memory = 2),
            Case(DoIpDirectRead.Operation.MEMORY_DECODED_EXTENDED, "1919010203FF02", "5919020102030801123490AA", 2, memory = 2),
            Case(DoIpDirectRead.Operation.OBD_DECODED_SNAPSHOT, "1904010203FF", "5904010203080101F40D78", 1, obd = true),
            Case(DoIpDirectRead.Operation.OBD_DECODED_EXTENDED, "1906010203FF", "59060102030801123490AA", 2, obd = true))
        fun bytes(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val loopback = InetAddress.getByName("127.0.0.1")
        for (case in cases) for (finalMatches in listOf(true, false)) {
            DatagramSocket(0, loopback).use { udp ->
                java.net.ServerSocket(0, 1, loopback).use { tcp ->
                    udp.soTimeout = 5000
                    tcp.soTimeout = 5000
                    val worker = Executors.newSingleThreadExecutor()
                    try {
                        val server = worker.submit {
                            respond(udp, pdu(255, 1), identity())
                            tcp.accept().use { socket ->
                                socket.soTimeout = 5000
                                val input = java.io.DataInputStream(socket.getInputStream())
                                val output = socket.getOutputStream()
                                fun receive(type: Int, expected: ByteArray) {
                                    val header = ByteArray(8).also { input.readFully(it) }
                                    val size = ByteBuffer.wrap(header).getInt(4)
                                    assertTrue(size in 0..65536)
                                    val payload = ByteArray(size).also { input.readFully(it) }
                                    assertArrayEquals(pdu(2, type, expected), header + payload)
                                }
                                fun send(type: Int, body: ByteArray) {
                                    output.write(pdu(2, type, body)); output.flush()
                                }
                                fun diagnostic(request: String, response: ByteArray) {
                                    receive(0x8001, bytes("0E801234" + request))
                                    send(0x8002, bytes("12340E8000"))
                                    send(0x8001, bytes("12340E80") + response)
                                }
                                receive(5, bytes("0E800000000000"))
                                send(7, byteArrayOf())
                                receive(8, bytes("0E80"))
                                send(6, bytes("0E8010001000000000"))
                                val vinDid = if (case.obd) "F802" else "F190"
                                diagnostic("22" + vinDid, bytes("62" + vinDid) + vin.toByteArray(Charsets.US_ASCII))
                                if (case.obd) diagnostic("22F810", bytes("62F81001"))
                                diagnostic(case.request, bytes(case.response))
                                val finalVin = if (finalMatches) vin else vin.dropLast(1) + "2"
                                diagnostic("22" + vinDid, bytes("62" + vinDid) + finalVin.toByteArray(Charsets.US_ASCII))
                                if (case.obd && finalMatches) diagnostic("22F810", bytes("62F81001"))
                            }
                        }
                        val target = DoIpDirectRead.Target.parse("127.0.0.1", "0E80", "1234", vin, false)
                        val events = mutableListOf<DoIpDirectRead.Trace>()
                        val result = runCatching { DoIpDirectRead.readAt(target, case.operation,
                            timeoutMs = 2000, dtc = 0x010203, record = 255, memory = case.memory,
                            storedDidLengths = if (case.request.startsWith("1906") || case.request.startsWith("1919")) null else mapOf(0xF40D to 1),
                            extendedRecordLengths = if (case.request.startsWith("1906") || case.request.startsWith("1919")) mapOf(1 to 2, 0x90 to 1) else null,
                            onTrace = { events.add(it) }, udpPort = udp.localPort, tcpPort = tcp.localPort) }
                        server.get(6, TimeUnit.SECONDS)
                        if (finalMatches) {
                            val outcome = result.getOrThrow()
                            assertEquals(vin, outcome.vin)
                            assertEquals(case.memory, outcome.decodedMemory)
                            assertEquals(case.count, outcome.decodedSnapshot?.records?.size
                                ?: outcome.decodedExtended?.records?.size ?: outcome.allStoredReport?.records?.size)
                            assertArrayEquals(bytes(case.response), outcome.decodedSnapshot?.rawResponse
                                ?: outcome.decodedExtended?.rawResponse ?: outcome.allStoredReport?.rawResponse)
                        } else assertTrue(result.isFailure)
                        assertTrue(events.any { it.direction == "TX" && it.payloadHex == case.request })
                    } finally { worker.shutdownNow() }
                }
            }
        }
    }

    @Test fun discoveryContextInvalidatedByAnnouncementNeverOpensTcp(): Unit = runBlocking {
        val loopback = InetAddress.getByName("127.0.0.1")
        DatagramSocket(0, loopback).use { udp ->
            java.net.ServerSocket(0, 1, loopback).use { tcp ->
                udp.soTimeout = 3000
                tcp.soTimeout = 500
                val worker = Executors.newSingleThreadExecutor()
                try {
                    val server = worker.submit { respond(udp, pdu(255, 1), identity()) }
                    val target = DoIpDirectRead.Target.parse("127.0.0.1", "0E80", "1234", vin, false)
                    val current = java.util.concurrent.atomic.AtomicBoolean(true)
                    val result = runCatching {
                        DoIpDirectRead.readAt(target, DoIpDirectRead.Operation.VIN,
                            timeoutMs = 2000, isReadContextCurrent = current::get,
                            onControlTrace = { if (it.direction == "RX") current.set(false) },
                            udpPort = udp.localPort, tcpPort = tcp.localPort)
                    }
                    server.get(4, TimeUnit.SECONDS)
                    assertFalse(current.get())
                    assertTrue(result.isFailure)
                    assertTrue(result.exceptionOrNull() is IllegalStateException)
                    try {
                        tcp.accept().use { fail("Obsolete discovery opened a diagnostic TCP session.") }
                    } catch (_: java.net.SocketTimeoutException) {
                        // Expected: cancellation happens before any TCP connection.
                    }
                } finally { worker.shutdownNow() }
            }
        }
    }

    @Test fun monitoringBudgetIncludesEveryIdentityExchangeAndIdleFrameTail() {
        val uds = DoIpDirectRead.Operation.MONITOR
        val multi = DoIpDirectRead.Operation.MULTI_MONITOR
        val obd = DoIpDirectRead.Operation.OBD_MONITOR
        val obdMulti = DoIpDirectRead.Operation.OBD_MULTI_MONITOR
        // One sample has no idle interval or Alive Check tail.
        assertEquals(9000L, DoIpDirectRead.monitoringSessionBudgetMs(uds, 1000, 1, 250))
        assertEquals(10000L, DoIpDirectRead.monitoringSessionBudgetMs(obd, 1000, 1, 250))
        val udsBudget = DoIpDirectRead.monitoringSessionBudgetMs(uds, 1000, 30, 250)
        val obdBudget = DoIpDirectRead.monitoringSessionBudgetMs(obd, 1000, 30, 250)
        assertEquals(103250L, udsBudget)
        assertEquals(133250L, obdBudget)
        assertEquals(udsBudget, DoIpDirectRead.monitoringSessionBudgetMs(multi, 1000, 30, 250))
        assertEquals(obdBudget, DoIpDirectRead.monitoringSessionBudgetMs(obdMulti, 1000, 30, 250))
        assertTrue(runCatching {
            DoIpDirectRead.monitoringSessionBudgetMs(DoIpDirectRead.Operation.DID, 1000, 30, 250)
        }.isFailure)
    }

    @Test fun monitoringPublishesOnlySamplesWithVerifiedIdentity(): Unit = runBlocking {
        fun bytes(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val loopback = InetAddress.getByName("127.0.0.1")
        for (obd in listOf(false, true))
            for ((lastMatches, cancelAfterIdentity) in listOf(false to false, true to false, true to true)) {
            DatagramSocket(0, loopback).use { udp ->
                java.net.ServerSocket(0, 1, loopback).use { tcp ->
                    udp.soTimeout = 5000
                    tcp.soTimeout = 5000
                    val worker = Executors.newSingleThreadExecutor()
                    try {
                        val server = worker.submit {
                            respond(udp, pdu(255, 1), identity())
                            tcp.accept().use { socket ->
                                socket.soTimeout = 5000
                                val input = java.io.DataInputStream(socket.getInputStream())
                                val output = socket.getOutputStream()
                                fun receive(type: Int, expected: ByteArray) {
                                    val header = ByteArray(8).also { input.readFully(it) }
                                    val size = ByteBuffer.wrap(header).getInt(4)
                                    assertTrue(size in 0..65536)
                                    val payload = ByteArray(size).also { input.readFully(it) }
                                    assertArrayEquals(pdu(2, type, expected), header + payload)
                                }
                                fun send(type: Int, body: ByteArray) {
                                    output.write(pdu(2, type, body)); output.flush()
                                }
                                fun diagnostic(request: String, response: ByteArray) {
                                    receive(0x8001, bytes("0E801234" + request))
                                    send(0x8002, bytes("12340E8000"))
                                    send(0x8001, bytes("12340E80") + response)
                                }
                                receive(5, bytes("0E800000000000"))
                                send(6, bytes("0E8010001000000000"))
                                fun identify(matches: Boolean) {
                                    val did = if (obd) "F802" else "F190"
                                    val observed = if (matches || obd) vin else vin.dropLast(1) + "2"
                                    diagnostic("22" + did, bytes("62" + did) + observed.toByteArray(Charsets.US_ASCII))
                                    if (obd) diagnostic("22F810", bytes(if (matches) "62F81001" else "62F81002"))
                                }
                                identify(true)
                                for (index in 1..2) {
                                    diagnostic("22F40D", byteArrayOf(0x62, 0xF4.toByte(), 0x0D, index.toByte()))
                                    identify(index == 1 || lastMatches)
                                }
                            }
                        }
                        val target = DoIpDirectRead.Target.parse("127.0.0.1", "0E80", "1234", vin, false)
                        val samples = mutableListOf<Int>()
                        var identities = 0
                        lateinit var task: Deferred<Result<DoIpDirectRead.Outcome>>
                        task = async(start = CoroutineStart.LAZY) {
                            runCatching { DoIpDirectRead.readAt(target,
                                if (obd) DoIpDirectRead.Operation.OBD_MONITOR else DoIpDirectRead.Operation.MONITOR,
                                did = 0xF40D, timeoutMs = 2000, sampleCount = 2, intervalMs = 250,
                                onTrace = { trace ->
                                    // Cancel on the final identity RX, before the second
                                    // verified measurement can be published by the worker.
                                    if (trace.direction == "RX" &&
                                        trace.payloadHex.startsWith(if (obd) "62F810" else "62F190")) {
                                        identities++
                                        if (cancelAfterIdentity && identities == 3) task.cancel()
                                    }
                                },
                                onSample = {
                                    assertEquals(vin, it.verifiedVin)
                                    assertEquals(0x1000, it.gatewayLogicalAddress)
                                    samples += it.index
                                }, udpPort = udp.localPort, tcpPort = tcp.localPort) }
                        }
                        val result = runCatching { task.await().getOrThrow() }
                        server.get(6, TimeUnit.SECONDS)
                        assertEquals(lastMatches && !cancelAfterIdentity, result.isSuccess)
                        if (cancelAfterIdentity) assertTrue(task.isCancelled)
                        assertEquals(if (lastMatches && !cancelAfterIdentity) listOf(1, 2) else listOf(1), samples)
                    } finally { worker.shutdownNow() }
                }
            }
        }
    }

    @Test fun wildcardStoredRecordsUseRealRecordNumbersAndRejectTruncation() {
        fun bytes(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val request = bytes("1905FF")
        val reply = bytes("5905010102030802F40C1234F40D56020405060001F40D78")
        DoIpDirectRead.requirePositiveResponse(request, reply)
        DoIpDirectRead.requirePositiveResponse(request, bytes("5905FF"))
        val report = UdsDtcDecoder.decodeAllStoredRecords(reply,
            UdsDtcDecoder.parseStoredDidLengths("F40C:2,F40D:1"))
        assertEquals(2, report.records.size)
        assertArrayEquals(bytes("1234"), report.records.first().dids.first().data)
        assertArrayEquals(bytes("78"), report.records.last().dids.single().data)
        for (bad in listOf("590501", "5905FF0102030800", "5905010102030802F40C"))
            assertTrue(runCatching { DoIpDirectRead.requirePositiveResponse(request, bytes(bad)) }.isFailure)
        assertTrue(runCatching { DoIpDirectRead.requirePositiveResponse(bytes("190502"), reply) }.isFailure)
    }

    @Test fun rawDtcResponsesMustCorrelateRecordAndFunctionalGroup() {
        fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val valid = listOf("1903" to "5903", "190A" to "590A08",
            "190412345601" to "5904123456080100",
            "1906123456FE" to "5906123456089001",
            "19563307" to "595633FF0407",
            "241234" to "64123400")
        valid.forEach { (tx, rx) -> DoIpDirectRead.requirePositiveResponse(hex(tx), hex(rx)) }
        val invalid = listOf("1903" to "590301", "190A" to "590A",
            "190A" to "590A0801", "190412345601" to "5904123456080200",
            "190612345690" to "5906123457089001",
            "195533" to "595534FF04",
            "19563307" to "595633FF0408",
            "241234" to "64123500")
        invalid.forEach { (tx, rx) ->
            assertTrue(runCatching { DoIpDirectRead.requirePositiveResponse(hex(tx), hex(rx)) }.isFailure)
        }
    }

    @Test fun explicitCommandPreservesBytesAndCorrelatesStandardResponse() {
        val commands = DoIpDirectRead.parseCommands("22 F1 90;19 01 FF")
        assertEquals(2, commands.size)
        assertArrayEquals(byteArrayOf(0x19, 1, 0xFF.toByte()), commands[1])
        for (invalid in listOf("22;", ";22", "22;;19", List(17) { "22" }.joinToString(";")))
            assertTrue(runCatching { DoIpDirectRead.parseCommands(invalid) }.isFailure)
        val command = DoIpDirectRead.parseCommand("22 F1 90")
        assertArrayEquals(byteArrayOf(0x22, 0xF1.toByte(), 0x90.toByte()), command)
        DoIpDirectRead.requirePositiveResponse(command, byteArrayOf(0x62, 0xF1.toByte(), 0x90.toByte(), 1))
        assertTrue(runCatching { DoIpDirectRead.requirePositiveResponse(command,
            byteArrayOf(0x62, 0xF1.toByte(), 0x91.toByte(), 1)) }.isFailure)
        assertTrue(runCatching { DoIpDirectRead.requirePositiveResponse(byteArrayOf(0x36, 1),
            byteArrayOf(0x76, 2)) }.isFailure)
        for (invalid in listOf("", "0x22", "A", "GG", "AA".repeat(4092)))
            assertTrue(runCatching { DoIpDirectRead.parseCommand(invalid) }.isFailure)
    }

    @Test fun inventoryStopsWhenDiagnosticTraceSinkFails(): Unit = runBlocking {
        val loopback = InetAddress.getByName("127.0.0.1")
        DatagramSocket(0, loopback).use { udp ->
            java.net.ServerSocket(0, 1, loopback).use { tcp ->
                udp.soTimeout = 3000
                tcp.soTimeout = 3000
                val worker = Executors.newSingleThreadExecutor()
                try {
                    val server = worker.submit {
                        repeat(2) { respond(udp, pdu(255, 1), identity()) }
                        tcp.accept().use { peer ->
                            peer.soTimeout = 3000
                            val input = java.io.DataInputStream(peer.getInputStream())
                            val output = peer.getOutputStream()
                            val routing = ByteArray(15).also { input.readFully(it) }
                            assertArrayEquals(pdu(2, 5, byteArrayOf(0x0E, 0x80.toByte(), 0, 0, 0, 0, 0)), routing)
                            output.write(pdu(2, 6, byteArrayOf(0x0E, 0x80.toByte(), 0x10, 0, 0x10, 0, 0, 0, 0)))
                            output.flush()
                            val request = ByteArray(15).also { input.readFully(it) }
                            assertArrayEquals(pdu(2, 0x8001, byteArrayOf(0x0E, 0x80.toByte(),
                                0x12, 0x34, 0x22, 0xF1.toByte(), 0x90.toByte())), request)
                            assertEquals(-1, input.read())
                        }
                    }
                    val target = DoIpDirectRead.Target.parse("127.0.0.1", "0E80", "1234", vin, false)
                    val failure = java.io.IOException("Evidence sink failed")
                    val result = runCatching { DoIpDirectRead.inventoryAt(target, listOf(0x1234, 0x1235),
                        DoIpDirectRead.SessionOptions(timeoutMs = 1000),
                        onTrace = { _, _ -> throw failure },
                        udpPort = udp.localPort, tcpPort = tcp.localPort) }
                    server.get(5, TimeUnit.SECONDS)
                    assertTrue(result.exceptionOrNull() is DoIpDirectRead.InventoryTraceException)
                    assertSame(failure, result.exceptionOrNull()?.cause)
                } finally { worker.shutdownNow() }
            }
        }
    }

    @Test fun explicitInventoryRejectsDuplicateAndImplicitRanges() = runBlocking {
        assertEquals(listOf(0x1234, 0x1235), DoIpDirectRead.parseInventoryAddresses("1234,1235"))
        for (invalid in listOf("", "1234,1234", "1234-1235", "123", (0..16).joinToString(",") { "%04X".format(it) })) {
            assertTrue(runCatching { DoIpDirectRead.parseInventoryAddresses(invalid) }.isFailure)
        }
        val target = DoIpDirectRead.Target.parse("127.0.0.1", "0E80", "1234", vin, false)
        val events = mutableListOf<DoIpDirectRead.ControlTrace>()
        assertTrue(runCatching { DoIpDirectRead.inventory(target, listOf(0x0E80), DoIpDirectRead.SessionOptions(),
            onControlTrace = { events.add(it) }) }.isFailure)
        assertTrue(runCatching { DoIpDirectRead.inventory(target, listOf(0x1234), DoIpDirectRead.SessionOptions(),
            onControlTrace = { events.add(it) }, current = { false }) }.isFailure)
        assertTrue(events.isEmpty())
    }

    @Test fun legislatedInventoryRejectsRevokedContextAndSingleRead() = runBlocking {
        val target = DoIpDirectRead.Target.parse("127.0.0.1", "0E80", "1234", vin, false)
        val events = mutableListOf<DoIpDirectRead.ControlTrace>()
        assertTrue(runCatching { DoIpDirectRead.inventory(target, listOf(0x1234),
            DoIpDirectRead.SessionOptions(), onControlTrace = { events.add(it) },
            current = { false }, obd = true) }.isFailure)
        assertTrue(runCatching { DoIpDirectRead.read(target, DoIpDirectRead.Operation.OBD_INVENTORY,
            onControlTrace = { events.add(it) }) }.isFailure)
        assertTrue(events.isEmpty())
        assertEquals(2, DoIpDirectRead.decodeObdProtocol(byteArrayOf(0x62, 0xF8.toByte(), 0x10, 2)))
        assertTrue(runCatching { DoIpDirectRead.decodeObdProtocol(byteArrayOf(0x62, 0xF8.toByte(), 0x10)) }.isFailure)
    }

    @Test fun revokedReadContextFailsBeforeAnyDiscovery() = runBlocking {
        val target = DoIpDirectRead.Target.parse("127.0.0.1", "0E80", "1001", vin, false)
        val events = mutableListOf<DoIpDirectRead.ControlTrace>()
        assertTrue(runCatching { DoIpDirectRead.read(target, DoIpDirectRead.Operation.DTC,
            isReadContextCurrent = { false }, onControlTrace = { events.add(it) }) }.isFailure)
        assertTrue(events.isEmpty())
    }

    @Test fun unconfirmedExchangeFailsBeforeAnyDiscovery() = runBlocking {
        val target = DoIpDirectRead.Target.parse("127.0.0.1", "0E80", "1001", vin, false)
        val events = mutableListOf<DoIpDirectRead.ControlTrace>()
        assertTrue(runCatching { DoIpDirectRead.read(target, DoIpDirectRead.Operation.EXCHANGE,
            command = byteArrayOf(0x22, 1, 2), onControlTrace = { events.add(it) }) }.isFailure)
        assertTrue(events.isEmpty())
    }

}
