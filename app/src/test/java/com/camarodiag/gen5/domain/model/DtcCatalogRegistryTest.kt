package com.camarodiag.gen5.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DtcCatalogRegistryTest {
    @Test
    fun registryCoversEveryEngineRpoDeclaredByTheVehicleModel() {
        assertEquals(CamaroEngine.entries.map { it.rpo }.toSet(), DtcCatalogRegistry.supportedEngineRpos)
        CamaroEngine.entries.forEach { engine ->
            val year = engine.years.first
            val vehicle = VehicleProfile(modelYear = year, engine = engine)
            assertEquals(
                if (engine == CamaroEngine.LFX && year in 2012..2015) DtcEvidenceStatus.DOCUMENTED
                else DtcEvidenceStatus.GENERIC_ONLY,
                DtcCatalogRegistry.evidenceStatus(vehicle, "ECM")
            )
        }
    }

    @Test
    fun engineProfilesExposeRpoSpecificArchitectureWithoutCrossEngineAssumptions() {
        val expected = mapOf(
            "LLT" to Triple(6, EngineInductionType.NATURALLY_ASPIRATED, EngineFuelSystemType.DIRECT_INJECTION),
            "LFX" to Triple(6, EngineInductionType.NATURALLY_ASPIRATED, EngineFuelSystemType.DIRECT_INJECTION),
            "LS3" to Triple(8, EngineInductionType.NATURALLY_ASPIRATED, EngineFuelSystemType.PORT_INJECTION),
            "L99" to Triple(8, EngineInductionType.NATURALLY_ASPIRATED, EngineFuelSystemType.PORT_INJECTION),
            "LSA" to Triple(8, EngineInductionType.SUPERCHARGED, EngineFuelSystemType.PORT_INJECTION),
            "LS7" to Triple(8, EngineInductionType.NATURALLY_ASPIRATED, EngineFuelSystemType.PORT_INJECTION),
            "LTG" to Triple(4, EngineInductionType.TURBOCHARGED, EngineFuelSystemType.DIRECT_INJECTION),
            "LGX" to Triple(6, EngineInductionType.NATURALLY_ASPIRATED, EngineFuelSystemType.DIRECT_INJECTION),
            "LT1" to Triple(8, EngineInductionType.NATURALLY_ASPIRATED, EngineFuelSystemType.DIRECT_INJECTION),
            "LT4" to Triple(8, EngineInductionType.SUPERCHARGED, EngineFuelSystemType.DIRECT_INJECTION)
        )
        assertEquals(expected.keys, DtcCatalogRegistry.engineProfiles.keys)
        expected.forEach { (rpo, architecture) ->
            val profile = DtcCatalogRegistry.engineProfiles.getValue(rpo)
            assertEquals(architecture.first, profile.cylinderCount)
            assertEquals(architecture.second, profile.induction)
            assertEquals(architecture.third, profile.fuelSystem)
        }
    }

    @Test
    fun moduleInventoryIncludesCoreAndOptionalVehicleSystems() {
        val ids = DtcCatalogRegistry.moduleFamilies.map { it.id }.toSet()
        assertTrue(ids.containsAll(setOf("ECM", "PCM", "TCM", "EBCM", "BCM", "IPC", "SDM")))
        assertTrue(ids.containsAll(setOf("HVAC", "PSCM", "RCDLR", "RADIO", "AMP", "DDM", "PDM", "FPCM", "PARK_ASSIST", "HMI")))
    }

    @Test
    fun perModuleCatalogueNeverClaimsUnsupportedOemCoverage() {
        val lfx = VehicleProfile(modelYear = 2012, engine = CamaroEngine.LFX)
        val lt1 = VehicleProfile(modelYear = 2018, engine = CamaroEngine.LT1)

        val lfxEcm = DtcCatalogRegistry.catalogueFor("ECM", lfx)
        val lt1Ecm = DtcCatalogRegistry.catalogueFor("ECM", lt1)
        val lfxAbs = DtcCatalogRegistry.catalogueFor("EBCM", lfx)
        val unknown = DtcCatalogRegistry.catalogueFor("ECU-7E9", lfx)

        assertTrue(lfxEcm.hasVehicleSpecificDefinitions)
        assertEquals(DtcEvidenceStatus.DOCUMENTED, lfxEcm.evidenceStatus)
        assertFalse(lt1Ecm.hasVehicleSpecificDefinitions)
        assertEquals(DtcEvidenceStatus.GENERIC_ONLY, lt1Ecm.evidenceStatus)
        assertFalse(lfxAbs.hasVehicleSpecificDefinitions)
        assertEquals(DtcEvidenceStatus.GENERIC_ONLY, lfxAbs.evidenceStatus)
        assertEquals(DtcEvidenceStatus.DISCOVERY_REQUIRED, unknown.evidenceStatus)
    }

    @Test
    fun genericSaeDescriptionsAreAvailableWithoutClaimingOemSpecificity() {
        assertNotNull(DtcCatalogRegistry.genericDescription("P0101"))
        listOf("P0013", "P0017", "P0018", "P0019", "P0020", "P0021", "P0023", "P0024", "P0030", "P0036", "P0050").forEach { code ->
            assertNotNull("Missing generic SAE description for $code", DtcCatalogRegistry.genericDescription(code))
        }
        assertNotNull(DtcCatalogRegistry.genericDescription("P0308"))
        assertEquals(null, DtcCatalogRegistry.genericDescription("B1234"))
    }

    @Test
    fun applicabilityWarningsFollowTheSelectedEngineArchitecture() {
        val lfx = VehicleProfile(modelYear = 2012, engine = CamaroEngine.LFX)
        val lt1 = VehicleProfile(modelYear = 2018, engine = CamaroEngine.LT1)
        val ltg = VehicleProfile(modelYear = 2018, engine = CamaroEngine.LTG)

        assertNotNull(CamaroDtcCatalog.applicabilityNotice("P0308", lfx))
        assertEquals(null, CamaroDtcCatalog.applicabilityNotice("P0308", lt1))
        assertNotNull(CamaroDtcCatalog.applicabilityNotice("P0299", lfx))
        assertEquals(null, CamaroDtcCatalog.applicabilityNotice("P0299", ltg))
    }

    @Test
    fun gm2012LfxMonitorEvidenceIsRestrictedToItsDocumentedVehicleGroup() {
        val lfx2012 = VehicleProfile(modelYear = 2012, engine = CamaroEngine.LFX)
        val lfx2013 = VehicleProfile(modelYear = 2013, engine = CamaroEngine.LFX)
        val lt1 = VehicleProfile(modelYear = 2018, engine = CamaroEngine.LT1)

        val p0011 = Gm2012LfxMonitorCatalog.find("p0011", lfx2012)
        assertNotNull(p0011)
        assertEquals("P0011", p0011?.code)
        assertTrue(p0011?.malfunctionCriteriaEn?.contains("5.00 cam degrees") == true)
        assertEquals(null, Gm2012LfxMonitorCatalog.find("P0011", lfx2013))
        assertEquals(null, Gm2012LfxMonitorCatalog.find("P0011", lt1))
        assertEquals(14, Gm2012LfxMonitorCatalog.supportedCodes().size)
        assertNotNull(Gm2012LfxMonitorCatalog.find("P0300", lfx2012))
        assertNotNull(Gm2012LfxMonitorCatalog.find("P0420", lfx2012))
        assertTrue(Gm2012LfxMonitorCatalog.SOURCE_DOCUMENT.contains("12OBDG05B"))
    }

    @Test
    fun oemEvidenceCannotBeUsedWithoutExactVehicleAndCalibrationIdentity() {
        val evidence = DtcDefinitionEvidence(
            source = DtcDefinitionSource.GM_SERVICE_INFORMATION,
            status = DtcEvidenceStatus.DOCUMENTED,
            modelYears = 2012..2015,
            engineRpos = setOf("LFX"),
            transmission = TransmissionType.MANUAL,
            moduleId = "ECM",
            hardwarePartNumber = null,
            operatingSystemId = null,
            calibrationId = null,
            calibrationVerificationNumber = null,
            vin = null,
            reference = ""
        )
        val vehicle = VehicleProfile(modelYear = 2012, engine = CamaroEngine.LFX, vin = "1G1FB1E30C9123456")

        assertFalse(evidence.isUsableForOemInterpretation(vehicle, "ECM"))
        assertTrue(evidence.validationIssues(vehicle, "ECM").size >= 6)
    }
}
