package com.camarodiag.gen5.data.obd

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
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
