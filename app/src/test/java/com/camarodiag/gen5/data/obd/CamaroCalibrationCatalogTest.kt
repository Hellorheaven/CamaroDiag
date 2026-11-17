package com.camarodiag.gen5.data.obd

import com.camarodiag.gen5.domain.model.CamaroEngine
import com.camarodiag.gen5.domain.model.CamaroGeneration
import com.camarodiag.gen5.domain.model.VehicleProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CamaroCalibrationCatalogTest {

    @Test
    fun catalogueContainsEveryGen5AndGen6EngineProfile() {
        assertEquals(CamaroEngine.entries.toSet(), CamaroCalibrationCatalog.profiles.map { it.engine }.toSet())
        assertEquals(10, CamaroCalibrationCatalog.profiles.size)
    }

    @Test
    fun catalogueCoversEveryExpectedGen5Powertrain() {
        val gen5 = CamaroCalibrationCatalog.profiles
            .filter { it.generation == CamaroGeneration.GEN5 }
            .map { it.engine }
            .toSet()

        assertEquals(
            setOf(CamaroEngine.LLT, CamaroEngine.LFX, CamaroEngine.LS3, CamaroEngine.L99, CamaroEngine.LSA, CamaroEngine.LS7),
            gen5
        )
    }

    @Test
    fun catalogueCoversEveryExpectedGen6PowertrainIncludingLtg() {
        val gen6 = CamaroCalibrationCatalog.profiles
            .filter { it.generation == CamaroGeneration.GEN6 }
            .map { it.engine }
            .toSet()

        assertEquals(
            setOf(CamaroEngine.LTG, CamaroEngine.LGX, CamaroEngine.LT1, CamaroEngine.LT4),
            gen6
        )
        assertTrue(2016 in CamaroEngine.LTG.years)
        assertTrue(2020 in CamaroEngine.LTG.years)
        assertTrue(2021 in CamaroEngine.LTG.years)
        assertTrue(2023 in CamaroEngine.LTG.years)
        assertFalse(2024 in CamaroEngine.LTG.years)
    }

    @Test
    fun profileLookupRequiresBothEngineAndApplicableModelYear() {
        val valid = CamaroCalibrationCatalog.forVehicle(
            VehicleProfile(modelYear = 2018, engine = CamaroEngine.LTG)
        )
        val valid2022 = CamaroCalibrationCatalog.forVehicle(
            VehicleProfile(modelYear = 2022, engine = CamaroEngine.LTG)
        )
        val valid2023 = CamaroCalibrationCatalog.forVehicle(
            VehicleProfile(modelYear = 2023, engine = CamaroEngine.LTG)
        )
        val invalid = CamaroCalibrationCatalog.forVehicle(
            VehicleProfile(modelYear = 2024, engine = CamaroEngine.LTG)
        )

        assertNotNull(valid)
        assertNotNull(valid2022)
        assertNotNull(valid2023)
        assertEquals("GM E80 (RPO LTG)", valid?.nominalControllerFamily)
        assertEquals("GM E80 (RPO LTG)", valid2022?.nominalControllerFamily)
        assertEquals("GM E80 (RPO LTG)", valid2023?.nominalControllerFamily)
        assertNull(invalid)
    }

    @Test
    fun binaryReadEditAndFlashRemainDisabledUntilControllerProceduresAreVerified() {
        CamaroCalibrationCatalog.profiles.forEach { profile ->
            assertTrue(profile.supportsStandardCalIdRead)
            assertTrue(profile.supportsStandardCvnRead)
            assertFalse(profile.supportsBinaryRead)
            assertFalse(profile.supportsTableEditing)
            assertFalse(profile.supportsEcuWrite)
        }
    }
}
