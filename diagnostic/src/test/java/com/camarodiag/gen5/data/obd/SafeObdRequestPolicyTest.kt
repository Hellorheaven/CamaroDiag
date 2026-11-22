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
        assertEquals("090A", SafeObdRequestPolicy.parse("09 0A")?.elmCommand)
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
        assertEquals(true, SafeObdRequestPolicy.hasPositiveResponse(dtc, "43 01 03 00"))
        for (invalid in listOf("43 FF", "43 01 03", "43 01 00 00", "43 00 03 00")) {
            assertEquals(invalid, false, SafeObdRequestPolicy.hasPositiveResponse(dtc, invalid))
        }
        val vin = requireNotNull(SafeObdRequestPolicy.parse("0902"))
        val completeVin = "49 02 01 " +
            "1G1FA1RXXR0123456".toByteArray(Charsets.US_ASCII).joinToString(" ") {
                "%02X".format(java.util.Locale.ROOT, it.toInt() and 0xFF)
            }
        assertEquals(true, SafeObdRequestPolicy.hasPositiveResponse(vin, completeVin))
        assertEquals(false, SafeObdRequestPolicy.hasPositiveResponse(vin, "49 02 01 31"))
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

    @Test
    fun aMatchingNegativeEcuReplyRemainsReceivedTrafficButNotSuccess() {
        val request = requireNotNull(SafeObdRequestPolicy.parse("0152"))
        for (reply in listOf("7F 01 11", "7F0111", "7E8 7F 01 11",
            "7E8 03 7F 01 11", "7E8 03 7F 01 11 00 00", "18DAF110 7F0111")) {
            assertEquals(reply, true, SafeObdRequestPolicy.hasNegativeResponse(request, reply))
            assertEquals(reply, false, SafeObdRequestPolicy.hasPositiveResponse(request, reply))
        }
    }

    @Test
    fun unrelatedNegativeAndAdapterErrorsAreNotAttributedToEcu() {
        val request = requireNotNull(SafeObdRequestPolicy.parse("0152"))
        for (reply in listOf("7F 09 11", "7F 01", "7F 01 00", "7F 01 78", "7F 01 11 00",
            "7E0 7F 01 11", "7E8 03 7F 01 11 FF", "NO DATA", "OK", "41 52 80")) {
            assertEquals(reply, false, SafeObdRequestPolicy.hasNegativeResponse(request, reply))
        }
    }

    @Test
    fun conflictingPositiveAndNegativeLinesCannotConfirmConsoleSuccess() {
        val request = requireNotNull(SafeObdRequestPolicy.parse("0152"))
        val mixed = "41 52 80\n7F 01 11"
        assertEquals(true, SafeObdRequestPolicy.hasPositiveResponse(request, mixed))
        assertEquals(true, SafeObdRequestPolicy.hasNegativeResponse(request, mixed))
        assertEquals(false, SafeObdRequestPolicy.hasUnambiguousPositiveResponse(request, mixed))
        assertEquals(true, SafeObdRequestPolicy.hasUnambiguousPositiveResponse(request, "41 52 80"))
    }

    @Test
    fun liveMode01ReadRequiresTheExactSaePayloadLength() {
        val cases = listOf(
            Triple("010C", "41 0C 1A F8", "41 0C 1A"),
            Triple("0110", "41 10 00 64", "41 10 64"),
            Triple("0105", "41 05 5B", "41 05 5B 00"),
            Triple("0152", "41 52 80", "41 52 80 00"),
            Triple("0140", "41 40 00 00 40 00", "41 40 00 00 40"),
            Triple("0124", "41 24 00 01 02 03", "41 24 00 01"),
            Triple("0144", "41 44 80 00", "41 44 80"),
            Triple("0114", "41 14 80 80", "41 14 80")
        )
        for ((command, valid, invalid) in cases) {
            val request = requireNotNull(SafeObdRequestPolicy.parse(command))
            assertEquals(command, true, SafeObdRequestPolicy.hasPositiveResponse(request, valid))
            assertEquals(command, false, SafeObdRequestPolicy.hasPositiveResponse(request, invalid))
        }
    }

    @Test
    fun allWhitelistedMode01PidsHaveAnExplicitPayloadLength() {
        for (pid in 0..255) {
            val command = "01%02X".format(java.util.Locale.ROOT, pid)
            val request = SafeObdRequestPolicy.parse(command) ?: continue
            assertEquals(command, 0x01, request.service)
            assertEquals(command, true, SafeObdRequestPolicy.expectedMode01DataBytes(pid) != null)
        }
    }

    @Test
    fun physicalEcuNameReadRequiresMatchingMode09Pid() {
        val name = requireNotNull(SafeObdRequestPolicy.parse("090A"))
        assertEquals(true, SafeObdRequestPolicy.hasPositiveResponse(name, "49 0A 01 45 43 4D"))
        assertEquals(false, SafeObdRequestPolicy.hasPositiveResponse(name, "49 04 01 45 43 4D"))
        assertEquals(false, SafeObdRequestPolicy.hasPositiveResponse(name, "7F 09 12"))
        assertEquals(true, SafeObdRequestPolicy.hasNegativeResponse(name, "7F 09 12"))
    }

}
