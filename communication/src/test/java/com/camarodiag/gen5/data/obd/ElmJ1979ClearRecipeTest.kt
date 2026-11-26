package com.camarodiag.gen5.data.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ElmJ1979ClearRecipeTest {
    private val vin = "1G1FA1RXXR0123456"
    private fun bytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun replies() = mutableMapOf(
        "0902" to (bytes("490201") + vin.toByteArray(Charsets.US_ASCII)),
        "03" to bytes("43010123"),
        "07" to bytes("4700"),
        "0A" to bytes("4A010123"),
        "0101" to bytes("410101000000"),
        "010D" to bytes("410D00"),
        "010C" to bytes("410C0000"))

    @Test fun actualBeforeAfterAndPermanentEvidence() = runBlocking {
        val sent = mutableListOf<String>()
        val records = replies()
        val result = ElmJ1979ClearRecipe.run(6, vin, {}) {
            val command = ElmJ1979EvapTest.hex(it)
            sent += command
            if (command == "04") {
                records["03"] = bytes("4300")
                bytes("44")
            } else records.getValue(command)
        }
        assertEquals("ACKNOWLEDGED_READBACK", result.outcome)
        assertEquals(listOf("P0123"), result.beforeDtcs["03"])
        assertEquals(emptyList<String>(), result.afterDtcs["03"])
        assertEquals(listOf("P0123"), result.afterDtcs["0A"])
        assertEquals(1, sent.count { it == "04" })
        assertEquals(2, sent.count { it == "0101" })
        assertTrue(result.acknowledged)
    }

    @Test fun mismatchedVinMalformedBaselineAndMovementBlockReset() = runBlocking {
        for ((key, value) in listOf("0902" to (bytes("490201") + "1G1FA1RXXR0123457".toByteArray()),
            "03" to bytes("43010000"), "010D" to bytes("410D01"), "010C" to bytes("410C0001"))) {
            val records = replies().apply { put(key, value) }
            val result = ElmJ1979ClearRecipe.run(6, vin, {}) {
                val command = ElmJ1979EvapTest.hex(it)
                assertNotEquals("04", command)
                records.getValue(command)
            }
            assertFalse(result.attempted)
            assertEquals("NOT_TESTED", result.outcome)
        }
    }

    @Test fun missingPostResetEvidenceIsNotZero() = runBlocking {
        val records = replies()
        var sent = 0
        val result = ElmJ1979ClearRecipe.run(8, vin, {}) {
            val command = ElmJ1979EvapTest.hex(it)
            if (command == "04") {
                sent++
                records["03"] = bytes("7F0311")
                bytes("44")
            } else records.getValue(command)
        }
        assertEquals(1, sent)
        assertEquals("ACKNOWLEDGED_PARTIAL", result.outcome)
        assertTrue(result.acknowledged)
        assertNull(result.afterDtcs["03"])
        assertEquals(listOf("P0123"), result.afterDtcs["0A"])
    }

    @Test fun persistentDtcAndLostAckNeverCauseAnotherReset() = runBlocking {
        for ((reply, outcome) in listOf(bytes("44") to "ACKNOWLEDGED_READBACK",
            bytes("7F0422") to "REFUSED", null to "UNCERTAIN")) {
            val records = replies()
            var sent = 0
            val result = ElmJ1979ClearRecipe.run(6, vin, {}) {
                val command = ElmJ1979EvapTest.hex(it)
                if (command == "04") {
                    sent++
                    reply ?: error("Lost acknowledgment")
                } else records.getValue(command)
            }
            assertEquals(1, sent)
            assertEquals(outcome, result.outcome)
            if (reply?.contentEquals(bytes("44")) == true)
                assertEquals("DTCS_STILL_PRESENT", result.reason)
        }
    }
}
