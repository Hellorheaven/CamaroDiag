package com.camarodiag.gen5.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSensorCatalogTest {
    @Test fun hasUniqueBilingualNamesForAllKnownSensors() {
        val catalog = LiveSensorCatalog.known
        assertTrue(catalog.size >= 30)
        assertEquals(catalog.size, catalog.map { it.id }.distinct().size)
        assertTrue(catalog.all { it.labelEn.isNotBlank() && it.labelFr.isNotBlank() })
        assertTrue(catalog.any { it.id == "ETHANOL" })
        assertTrue(catalog.any { it.id == "WBO2_B2S1" })
    }

    @Test fun showsUnknownObservedSensorsWithoutDuplicates() {
        val found = LiveSensorDefinition("CUSTOM", "Additional signal", "Signal supplémentaire")
        val repeated = LiveSensorDefinition("RPM", "Unverified replacement", "Remplacement non vérifié")
        val entries = LiveSensorCatalog.entriesWithObserved(listOf(found, repeated))
        assertEquals(1, entries.count { it.id == "RPM" })
        assertEquals("Engine speed", entries.first { it.id == "RPM" }.labelEn)
        assertEquals(1, entries.count { it.id == "CUSTOM" })
        assertEquals(entries.sortedBy { it.id }, entries)
    }

    @Test fun knownCatalogNeverImpliesMeasuredOrSupported() {
        val observedIds = setOf("RPM", "ECT")
        assertTrue(LiveSensorCatalog.isMeasured("RPM", observedIds))
        assertFalse(LiveSensorCatalog.isMeasured("ETHANOL", observedIds))
        assertFalse(LiveSensorCatalog.isMeasured("WBO2_B2S1", observedIds))
    }
}
