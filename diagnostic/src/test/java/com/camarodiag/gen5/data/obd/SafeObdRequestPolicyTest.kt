package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SafeObdRequestPolicyTest {
    @Test
    fun acceptsWhitelistedMode01RequestsAndNormalizesSpacing() {
        val request = SafeObdRequestPolicy.parse("01 0C")
        assertEquals(0x01, request?.service)
        assertEquals(0x0C, request?.parameter)
        assertEquals("010C", request?.elmCommand)
    }

    @Test
    fun acceptsWhitelistedMode09VehicleInformationRequests() {
        assertEquals("0902", SafeObdRequestPolicy.parse("0x09 0x02")?.elmCommand)
        assertEquals("0904", SafeObdRequestPolicy.parse("0904")?.elmCommand)
        assertEquals("0906", SafeObdRequestPolicy.parse("09 06")?.elmCommand)
    }

    @Test
    fun acceptsOnlyParameterlessReadOnlyDtcServices() {
        listOf("03", "07", "0A").forEach { command ->
            val request = SafeObdRequestPolicy.parse(command)
            assertEquals(command.toInt(16), request?.service)
            assertEquals(null, request?.parameter)
            assertEquals(command, request?.elmCommand)
        }
        assertEquals("03", SafeObdRequestPolicy.parse("0x03")?.elmCommand)
    }

    @Test
    fun rejectsParameterizedDtcCommandsAndClearCodesService() {
        assertNull(SafeObdRequestPolicy.parse("03 01"))
        assertNull(SafeObdRequestPolicy.parse("04"))
        assertNull(SafeObdRequestPolicy.parse("04 00"))
    }

    @Test
    fun rejectsUdsWriteSecurityAndRoutineServices() {
        assertNull(SafeObdRequestPolicy.parse("2E F190"))
        assertNull(SafeObdRequestPolicy.parse("27 01"))
        assertNull(SafeObdRequestPolicy.parse("31 01"))
        assertNull(SafeObdRequestPolicy.parse("34 00"))
    }

    @Test
    fun rejectsUnknownPidsAndArbitraryCanFrames() {
        assertNull(SafeObdRequestPolicy.parse("01 FF"))
        assertNull(SafeObdRequestPolicy.parse("7E0 01 0C"))
        assertNull(SafeObdRequestPolicy.parse("7E0"))
        assertNull(SafeObdRequestPolicy.parse("01 0C 00"))
    }

    @Test
    fun rejectsMalformedAndEmptyInput() {
        assertNull(SafeObdRequestPolicy.parse(""))
        assertNull(SafeObdRequestPolicy.parse("hello"))
        assertNull(SafeObdRequestPolicy.parse("0xGG 0x0C"))
    }

    @Test
    fun rejectsMisplacedOrRepeatedHexPrefixes() {
        assertNull(SafeObdRequestPolicy.parse("0x0x010C"))
        assertNull(SafeObdRequestPolicy.parse("010xC"))
        assertNull(SafeObdRequestPolicy.parse("0x01 0x0x0C"))
        assertNull(SafeObdRequestPolicy.parse("01:0C"))
    }

    @Test
    fun rejectsNonAsciiHexDigits() {
        assertNull(SafeObdRequestPolicy.parse("０１ ０Ｃ"))
        assertNull(SafeObdRequestPolicy.parse("٠١ ٠C"))
    }

    @Test
    fun acceptsOnlyOnePairRegardlessOfWhitespace() {
        assertEquals("010C", SafeObdRequestPolicy.parse("  01   0c  ")?.elmCommand)
        assertNull(SafeObdRequestPolicy.parse("01\t0C extra"))
    }
}
