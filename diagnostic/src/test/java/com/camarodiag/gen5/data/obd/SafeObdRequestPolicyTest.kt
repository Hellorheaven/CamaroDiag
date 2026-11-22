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
    fun rejectsOversizedInputBeforeParsing() {
        assertNull(SafeObdRequestPolicy.parse("01" + " ".repeat(31) + "0C"))
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
    @Test
    fun acceptsReadOnlyPidsExposedByLiveVehicleTelemetryIncludingEthanol() {
        val livePids = listOf(
            0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B,
            0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11,
            0x14, 0x15, 0x16, 0x17, 0x24, 0x25, 0x34, 0x35,
            0x42, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49,
            0x4A, 0x4B, 0x4C, 0x52
        )
        for (pid in livePids) {
            val command = "01%02X".format(java.util.Locale.ROOT, pid)
            assertEquals(command, SafeObdRequestPolicy.parse(command)?.elmCommand)
            assertEquals(pid, SafeObdRequestPolicy.parse(command)?.parameter)
        }
    }

    @Test
    fun allowingEthanolReadingDoesNotPermitDestructiveServices() {
        assertEquals("0152", SafeObdRequestPolicy.parse("01 52")?.elmCommand)
        for (command in listOf("04", "14", "2E", "27", "31", "34", "36", "3D", "85", "10 03")) {
            assertNull(SafeObdRequestPolicy.parse(command))
        }
    }

    @Test
    fun positiveResponsesAcceptCompactSpacedAndPhysicalHeaderFormats() {
        val request = requireNotNull(SafeObdRequestPolicy.parse("0152"))
        for (response in listOf("41 52 80", "415280", "7E8 41 52 80",
            "7E8 03 41 52 80", "7E8 03 41 52 80 00 00", "18DAF110 41 52 80")) {
            assertEquals(response, true, SafeObdRequestPolicy.hasPositiveResponse(request, response))
        }
        val dtc = requireNotNull(SafeObdRequestPolicy.parse("03"))
        assertEquals(true, SafeObdRequestPolicy.hasPositiveResponse(dtc, "43 00"))
        assertEquals(true, SafeObdRequestPolicy.hasPositiveResponse(dtc, "4300"))
        val vin = requireNotNull(SafeObdRequestPolicy.parse("0902"))
        assertEquals(true, SafeObdRequestPolicy.hasPositiveResponse(vin, "49 02 01 31"))
    }

    @Test
    fun commandEchoAdapterAcknowledgementAndNegativeReplyNeverCountAsSuccess() {
        val request = requireNotNull(SafeObdRequestPolicy.parse("0152"))
        for (response in listOf("0152", "OK", "NO DATA", "7F 01 11",
            "41 53 80", "41 52", "41 52 80 00",
            "7E8 05 41 52 80", "7E8 02 41 52 80",
            "SEARCHING...", "7E0 41 52 80",
            "41 52 80 0", "")) {
            assertEquals(response, false, SafeObdRequestPolicy.hasPositiveResponse(request, response))
        }
    }

}
