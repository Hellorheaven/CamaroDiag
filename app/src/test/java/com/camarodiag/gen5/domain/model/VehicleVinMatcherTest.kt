package com.camarodiag.gen5.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VehicleVinMatcherTest {
    private val selectedVin = "1G1FB1E30C9123456"
    private val ecuVin = "1G1FB1E30C9123457"

    @Test
    fun matchingVinsAreNormalizedBeforeComparison() {
        val result = VehicleVinMatcher.compare("1g1fb1e30c9123456", "1G1FB1E30C9123456")

        assertEquals(selectedVin, result?.selectedVehicleVin)
        assertEquals(selectedVin, result?.ecuVin)
        assertEquals(true, result?.matches)
    }

    @Test
    fun differentValidVinsAreReportedAsMismatch() {
        val result = VehicleVinMatcher.compare(selectedVin, ecuVin)

        assertEquals(selectedVin, result?.selectedVehicleVin)
        assertEquals(ecuVin, result?.ecuVin)
        assertEquals(false, result?.matches)
    }

    @Test
    fun missingSelectedVehicleVinDoesNotBecomeFalseMismatch() {
        val result = VehicleVinMatcher.compare("", ecuVin)

        assertEquals(null, result?.selectedVehicleVin)
        assertEquals(ecuVin, result?.ecuVin)
        assertNull(result?.matches)
    }

    @Test
    fun invalidEcuVinIsNotCompared() {
        assertNull(VehicleVinMatcher.compare(selectedVin, "1G1FB1E30C912345"))
        assertNull(VehicleVinMatcher.compare(selectedVin, "1G1FB1E30CI123456"))
        assertNull(VehicleVinMatcher.compare(selectedVin, "1G1FB1E30CQ123456"))
    }

    @Test
    fun vinInputMayContainSeparatorsButMustNormalizeToValidVin() {
        assertEquals(selectedVin, VehicleVinMatcher.normalize("1g1-fb1e30-c9123456"))
    }

    @Test
    fun decodesCamaroModelYearFromTheStandardVinYearPosition() {
        assertEquals(2012, VehicleVinMatcher.modelYearFromVin(selectedVin))
        assertEquals(2024, VehicleVinMatcher.modelYearFromVin("1G1FA1RXXR0123456"))
    }

    @Test
    fun doesNotGuessModelYearFromInvalidOrUnsupportedVin() {
        assertNull(VehicleVinMatcher.modelYearFromVin("INVALID"))
        assertNull(VehicleVinMatcher.modelYearFromVin("1G1FA1RXXS0123456"))
    }

    @Test
    fun decodesEveryCamaroModelYearCodeInTheSupportedRange() {
        val yearCodes = "ABCDEFGHJKLMNPR"
        yearCodes.forEachIndexed { index, code ->
            val vin = selectedVin.toCharArray().also { it[9] = code }.concatToString()
            assertEquals(2010 + index, VehicleVinMatcher.modelYearFromVin(vin))
        }
    }

    @Test
    fun rejectsExcludedVinYearCharacters() {
        listOf('I', 'O', 'Q').forEach { code ->
            val vin = selectedVin.toCharArray().also { it[9] = code }.concatToString()
            assertNull(VehicleVinMatcher.modelYearFromVin(vin))
        }
    }
}
