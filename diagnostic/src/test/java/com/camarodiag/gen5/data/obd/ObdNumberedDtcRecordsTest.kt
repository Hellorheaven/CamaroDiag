package com.camarodiag.gen5.data.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ObdNumberedDtcRecordsTest {
    private val response = "008\r0: 430303000100\r1: C123\r002\r0: 4300"
    private val request = requireNotNull(SafeObdRequestPolicy.parse("03"))

    @Test fun completeIndependentNumberedRecordsAreRecognizedByConsoleAndScan() = runBlocking {
        assertTrue(SafeObdRequestPolicy.hasPositiveResponse(request, response))
        assertFalse(SafeObdRequestPolicy.hasPositiveResponse(request, response, expectedResponder = "7E8"))
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            if (command == "03") response else "NO DATA"
        })
        assertEquals(setOf("P0300", "P0100", "U0123"),
            engine.readDtcModeEvidence()[3]?.map { it.code }?.toSet())
    }

    @Test fun malformedNumberedRecordsCannotConfirmPositiveRead() = runBlocking {
        for (bad in listOf(response.replace("1:", "2:"),
            response.replace("C123", "C1"), response.replace("430303", "430403"),
            response.replace("C123", "0000"), response + "\r1: FFFF")) {
            assertFalse(bad, SafeObdRequestPolicy.hasPositiveResponse(request, bad))
            val engine = Obd2FeatureEngine(ObdCommandChannel { _, _ -> bad })
            assertNull(bad, engine.readDtcModeEvidence()[3])
        }
    }

    @Test fun finalNegativeResponseInAnotherNumberedRecordIsNotIgnored() {
        val mixed = response + "\r003\r0: 7F0311"
        assertTrue(SafeObdRequestPolicy.hasPositiveResponse(request, mixed))
        assertTrue(SafeObdRequestPolicy.hasNegativeResponse(request, mixed))
        assertFalse(SafeObdRequestPolicy.hasUnambiguousPositiveResponse(request, mixed))
    }
}
