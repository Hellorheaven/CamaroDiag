package com.camarodiag.gen5.data.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ObdMode08DiscoveryTest {
    @Test fun canInventoryReadsContinuationWithoutSendingAnyActuation() = runBlocking {
        val commands = mutableListOf<String>()
        val result = ObdMode08Discovery.discover(false, "7E8") { command, _ ->
            commands += command
            when (command) {
                "0800" -> "0800\r480080000001\r>"
                "0820" -> "482040000000"
                else -> error("Unexpected request " + command)
            }
        }
        assertEquals(listOf("0800", "0820"), commands)
        assertEquals(setOf(1, 34), result.supportedTests)
        assertEquals("7E8", result.physicalResponder)
        assertTrue(result.complete)
    }

    @Test fun legacyUsesFiveZeroDataBytesAndRequiresZeroFiller() = runBlocking {
        val result = ObdMode08Discovery.discover(true) { command, _ ->
            assertEquals("08000000000000", command)
            "48000080000000"
        }
        assertEquals(setOf(1), result.supportedTests)
        assertTrue(result.complete)
        val malformed = ObdMode08Discovery.discover(true) { _, _ -> "48000180000000" }
        assertTrue(malformed.supportedTests.isEmpty())
        assertFalse(malformed.complete)
    }

    @Test fun finalContinuationCannotWrapToAnotherPage() = runBlocking {
        val result = ObdMode08Discovery.discover(false) { command, _ ->
            "48" + command.substring(2) + "80000001"
        }
        assertFalse(result.complete)
        assertEquals(8, result.pages.size)
        assertTrue(result.pages.last().positiveRecords.isEmpty())
    }

    @Test fun deadlineDoesNotTransmitAnotherInventoryPage() = runBlocking {
        var now = 0L
        val commands = mutableListOf<String>()
        val result = ObdMode08Discovery.discover(false, budgetMs = 100L, clockMs = { now }) { cmd, timeout ->
            commands += cmd
            assertEquals(100L, timeout)
            now += timeout
            "480080000001"
        }
        assertEquals(listOf("0800"), commands)
        assertFalse(result.complete)
        assertEquals(setOf(1), result.supportedTests)
    }

    @Test fun mixedOrMissingResponsesDoNotProveCompleteInventory() = runBlocking {
        val result = ObdMode08Discovery.discover(false) { command, _ ->
            when (command) {
                "0800" -> "480080000001"
                "0820" -> "7F0811"
                else -> error("Unexpected request " + command)
            }
        }
        assertEquals(setOf(1), result.supportedTests)
        assertFalse(result.complete)
        assertEquals(2, result.pages.size)
        val wrong = ObdMode08Discovery.discover(false) { _, _ -> "480180000000" }
        assertFalse(wrong.complete)
        assertTrue(wrong.supportedTests.isEmpty())
    }
}
