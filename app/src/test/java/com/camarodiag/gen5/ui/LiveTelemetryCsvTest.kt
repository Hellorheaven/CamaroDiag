package com.camarodiag.gen5.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTelemetryCsvTest {

    @Test
    fun exportsOneRowPerSensorValueWithStableNumericFormatting() {
        val csv = LiveTelemetryCsv.encode(
            samples = listOf(
                RecordedLiveSample(
                    timestampMillis = 1_700_000_000_000,
                    values = listOf(
                        RecordedLivePid("RPM", "Engine speed", 1726.0, "rpm"),
                        RecordedLivePid("ECT", "Coolant temperature", 91.5, "°C")
                    )
                )
            ),
            vin = "1G1FA1RXXR0123456",
            vehicleLabel = "2018 Camaro LT1"
        )

        val lines = csv.trim().lines()
        assertEquals("timestamp_ms,vin,vehicle,pid,label,value,unit", lines.first())
        assertEquals(3, lines.size)
        assertTrue(lines[1].contains(",\"RPM\","))
        assertTrue(lines[1].contains(",1726.00000,"))
        assertTrue(lines[2].contains(",91.50000,"))
    }

    @Test
    fun escapesCommasQuotesAndLineBreaksInTextFields() {
        val csv = LiveTelemetryCsv.encode(
            samples = listOf(
                RecordedLiveSample(
                    1234,
                    listOf(RecordedLivePid("TEST", "Load, \"Bank 1\"\nraw", 50.0, "%"))
                )
            ),
            vin = "VIN,TEST",
            vehicleLabel = "Camaro \"Test\""
        )

        assertTrue(csv.contains("\"VIN,TEST\""))
        assertTrue(csv.contains("\"Camaro \"\"Test\"\"\""))
        assertTrue(csv.contains("\"Load, \"\"Bank 1\"\"\nraw\""))
    }

    @Test
    fun returnsHeaderOnlyForNoSamples() {
        assertEquals(
            "timestamp_ms,vin,vehicle,pid,label,value,unit\n",
            LiveTelemetryCsv.encode(emptyList(), "", "")
        )
    }
}
