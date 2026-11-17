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

        assertFalse(evidence.isUsableForOemInterpretation)
        assertTrue(evidence.validationIssues(vehicle, "ECM").size >= 6)
    }
}
