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

    private fun frames(address: String, payload: String): List<String> {
        val bytes = payload.chunked(2)
        if (bytes.size <= 7) return listOf(address + " %02X ".format(bytes.size) + bytes.joinToString(" "))
        val first = address + " %02X %02X ".format(0x10 or (bytes.size shr 8), bytes.size and 255) +
            bytes.take(6).joinToString(" ")
        return listOf(first) + bytes.drop(6).chunked(7).mapIndexed { index, row ->
            address + " %02X ".format(0x20 or ((index + 1) and 15)) + row.joinToString(" ")
        }
    }

    @Test fun interleavedMode09FramesAreReassembledPerActualCanHeader() = runBlocking {
        val first = "ABCDEFGH12345678"
        val second = "IJKLMNOP12345678"
        val a = frames("7E8", "490401" + hex(first))
        val b = frames("7EA", "490401" + hex(second))
        val mixed = a.zip(b).flatMap { listOf(it.first, it.second) }.joinToString("\r")
        assertEquals(listOf(first, second), engine(mixed).readCalibrationIds())
        assertTrue(engine(mixed.substringBeforeLast("\r")).readCalibrationIds().isEmpty())
        val wrongSequence = mixed.replace("7EA 21 ", "7EA 22 ")
        assertTrue(engine(wrongSequence).readCalibrationIds().isEmpty())
        val single = frames("7EC", "4906010A63D427").single()
        assertEquals(listOf("0A63D427"), engine(single).readCalibrationVerificationNumbers())
        val compact = mixed.lines().joinToString("\r") { it.replace(" ", "") }
        assertEquals(listOf(first, second), engine(compact).readCalibrationIds())
    }

    @Test fun headerlessInterleavingRetriesWithPhysicalHeaders() = runBlocking {
        val a = frames("7E8", "490401" + hex("ABCDEFGH12345678"))
        val b = frames("7EA", "490401" + hex("IJKLMNOP12345678"))
        var headers = true
        val commands = mutableListOf<String>()
        val reader = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            commands += command
            when (command) {
                "ATH0" -> { headers = false; "OK" }
                "ATH1" -> { headers = true; "OK" }
                "0904" -> if (headers) a.zip(b).flatMap { listOf(it.first, it.second) }.joinToString("\r")
                    else "013\r0:490401414243\r013\r0:490401494A4B"
                else -> "OK"
            }
        }, physicalElmHeaders = true)
        assertEquals(listOf("ABCDEFGH12345678", "IJKLMNOP12345678"), reader.readCalibrationIds())
        assertEquals(listOf("ATH0", "ATS0", "0904", "ATH1", "ATS0", "0904"), commands)
        assertTrue(headers)
    }

    @Test fun consoleRejectsIncompleteSecondNumberedIdentityRecord() {
        val request = requireNotNull(SafeObdRequestPolicy.parse("0902"))
        val first = block("490201" + hex(vin))
        assertTrue(SafeObdRequestPolicy.hasPositiveResponse(request, first + "\r" + first))
        assertFalse(SafeObdRequestPolicy.hasPositiveResponse(request, first + "\r" + first.dropLast(2)))
    }
}
