package com.camarodiag.gen5.data.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ObdIdentityMultiResponderTest {
    private val vin = "1G1FA1RXXR0123456"
    private fun hex(text: String) = text.map { "%02X".format(it.code) }.joinToString("")
    private fun block(payload: String): String {
        val rows = listOf(payload.take(12)) + payload.drop(12).chunked(14)
        return "%03X".format(payload.length / 2) + "\r" +
            rows.mapIndexed { index, row -> "%X: ".format(index % 16) + row }.joinToString("\r")
    }
    private fun engine(response: String) = Obd2FeatureEngine(ObdCommandChannel { _, _ -> response })

    @Test fun identicalVinFromTwoEcusRemainsOneObservedVin() = runBlocking {
        val first = block("490201" + hex(vin))
        assertEquals(vin, engine(first + "\r" + first).readVin())
        assertEquals(vin, engine(first + "\r490201" + hex(vin)).readVin())
        assertNull(engine(first + "\r" + block("490201" + hex(vin.dropLast(1) + "7"))).readVin())
        assertNull(engine(first + "\r" + first.dropLast(2)).readVin())
    }

    @Test fun calibrationAndCvnKeepIndependentRecords() = runBlocking {
        val first = "ABCDEFGH12345678"
        val second = "IJKLMNOP12345678"
        assertEquals(listOf(first, second), engine(block("490401" + hex(first)) +
            "\r" + block("490401" + hex(second))).readCalibrationIds())
        assertEquals(listOf("01020304", "AABBCCDD"),
            engine("49060101020304\r490601AABBCCDD").readCalibrationVerificationNumbers())
    }

    @Test fun consoleRejectsIncompleteSecondNumberedIdentityRecord() {
        val request = requireNotNull(SafeObdRequestPolicy.parse("0902"))
        val first = block("490201" + hex(vin))
        assertTrue(SafeObdRequestPolicy.hasPositiveResponse(request, first + "\r" + first))
        assertFalse(SafeObdRequestPolicy.hasPositiveResponse(request, first + "\r" + first.dropLast(2)))
    }
}
