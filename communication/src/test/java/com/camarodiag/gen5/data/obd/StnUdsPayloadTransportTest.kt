package com.camarodiag.gen5.data.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class StnUdsPayloadTransportTest {
    private val vin = "1G1FA1RXXR0123456"

    @Test fun configuresVerifiedMxPlusAndExchangesCompleteUdsPayload() = runBlocking {
        val commands = mutableListOf<String>()
        val transport = StnUdsPayloadTransport(0x7E0, 0x7E8, 33) { command, _ ->
            commands += command
            when {
                command == "STI" -> "STN2255 v5.10.3"
                command == "STDI" -> "OBDLink MX+ r3.2.1"
                command.startsWith("STPX") -> "7E8 62 F1 90 " + vin.toByteArray().joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
                else -> "OK"
            }
        }

        val response = transport.exchange(byteArrayOf(0x22, 0xF1.toByte(), 0x90.toByte()), 3000)

        assertArrayEquals(byteArrayOf(0x62, 0xF1.toByte(), 0x90.toByte()) + vin.toByteArray(), response)
        assertTrue(commands.contains("STP 33"))
        assertTrue(commands.contains("STCSEGR 1"))
        assertTrue(commands.contains("STCSEGT 1"))
        assertTrue(commands.contains("STCFCPA 7E0, 7E8"))
        assertTrue(commands.any { it.startsWith("STPX h:7E0, d:22F190") })
    }

    @Test fun collectsFinalUdsResponseAfterResponsePending() = runBlocking {
        val commands = mutableListOf<String>()
        val transport = StnUdsPayloadTransport(0x7E0, 0x7E8, 33) { command, _ ->
            commands += command
            when {
                command == "STI" -> "STN2255 v5.10.3"
                command == "STDI" -> "OBDLink MX+ r3.2.1"
                command.startsWith("STPX") -> "7E8 7F 22 78"
                command == "STMA 1" -> "7E8 62 12 34 AB"
                else -> "OK"
            }
        }

        assertArrayEquals(
            byteArrayOf(0x62, 0x12, 0x34, 0xAB.toByte()),
            transport.exchange(byteArrayOf(0x22, 0x12, 0x34), 1000)
        )
        assertTrue(commands.contains("STMA 1"))
    }

    @Test fun rejectsNonMxPlusHardwareBeforeSendingAnyUdsRequest() = runBlocking {
        val commands = mutableListOf<String>()
        val transport = StnUdsPayloadTransport(0x7E0, 0x7E8, 33) { command, _ ->
            commands += command
            when (command) {
                "STI" -> "STN2255 v5.10.3"
                "STDI" -> "Generic STN adapter"
                else -> "OK"
            }
        }

        assertTrue(runCatching { transport.exchange(byteArrayOf(0x22, 0x12, 0x34), 1000) }.isFailure)
        assertFalse(commands.any { it.startsWith("STPX") })
    }

    @Test fun sendsSuppressedTesterPresentWithoutWaitingForEcuReply() = runBlocking {
        val commands = mutableListOf<String>()
        val transport = StnUdsPayloadTransport(0x7E0, 0x7E8, 33) { command, _ ->
            commands += command
            when {
                command == "STI" -> "STN2255 v5.10.3"
                command == "STDI" -> "OBDLink MX+ r3.2.1"
                else -> "OK"
            }
        }

        transport.sendOnly(byteArrayOf(0x3E, 0x80.toByte()), 1000)

        assertTrue(commands.any { it.startsWith("STPX") && it.endsWith("r:0") })
    }

    @Test fun rejectsCanIdentifierAndProtocolFormatMismatch() {
        assertTrue(runCatching { StnUdsPayloadTransport(0x7E0, 0x7E8, 34) { _, _ -> "OK" } }.isFailure)
        assertTrue(runCatching { StnUdsPayloadTransport(0x18DA10F1, 0x18DAF110, 34) { _, _ -> "OK" } }.isSuccess)
        assertTrue(runCatching { StnUdsPayloadTransport(0x7E0, 0x7E8, 63) { _, _ -> "OK" } }.isSuccess)
        assertTrue(runCatching { StnUdsPayloadTransport(0x18DA10F1, 0x18DAF110, 63) { _, _ -> "OK" } }.isFailure)
    }
}
