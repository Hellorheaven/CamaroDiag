package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationTableCodecTest {

    @Test
    fun parsesUserSuppliedTsvDefinition() {
        val definitions = CalibrationTableCodec.parseDefinitions(CalibrationTableCodec.template)
        val table = definitions.definitions.single()
        assertEquals("example_fuel", table.id)
        assertEquals(0x1000L, table.fileOffset)
        assertEquals(16, table.rows)
        assertEquals(16, table.columns)
        assertEquals(CalibrationValueType.U16, table.valueType)
        assertEquals(CalibrationEndian.LITTLE, table.endian)
    }

    @Test
    fun decodesAndEditsLittleEndianU16WithoutChangingOtherBytes() {
        val definition = CalibrationMapDefinition(
            id = "fuel", name = "Fuel", fileOffset = 2, rows = 1, columns = 2,
            valueType = CalibrationValueType.U16, endian = CalibrationEndian.LITTLE,
            scale = 0.5, bias = 0.0, unit = "mg"
        )
        val original = byteArrayOf(0x55, 0x66, 0x0A, 0x00, 0x14, 0x00, 0x77)
        val decoded = CalibrationTableCodec.decode(original, definition)
        assertEquals(5.0, decoded.values[0][0], 0.0001)
        assertEquals(10.0, decoded.values[0][1], 0.0001)

        val modified = CalibrationTableCodec.applyEdits(original, definition, listOf(listOf(6.0, 8.0)))
        assertEquals(0x55.toByte(), modified[0])
        assertEquals(0x66.toByte(), modified[1])
        assertEquals(12, modified[2].toInt() and 0xFF)
        assertEquals(0, modified[3].toInt() and 0xFF)
        assertEquals(16, modified[4].toInt() and 0xFF)
        assertEquals(0, modified[5].toInt() and 0xFF)
        assertEquals(0x77.toByte(), modified[6])
        assertEquals(10, original[2].toInt() and 0xFF)
    }

    @Test
    fun decodesBigEndianAndSignedValues() {
        val definition = CalibrationMapDefinition(
            id = "signed", name = "Signed", fileOffset = 0, rows = 1, columns = 1,
            valueType = CalibrationValueType.S16, endian = CalibrationEndian.BIG,
            scale = 0.1, bias = 0.0, unit = "deg"
        )
        val table = CalibrationTableCodec.decode(byteArrayOf(0xFF.toByte(), 0x9C.toByte()), definition)
        assertEquals(-10.0, table.values[0][0], 0.0001)
    }

    @Test
    fun rejectsInvalidOrDuplicateDefinitionIdsAndDimensions() {
        val duplicate = """
            id;name;offset;rows;columns;type;endian;scale;bias;unit
            a;Table A;0;1;1;U8;LE;1;0;x
            a;Table B;1;1;1;U8;LE;1;0;x
        """.trimIndent()
        val oversized = """
            id;name;offset;rows;columns;type;endian;scale;bias;unit
            a;Huge;0;128;128;U8;LE;1;0;x
        """.trimIndent()
        assertFalse(runCatching { CalibrationTableCodec.parseDefinitions(duplicate) }.isSuccess)
        assertFalse(runCatching { CalibrationTableCodec.parseDefinitions(oversized) }.isSuccess)
    }

    @Test
    fun rejectsTableDefinitionsThatExtendBeyondTheBinaryImage() {
        val definition = CalibrationMapDefinition(
            id = "outside", name = "Outside", fileOffset = 3, rows = 1, columns = 2,
            valueType = CalibrationValueType.U16, endian = CalibrationEndian.LITTLE,
            scale = 1.0, bias = 0.0, unit = ""
        )
        assertFalse(runCatching { CalibrationTableCodec.decode(byteArrayOf(1, 2, 3, 4), definition) }.isSuccess)
    }

    @Test
    fun rejectsValuesOutsideTheStorageTypeRange() {
        val definition = CalibrationMapDefinition(
            id = "byte", name = "Byte", fileOffset = 0, rows = 1, columns = 1,
            valueType = CalibrationValueType.U8, endian = CalibrationEndian.LITTLE,
            scale = 1.0, bias = 0.0, unit = ""
        )
        assertFalse(runCatching { CalibrationTableCodec.applyEdits(byteArrayOf(0), definition, listOf(listOf(256.0))) }.isSuccess)
        assertTrue(runCatching { CalibrationTableCodec.applyEdits(byteArrayOf(0), definition, listOf(listOf(255.0))) }.isSuccess)
    }

    @Test
    fun enforcesOptionalEngineeringLimits() {
        val definition = CalibrationMapDefinition(
            id = "limited", name = "Limited", fileOffset = 0, rows = 1, columns = 1,
            valueType = CalibrationValueType.U8, endian = CalibrationEndian.LITTLE,
            scale = 1.0, bias = 0.0, unit = "%", minimum = 0.0, maximum = 100.0
        )
        assertFalse(runCatching { CalibrationTableCodec.applyEdits(byteArrayOf(0), definition, listOf(listOf(101.0))) }.isSuccess)
        assertTrue(runCatching { CalibrationTableCodec.applyEdits(byteArrayOf(0), definition, listOf(listOf(100.0))) }.isSuccess)
    }
}
