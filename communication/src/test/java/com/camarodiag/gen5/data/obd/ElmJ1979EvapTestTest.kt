package com.camarodiag.gen5.data.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ElmJ1979EvapTestTest {
    private val vin = "1G1FA1RXXR0123456"
    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun replies() = mutableMapOf(
        "0902" to (bytes("490201") + vin.toByteArray(Charsets.US_ASCII)),
        "0800" to bytes("480080000000"),
        "010D" to bytes("410D00"),
        "010C" to bytes("410C0000"),
        "0801" to bytes("4801"))

    @Test fun selectsPhysicalPrimaryAndRequiresAllPreconditions() = runBlocking {
        val sent = mutableListOf<String>()
        val replies = replies()
        val result = ElmJ1979EvapTest.run(6, vin, {}) { request ->
            val key = ElmJ1979EvapTest.hex(request)
            sent += key
            replies.getValue(key)
        }
        assertEquals(listOf("0902", "0800", "010D", "010C", "0801"), sent)
        assertEquals("ACKNOWLEDGED", result.outcome)
        assertTrue(result.attempted)
        assertEquals("7E0", result.requestCanId)
        assertEquals("7E8", result.responseCanId)
        assertEquals("4801", result.exchanges.last().responseHex)
        assertEquals(0x18DA10F1 to 0x18DAF110, ElmJ1979EvapTest.addresses(7))
    }

    @Test fun missingSupportMovingVehicleOrWrongVinCannotTransmitTest() = runBlocking {
        for ((key, value) in listOf("0800" to bytes("480000000000"),
            "010D" to bytes("410D01"), "010C" to bytes("410C0001"),
            "0902" to (bytes("490201") + "1G1FA1RXXR0123457".toByteArray()))) {
            val responses = replies().apply { put(key, value) }
            val result = ElmJ1979EvapTest.run(6, vin, {}) {
                val request = ElmJ1979EvapTest.hex(it)
                assertNotEquals("0801", request)
                responses.getValue(request)
            }
            assertFalse(result.attempted)
            assertEquals("NOT_TESTED", result.outcome)
        }
    }

    @Test fun noRetryOnRefusalOrLostAcknowledgment() = runBlocking {
        for ((reply, outcome) in listOf(bytes("7F0822") to "REFUSED", bytes("4802") to "UNCERTAIN",
            null to "UNCERTAIN")) {
            var commands = 0
            val responses = replies()
            val result = ElmJ1979EvapTest.run(8, vin, {}) {
                val key = ElmJ1979EvapTest.hex(it)
                if (key == "0801") {
                    commands++
                    reply ?: error("Lost reply")
                } else responses.getValue(key)
            }
            assertEquals(1, commands)
            assertEquals(outcome, result.outcome)
            assertTrue(result.attempted)
        }
    }
}
