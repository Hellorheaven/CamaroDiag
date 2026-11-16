package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationTableCodecTest {

    @Test
    fun validatesDefinitionMetadataAgainstVehicleImageAndEcuIdentifiers() {
        val binary = byteArrayOf(0x10, 0x20, 0x30, 0x40)
        val sha256 = java.security.MessageDigest.getInstance("SHA-256").digest(binary)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        val metadata = CalibrationDefinitionMetadata(
            vin = "1G1FA1RXXR0123456",
            calibrationId = "CAL-123",
            cvn = "A1B2C3D4",
            operatingSystemId = "OS-123",
            hardwarePartNumber = "HW-123",
            sourceSha256 = sha256
        )

        assertTrue(
            CalibrationTableCodec.validateMetadataForImage(
                metadata,
                "1G1FA1RXXR0123456",
                "1G1FA1RXXR0123456",
                binary,
                listOf("CAL-123"),
                listOf("A1B2C3D4")
            ).isEmpty()
        )
        val issues = CalibrationTableCodec.validateMetadataForImage(
            metadata,
            "1G1FA1RXXR0123457",
            "1G1FA1RXXR0123457",
            binary + byteArrayOf(0x50),
            listOf("CAL-OTHER"),
            listOf("00000000")
        )
        assertEquals(5, issues.size)
    }

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
        assertEquals(null, table.xAxis)
        assertEquals(null, table.yAxis)
    }

    @Test
    fun parsesCommaSeparatedDefinitionsWithQuotedNamesAndExplicitAxes() {
        val csv = listOf(
            "id,name,offset,rows,columns,type,endian,scale,bias,unit,minimum,maximum,x_offset,x_type,x_endian,x_scale,x_bias,x_unit,y_offset,y_type,y_endian,y_scale,y_bias,y_unit",
            "fuel_main,\"Fuel, main\",0x4,1,2,U16,LE,0.1,0,mg,0,100,0x0,U8,LE,1,0,rpm,0x2,U8,LE,1,0,kPa"
        ).joinToString("\n")

        val parsed = CalibrationTableCodec.parseDefinitions(csv)
        val definition = parsed.definitions.single()

        assertEquals("Fuel, main", definition.name)
        assertEquals(0L, definition.xAxis?.fileOffset)
        assertEquals(2, definition.xAxis?.count)
        assertEquals(2L, definition.yAxis?.fileOffset)
        assertEquals(1, definition.yAxis?.count)
    }

    @Test
    fun decodesSeparateXAxisAndYAxisValuesFromBinaryImage() {
        val csv = listOf(
            "id;name;offset;rows;columns;type;endian;scale;bias;unit;x_offset;x_type;x_endian;x_scale;x_bias;x_unit;y_offset;y_type;y_endian;y_scale;y_bias;y_unit",
            "fuel;Fuel table;4;1;2;U16;LE;0.1;0;mg;0;U8;LE;1;0;rpm;2;U8;LE;1;0;kPa"
        ).joinToString("\n")
        val definition = CalibrationTableCodec.parseDefinitions(csv).definitions.single()
        val binary = byteArrayOf(100, 200.toByte(), 30, 40, 100, 0, 200.toByte(), 0)

        val decoded = CalibrationTableCodec.decode(binary, definition)

        assertEquals(listOf(100.0, 200.0), decoded.xAxisValues)
        assertEquals(listOf(30.0), decoded.yAxisValues)
        assertEquals(10.0, decoded.values[0][0], 0.0001)
        assertEquals(20.0, decoded.values[0][1], 0.0001)
    }

    @Test
    fun rejectsAxisRangesThatExtendBeyondTheBinaryImage() {
        val definition = CalibrationMapDefinition(
            id = "axis-outside", name = "Axis outside", fileOffset = 0, rows = 1, columns = 1,
            valueType = CalibrationValueType.U8, endian = CalibrationEndian.LITTLE,
            scale = 1.0, bias = 0.0, unit = "",
            xAxis = CalibrationAxisDefinition(
                fileOffset = 4, count = 2, valueType = CalibrationValueType.U16,
                endian = CalibrationEndian.LITTLE, scale = 1.0, bias = 0.0, unit = ""
            )
        )

        assertFalse(runCatching { CalibrationTableCodec.decode(byteArrayOf(1, 2, 3, 4, 5), definition) }.isSuccess)
    }

    @Test
    fun rejectsOverflowingDefinitionOffsetsAndOversizedDirectDefinitions() {
        val overflow = CalibrationMapDefinition(
            id = "overflow", name = "Overflow", fileOffset = Long.MAX_VALUE - 1,
            rows = 1, columns = 2, valueType = CalibrationValueType.U32,
            endian = CalibrationEndian.LITTLE, scale = 1.0, bias = 0.0, unit = ""
        )
        val oversized = CalibrationMapDefinition(
            id = "oversized", name = "Oversized", fileOffset = 0,
            rows = 256, columns = 256, valueType = CalibrationValueType.U8,
            endian = CalibrationEndian.LITTLE, scale = 1.0, bias = 0.0, unit = ""
        )

        assertFalse(runCatching { CalibrationTableCodec.decode(ByteArray(32), overflow) }.isSuccess)
        assertFalse(runCatching { CalibrationTableCodec.decode(ByteArray(65_536), oversized) }.isSuccess)
    }

    @Test
    fun rejectsNonFiniteFloatValuesWhenDecoding() {
        val definition = CalibrationMapDefinition(
            id = "float", name = "Float", fileOffset = 0, rows = 1, columns = 1,
            valueType = CalibrationValueType.F32, endian = CalibrationEndian.LITTLE,
            scale = 1.0, bias = 0.0, unit = ""
        )
        val nanBytes = byteArrayOf(0x00, 0x00, 0xC0.toByte(), 0x7F)

        assertFalse(runCatching { CalibrationTableCodec.decode(nanBytes, definition) }.isSuccess)
    }

    @Test
    fun formattingAndReapplyingFloatCellsPreservesUneditedBinaryPrecision() {
        val definition = CalibrationMapDefinition(
            id = "float-precision", name = "Float precision", fileOffset = 0,
            rows = 1, columns = 2, valueType = CalibrationValueType.F32,
            endian = CalibrationEndian.LITTLE, scale = 1.0, bias = 0.0, unit = ""
        )
        val original = java.nio.ByteBuffer.allocate(8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putFloat(0.1234567f)
            .putFloat(12345.678f)
            .array()
        val decoded = CalibrationTableCodec.decode(original, definition)
        val roundTrippedValues = decoded.values.map { row ->
            row.map { CalibrationTableCodec.formatEngineeringValue(it).toDouble() }
        }

        val reapplied = CalibrationTableCodec.applyEdits(original, definition, roundTrippedValues)

        assertTrue(original.contentEquals(reapplied))
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
