package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationFileInspectorTest {

    @Test
    fun recognizesRawBinaryWithoutClaimingEcuCompatibility() {
        val result = CalibrationFileInspector.inspect("stock.bin", byteArrayOf(0x01, 0x02, 0x03))
        assertEquals(CalibrationFileFormat.RAW_BINARY, result.format)
        assertTrue(result.valid)
        assertEquals(3L, result.payloadBytes)
        assertTrue(result.warnings.any { it.contains("compatibilité ECU") })
    }

    @Test
    fun validatesIntelHexChecksumsAndEof() {
        val input = ":0100000001FE\n:00000001FF\n".toByteArray()
        val result = CalibrationFileInspector.inspect("stock.hex", input)
        assertEquals(CalibrationFileFormat.INTEL_HEX, result.format)
        assertTrue(result.valid)
        assertEquals(1L, result.payloadBytes)
        assertEquals(2, result.recordCount)
        assertEquals(0L, result.addressRanges.single().start)
    }

    @Test
    fun rejectsIntelHexWithBadChecksum() {
        val input = ":0100000001FF\n:00000001FF\n".toByteArray()
        val result = CalibrationFileInspector.inspect("bad.hex", input)
        assertFalse(result.valid)
        assertTrue(result.summary.contains("Checksum"))
    }

    @Test
    fun rejectsIntelHexWithoutEofRecord() {
        val result = CalibrationFileInspector.inspect("missing.hex", ":0100000001FE\n".toByteArray())
        assertFalse(result.valid)
        assertTrue(result.summary.contains("EOF"))
    }

    @Test
    fun validatesMotorolaSRecordChecksumsAndTermination() {
        val input = "S1061000010203E3\nS9031000EC\n".toByteArray()
        val result = CalibrationFileInspector.inspect("stock.s19", input)
        assertEquals(CalibrationFileFormat.MOTOROLA_S_RECORD, result.format)
        assertTrue(result.valid)
        assertEquals(3L, result.payloadBytes)
        assertEquals(2, result.recordCount)
    }

    @Test
    fun rejectsMotorolaSRecordWithBadChecksum() {
        val input = "S1061000010203E4\nS9031000EC\n".toByteArray()
        val result = CalibrationFileInspector.inspect("bad.s19", input)
        assertFalse(result.valid)
        assertTrue(result.summary.contains("Checksum"))
    }

    @Test
    fun doesNotPretendToDecodeHpTunersContainers() {
        val result = CalibrationFileInspector.inspect("read.hpt", byteArrayOf(1, 2, 3, 4))
        assertEquals(CalibrationFileFormat.HP_TUNERS_CONTAINER, result.format)
        assertFalse(result.valid)
        assertTrue(result.summary.contains("propriétaire"))
    }
}
