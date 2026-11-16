package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CamaroSignalCatalogTest {
    @Test
    fun decodesUnsignedBigEndianSignalWithScaleAndOffset() {
        val format = CamaroSignalFormat(
            bitLength = 16,
            multiply = 0.1,
            add = -10.0,
            unit = "celsius"
        )

        val decoded = format.decode(byteArrayOf(0x01, 0x2C))

        assertEquals(300.0, decoded.raw.toDouble(), 0.0)
        assertEquals(20.0, decoded.value!!, 0.0)
        assertEquals("celsius", decoded.unit)
        assertFalse(decoded.unavailable)
    }

    @Test
    fun decodesSignedLittleEndianSignal() {
        val format = CamaroSignalFormat(
            bitLength = 16,
            littleEndian = true,
            signed = true,
            divide = 8.0,
            unit = "rpm"
        )

        val decoded = format.decode(byteArrayOf(0xF8.toByte(), 0xFF.toByte()))

        assertEquals(-1.0, decoded.value!!, 0.0)
    }

    @Test
    fun recognizesNullSentinelAndValueMap() {
        val format = CamaroSignalFormat(
            bitLength = 8,
            nullMaximum = 255.0,
            unit = "status",
            valueMap = mapOf("1" to "Active")
        )

        assertTrue(format.decode(byteArrayOf(0xFF.toByte())).unavailable)
        assertEquals("Active", format.decode(byteArrayOf(0x01)).mappedLabel)
    }

    @Test
    fun proposedSignalCannotBeExecuted() {
        val catalog = CamaroSignalCatalog.fromJson(signalJson(verification = "PROPOSED"))

        assertEquals(1, catalog.forYear(2012).size)
        assertTrue(catalog.executableFor(2012, "2012-LFX").isEmpty())
    }

    @Test
    fun vehicleVerifiedSignalRequiresMatchingVehicleAndEvidence() {
        val catalog = CamaroSignalCatalog.fromJson(
            signalJson(
                verification = "VEHICLE_VERIFIED",
                evidence = "[\"capture:VIN-redacted-01\"]",
                configurations = "[\"2012-LFX\"]"
            )
        )
        val signal = catalog.find("ENGINE_OIL_TEMP")

        assertNotNull(signal)
        assertTrue(signal!!.isVehicleExecutable)
        assertEquals(1, catalog.executableFor(2012, "2012-LFX").size)
        assertTrue(catalog.executableFor(2012, "2012-LS3").isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDuplicateSignalIds() {
        val one = signalJson().substringAfter("\"signals\":[")
            .substringBeforeLast("]}")
        CamaroSignalCatalog.fromJson(
            "{\"schemaVersion\":1,\"signals\":[$one,$one]}"
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedSchemaVersion() {
        CamaroSignalCatalog.fromJson("{\"schemaVersion\":2,\"signals\":[]}")
    }

    private fun signalJson(
        verification: String = "PROPOSED",
        evidence: String = "[]",
        configurations: String = "[]"
    ): String = """
        {
          "schemaVersion": 1,
          "signals": [{
            "id": "ENGINE_OIL_TEMP",
            "name": "Engine oil temperature",
            "category": "Engine",
            "service": "22",
            "identifier": "1234",
            "requestAddress": "7E0",
            "responseAddress": "7E8",
            "protocol": "15765-4-11bit",
            "frequencySeconds": 1.0,
            "verification": "$verification",
            "evidenceReferences": $evidence,
            "verifiedVehicleConfigurations": $configurations,
            "format": {
              "bitOffset": 0,
              "bitLength": 8,
              "multiply": 1.0,
              "divide": 1.0,
              "unit": "celsius"
            }
          }]
        }
    """.trimIndent()
}
