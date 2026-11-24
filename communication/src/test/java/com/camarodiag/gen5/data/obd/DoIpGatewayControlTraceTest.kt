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

    @Test fun unconfirmedExchangeFailsBeforeAnyDiscovery() = runBlocking {
        val target = DoIpDirectRead.Target.parse("127.0.0.1", "0E80", "1001", vin, false)
        val events = mutableListOf<DoIpDirectRead.ControlTrace>()
        assertTrue(runCatching { DoIpDirectRead.read(target, DoIpDirectRead.Operation.EXCHANGE,
            command = byteArrayOf(0x22, 1, 2), onControlTrace = { events.add(it) }) }.isFailure)
        assertTrue(events.isEmpty())
    }

}
