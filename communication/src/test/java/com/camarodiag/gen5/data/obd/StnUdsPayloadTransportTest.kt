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

    @Test fun selectsSingleWireCanIso15765PresetWhenRequested() = runBlocking {
        val commands = mutableListOf<String>()
        val transport = StnUdsPayloadTransport(0x7E0, 0x7E8, 63) { command, _ ->
            commands += command
            when {
                command == "STI" -> "STN2255 v5.10.3"
                command == "STDI" -> "OBDLink MX+ r3.2.1"
                command.startsWith("STPX") -> "7E8 62 F1 90 01"
                else -> "OK"
            }
        }

        assertArrayEquals(byteArrayOf(0x62, 0xF1.toByte(), 0x90.toByte(), 0x01), transport.exchange(
            byteArrayOf(0x22, 0xF1.toByte(), 0x90.toByte()), 1000
        ))
        assertTrue(commands.contains("STP 63"))
    }

    @Test fun documentedObdLinkModelsUseIsoTpForHsCanDiagnostics() = runBlocking {
        for (model in listOf("EX", "CX", "LX", "SX", "MX")) {
            val commands = mutableListOf<String>()
            val transport = StnUdsPayloadTransport(0x7E0, 0x7E8, 33) { command, _ ->
                commands += command
                when {
                    command == "STI" -> "STN2100 v5.10.3"
                    command == "STDI" -> "OBDLink $model r3.2.1"
                    command.startsWith("STPX") -> "7E8 62 12 34"
                    else -> "OK"
                }
            }
            assertArrayEquals(
                "HS-CAN UDS must work for OBDLink $model",
                byteArrayOf(0x62, 0x12, 0x34),
                transport.exchange(byteArrayOf(0x22, 0x12, 0x34), 1000)
            )
            assertTrue("Expected ISO-TP on $model", commands.contains("STP 33"))
            assertTrue("Expected actual UDS transaction on $model",
                commands.any { it.startsWith("STPX h:7E0, d:221234") })
        }
    }

    @Test fun swCanIsRejectedBeforeProtocolSelectionOnUnsupportedObdLinkModels() = runBlocking {
        for (model in listOf("EX", "CX", "LX", "SX")) {
            val commands = mutableListOf<String>()
            val transport = StnUdsPayloadTransport(0x7E0, 0x7E8, 63) { command, _ ->
                commands += command
                when (command) {
                    "STI" -> "STN2100 v5.10.3"
                    "STDI" -> "OBDLink $model r3.2.1"
                    else -> "OK"
                }
            }
            val result = runCatching {
                transport.exchange(byteArrayOf(0x22, 0x12, 0x34), 1000)
            }
            assertTrue("GM SW-CAN must fail on $model", result.isFailure)
            assertFalse("Unsupported SW-CAN must not be selected on $model",
                commands.any { it.startsWith("STP ") || it.startsWith("STPX") })
        }
    }

    @Test fun assemblesLongStpxPayloadFromMatchingResponderAndContinuationOnly() = runBlocking {
        val transport = StnUdsPayloadTransport(0x7E0, 0x7E8, 33) { command, _ ->
            when {
                command == "STI" -> "STN2255 v5.10.3"
                command == "STDI" -> "OBDLink MX+ r3.2.1"
                command.startsWith("STPX") ->
                    "STPX h:7E0, d:22F190\n" +
                        "7E9 62 00 00\n" +
                        "7E8 62F190 31 47 31\n" +
                        "32 33 34 35 36\n" +
                        "7E9 DE AD BE EF\n" +
                        "7E8 37 38 39\n" +
                        ">"
                else -> "OK"
            }
        }
        assertArrayEquals(
            byteArrayOf(0x62, 0xF1.toByte(), 0x90.toByte()) +
                "1G123456789".toByteArray(Charsets.US_ASCII),
            transport.exchange(byteArrayOf(0x22, 0xF1.toByte(), 0x90.toByte()), 1500)
        )
    }

    @Test fun rejectsUnattributedStpxTextInsteadOfTreatingItAsEcuBytes() = runBlocking {
        val transport = StnUdsPayloadTransport(0x7E0, 0x7E8, 33) { command, _ ->
            when {
                command == "STI" -> "STN2255 v5.10.3"
                command == "STDI" -> "OBDLink MX+ r3.2.1"
                command.startsWith("STPX") -> "62 F1 90 01\n7E9 62 F1 90 02\nOK"
                else -> "OK"
            }
        }
        assertTrue(runCatching {
            transport.exchange(byteArrayOf(0x22, 0xF1.toByte(), 0x90.toByte()), 1000)
        }.isFailure)
    }

    @Test fun parsesExtendedCanResponderWithCompactHexPayload() = runBlocking {
        val transport = StnUdsPayloadTransport(0x18DA10F1, 0x18DAF110, 34) { command, _ ->
            when {
                command == "STI" -> "STN2255 v5.10.3"
                command == "STDI" -> "OBDLink MX+ r3.2.1"
                command.startsWith("STPX") ->
                    "18DAF111 62F190AAAA\n18DAF110 621234AB\n>"
                else -> "OK"
            }
        }
        assertArrayEquals(
            byteArrayOf(0x62, 0x12, 0x34, 0xAB.toByte()),
            transport.exchange(byteArrayOf(0x22, 0x12, 0x34), 1000)
        )
    }

    @Test fun rejectsCanIdentifierAndProtocolFormatMismatch() {
        assertTrue(runCatching { StnUdsPayloadTransport(0x7E0, 0x7E8, 34) { _, _ -> "OK" } }.isFailure)
        assertTrue(runCatching { StnUdsPayloadTransport(0x18DA10F1, 0x18DAF110, 34) { _, _ -> "OK" } }.isSuccess)
        assertTrue(runCatching { StnUdsPayloadTransport(0x7E0, 0x7E8, 63) { _, _ -> "OK" } }.isSuccess)
        assertTrue(runCatching { StnUdsPayloadTransport(0x18DA10F1, 0x18DAF110, 63) { _, _ -> "OK" } }.isFailure)
    }
}
