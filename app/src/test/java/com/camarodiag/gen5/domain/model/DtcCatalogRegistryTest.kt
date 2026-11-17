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
        assertEquals(2016..2023, CamaroEngine.LTG.years)
        assertEquals(2016..2023, DtcCatalogRegistry.engineProfiles.getValue("LTG").modelYears)
        assertEquals(CamaroEngine.LTG.years, DtcCatalogRegistry.engineProfile(CamaroEngine.LTG)?.modelYears)
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
        val l99_2012Ecm = DtcCatalogRegistry.catalogueFor(
            "ECM", VehicleProfile(modelYear = 2012, engine = CamaroEngine.L99)
        )
        val llt2010Ecm = DtcCatalogRegistry.catalogueFor(
            "ECM", VehicleProfile(modelYear = 2010, engine = CamaroEngine.LLT)
        )
        val lt4_2019Ecm = DtcCatalogRegistry.catalogueFor(
            "ECM", VehicleProfile(modelYear = 2019, engine = CamaroEngine.LT4)
        )
        val impossibleLsa2011Ecm = DtcCatalogRegistry.catalogueFor(
            "ECM", VehicleProfile(modelYear = 2011, engine = CamaroEngine.LSA)
        )
        val ls7_2014Ecm = DtcCatalogRegistry.catalogueFor(
            "ECM", VehicleProfile(modelYear = 2014, engine = CamaroEngine.LS7)
        )
        val lfxAbs = DtcCatalogRegistry.catalogueFor("EBCM", lfx)
        val unknown = DtcCatalogRegistry.catalogueFor("ECU 7E8", lfx)

        assertTrue(lfxEcm.hasVehicleSpecificDefinitions)
        assertEquals(DtcEvidenceStatus.DOCUMENTED, lfxEcm.evidenceStatus)
        assertTrue(l99_2012Ecm.hasVehicleSpecificDefinitions)
        assertTrue(llt2010Ecm.hasVehicleSpecificDefinitions)
        assertTrue(lt4_2019Ecm.hasVehicleSpecificDefinitions)
        assertFalse(impossibleLsa2011Ecm.hasVehicleSpecificDefinitions)
        assertFalse(ls7_2014Ecm.hasVehicleSpecificDefinitions)
        assertFalse(lt1Ecm.hasVehicleSpecificDefinitions)
        assertEquals(DtcEvidenceStatus.GENERIC_ONLY, lt1Ecm.evidenceStatus)
        assertFalse(lfxAbs.hasVehicleSpecificDefinitions)
        assertEquals(DtcEvidenceStatus.GENERIC_ONLY, lfxAbs.evidenceStatus)
        assertFalse(unknown.hasVehicleSpecificDefinitions)
        assertEquals(DtcEvidenceStatus.DISCOVERY_REQUIRED, unknown.evidenceStatus)
    }

    @Test
    fun genericSaeDescriptionsAreAvailableWithoutClaimingOemSpecificity() {
        assertNotNull(DtcCatalogRegistry.genericDescription("P0101"))
        listOf("P0008", "P0009", "P000A", "P000B", "P000C", "P000D", "P0013", "P0017", "P0018", "P0019", "P0020", "P0021", "P0023", "P0024", "P0030", "P0036", "P0050", "P0053", "P0054", "P0056", "P0059", "P0060").forEach { code ->
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
    fun gm2012V8MonitorDataSeparatesL99Ls3AndLsaHardware() {
        val l99 = VehicleProfile(modelYear = 2012, engine = CamaroEngine.L99)
        val ls3 = VehicleProfile(modelYear = 2012, engine = CamaroEngine.LS3)
        val lsa = VehicleProfile(modelYear = 2012, engine = CamaroEngine.LSA)

        assertNotNull(GmEngineMonitorCatalog.find("P0011", l99))
        assertEquals(null, GmEngineMonitorCatalog.find("P0011", ls3))
        assertEquals(null, GmEngineMonitorCatalog.find("P0011", lsa))
        assertNotNull(GmEngineMonitorCatalog.find("P0016", ls3))
        assertTrue(GmEngineMonitorCatalog.find("P0053", l99)?.malfunctionCriteriaEn?.contains("2.8") == true)
        assertTrue(GmEngineMonitorCatalog.find("P0054", l99)?.malfunctionCriteriaEn?.contains("4.1") == true)
        assertTrue(GmEngineMonitorCatalog.find("P0101", ls3)?.malfunctionCriteriaEn?.contains("12 g/s") == true)
        assertTrue(GmEngineMonitorCatalog.find("P0101", lsa)?.malfunctionCriteriaEn?.contains("21 g/s") == true)
        assertEquals("12OBDG07", GmEngineMonitorCatalog.sourceLabel(lsa))
        assertEquals(14, Gm2012L99MonitorCatalog.supportedCodes().size)
        assertEquals(10, Gm2012Ls3MonitorCatalog.supportedCodes().size)
        assertEquals(10, Gm2012LsaMonitorCatalog.supportedCodes().size)
        assertNotNull(CamaroDtcCatalog.find("P0056", l99))
        assertEquals(null, CamaroDtcCatalog.find("P0017", ls3))
        listOf(l99, ls3, lsa).forEach { profile ->
            assertTrue(CamaroDtcCatalog.coverageNotice(profile).first.contains("V8 Gen 5 2012"))
        }
    }

    @Test
    fun v8DtcAndMonitorCatalogsRespectL99VvtVersusLs3NonVvtArchitecture() {
        val l99_2010 = VehicleProfile(modelYear = 2010, engine = CamaroEngine.L99)
        val l99_2011 = VehicleProfile(modelYear = 2011, engine = CamaroEngine.L99)
        val ls3_2010 = VehicleProfile(modelYear = 2010, engine = CamaroEngine.LS3)
        val ls3_2011 = VehicleProfile(modelYear = 2011, engine = CamaroEngine.LS3)
        val l99_2012 = VehicleProfile(modelYear = 2012, engine = CamaroEngine.L99)

        listOf("P0010", "P0011", "P0013", "P0014", "P0016").forEach { code ->
            assertNotNull("Missing L99 interpretation for $code", CamaroDtcCatalog.find(code, l99_2010))
            assertNotNull("Missing L99 monitor for $code", GmEngineMonitorCatalog.find(code, l99_2010))
        }
        listOf("P0016").forEach { code ->
            assertNotNull("Missing LS3 correlation interpretation for $code", CamaroDtcCatalog.find(code, ls3_2010))
            assertNotNull("Missing LS3 correlation monitor for $code", GmEngineMonitorCatalog.find(code, ls3_2011))
        }
        listOf("P0010", "P0011", "P0013", "P0014").forEach { code ->
            assertEquals(null, CamaroDtcCatalog.find(code, ls3_2010))
            assertEquals(null, GmEngineMonitorCatalog.find(code, ls3_2011))
        }
        assertEquals("10OBDG12", GmEngineMonitorCatalog.sourceLabel(l99_2010))
        assertEquals("11OBDG07", GmEngineMonitorCatalog.sourceLabel(ls3_2011))
        assertNotNull("2012 L99 retains its VVT monitor", GmEngineMonitorCatalog.find("P0011", l99_2012))
        assertEquals(5, GmL99MonitorCatalog.supportedCodes().size)
        assertEquals(1, GmLs3MonitorCatalog.supportedCodes().size)
    }

    @Test
    fun gm2014And2015V8SourceGroupsIncludeLs7WithoutInventingMonitorData() {
        val v8Engines = listOf(CamaroEngine.L99, CamaroEngine.LS3, CamaroEngine.LSA, CamaroEngine.LS7)
        listOf(CamaroEngine.L99, CamaroEngine.LS3, CamaroEngine.LSA).forEach { engine ->
            assertEquals(
                "13OBDG07",
                GmEngineMonitorCatalog.sourceLabel(VehicleProfile(modelYear = 2013, engine = engine))
            )
        }

        v8Engines.forEach { engine ->
            assertEquals(
                "14OBDG07",
                GmEngineMonitorCatalog.sourceLabel(VehicleProfile(modelYear = 2014, engine = engine))
            )
            assertEquals(
                "15OBDG07",
                GmEngineMonitorCatalog.sourceLabel(VehicleProfile(modelYear = 2015, engine = engine))
            )
        }

        // The group summary establishes the certification file association,
        // but no LS7-specific monitor evidence has been integrated yet.
        assertEquals(
            null,
            GmEngineMonitorCatalog.find("P0016", VehicleProfile(modelYear = 2014, engine = CamaroEngine.LS7))
        )
        assertEquals(
            null,
            GmEngineMonitorCatalog.find("P0016", VehicleProfile(modelYear = 2015, engine = CamaroEngine.LS7))
        )
    }

    @Test
    fun gen5V8CertificationProvenanceIsVisibleWithoutInventingMonitorDefinitions() {
        val ls7 = VehicleProfile(modelYear = 2015, engine = CamaroEngine.LS7)
        val lsa = VehicleProfile(modelYear = 2014, engine = CamaroEngine.LSA)

        assertEquals("15OBDG07", GmEngineMonitorCatalog.sourceLabel(ls7))
        assertEquals("14OBDG07", GmEngineMonitorCatalog.sourceLabel(lsa))
        assertTrue(CamaroDtcCatalog.coverageNotice(ls7).first.contains("15OBDG07"))
        assertTrue(CamaroDtcCatalog.coverageNotice(lsa).first.contains("14OBDG07"))
        assertEquals(null, GmEngineMonitorCatalog.find("P0016", ls7))
        assertEquals(null, GmEngineMonitorCatalog.find("P0016", lsa))
    }

    @Test
    fun gen6CertificationSourcesAreYearAndEngineSpecific() {
        assertEquals(
            "16OBDG03",
            GmEngineMonitorCatalog.sourceLabel(VehicleProfile(modelYear = 2016, engine = CamaroEngine.LTG))
        )
        assertEquals(
            "16OBDG05",
            GmEngineMonitorCatalog.sourceLabel(VehicleProfile(modelYear = 2016, engine = CamaroEngine.LGX))
        )
        assertEquals(
            "16OBDG07A",
            GmEngineMonitorCatalog.sourceLabel(VehicleProfile(modelYear = 2016, engine = CamaroEngine.LT1))
        )
        assertEquals(null, GmEngineMonitorCatalog.sourceLabel(VehicleProfile(modelYear = 2016, engine = CamaroEngine.LT4)))

        listOf(CamaroEngine.LTG, CamaroEngine.LGX, CamaroEngine.LT1, CamaroEngine.LT4).forEach { engine ->
            assertEquals(
                "17OBDG03",
                GmEngineMonitorCatalog.sourceLabel(VehicleProfile(modelYear = 2017, engine = engine))
            )
            assertEquals(
                "18OBDG03A",
                GmEngineMonitorCatalog.sourceLabel(VehicleProfile(modelYear = 2018, engine = engine))
            )
            assertEquals(
                if (engine == CamaroEngine.LTG || engine == CamaroEngine.LGX) "19OBDG03B" else "19OBDG03A",
                GmEngineMonitorCatalog.sourceLabel(VehicleProfile(modelYear = 2019, engine = engine))
            )
            assertEquals(
                "20OBDG03A",
                GmEngineMonitorCatalog.sourceLabel(VehicleProfile(modelYear = 2020, engine = engine))
            )
        }
        assertEquals(
            "21OBDG03A",
            GmEngineMonitorCatalog.sourceLabel(VehicleProfile(modelYear = 2021, engine = CamaroEngine.LTG))
        )
        listOf(CamaroEngine.LGX, CamaroEngine.LT1, CamaroEngine.LT4).forEach { engine ->
            assertEquals(
                "21OBDG03B",
                GmEngineMonitorCatalog.sourceLabel(VehicleProfile(modelYear = 2021, engine = engine))
            )
        }

        assertEquals(
            "22OBDG03A",
            GmEngineMonitorCatalog.sourceLabel(VehicleProfile(modelYear = 2022, engine = CamaroEngine.LTG))
        )
        listOf(CamaroEngine.LGX, CamaroEngine.LT1, CamaroEngine.LT4).forEach { engine ->
            assertEquals(
                "22OBDG03B",
                GmEngineMonitorCatalog.sourceLabel(VehicleProfile(modelYear = 2022, engine = engine))
            )
            assertEquals(
                "23OBDG03B",
                GmEngineMonitorCatalog.sourceLabel(VehicleProfile(modelYear = 2023, engine = engine))
            )
            assertEquals(
                "24OBDG03B Part 2",
                GmEngineMonitorCatalog.sourceLabel(VehicleProfile(modelYear = 2024, engine = engine))
            )
        }
        assertEquals(
            "23OBDG03A Part2",
            GmEngineMonitorCatalog.sourceLabel(VehicleProfile(modelYear = 2023, engine = CamaroEngine.LTG))
        )
        assertEquals(null, GmEngineMonitorCatalog.sourceLabel(VehicleProfile(modelYear = 2024, engine = CamaroEngine.LTG)))
        assertTrue(
            CamaroDtcCatalog.coverageNotice(VehicleProfile(modelYear = 2024, engine = CamaroEngine.LT1))
                .first.contains("24OBDG03B Part 2")
        )

        // Group provenance is available; detailed Gen6 monitor evidence is not
        // yet integrated and must not be inferred from a certification label.
        assertEquals(
            null,
            GmEngineMonitorCatalog.find("P0011", VehicleProfile(modelYear = 2017, engine = CamaroEngine.LTG))
        )
    }

    @Test
    fun gm2019Lt4MonitorEvidenceIsRestrictedToTheDocumentedCamaroProfile() {
        val lt4_2019 = VehicleProfile(modelYear = 2019, engine = CamaroEngine.LT4)
        val lt1_2019 = VehicleProfile(modelYear = 2019, engine = CamaroEngine.LT1)
        val lt4_2020 = VehicleProfile(modelYear = 2020, engine = CamaroEngine.LT4)
        val lt1_2016 = VehicleProfile(modelYear = 2016, engine = CamaroEngine.LT1)
        val lt4_2024 = VehicleProfile(modelYear = 2024, engine = CamaroEngine.LT4)

        val performance = assertNotNull(GmEngineMonitorCatalog.find("P0196", lt4_2019))
        val low = assertNotNull(GmEngineMonitorCatalog.find("P0197", lt4_2019))
        val high = assertNotNull(GmEngineMonitorCatalog.find("P0198", lt4_2019))

        assertTrue(performance.strategyEn.contains("three independent tests"))
        assertTrue(performance.failureCriteriaEn.contains("210 failures out of 300"))
        assertEquals(603, performance.sourcePage)
        assertTrue(low.malfunctionCriteriaEn.contains("below 25"))
        assertTrue(high.malfunctionCriteriaEn.contains("above 450,000"))
        assertTrue(low.failureCriteriaEn.contains("20 failures out of 50"))
        assertTrue(high.enableConditionsEn.contains("above 20.0 seconds"))
        assertEquals(607, low.sourcePage)
        assertEquals(608, high.sourcePage)
        assertEquals("Type B – 2 trips", low.milClassification)
        assertEquals("Type B – 2 trips", high.milClassification)
        assertEquals(3, Gm2019Lt4MonitorCatalog.supportedCodes().size)
        assertTrue(CamaroDtcCatalog.coverageNotice(lt4_2019).first.contains("P0196"))
        assertTrue(CamaroDtcCatalog.coverageNotice(lt4_2019).second.contains("P0198"))
        assertEquals(null, GmEngineMonitorCatalog.find("P0197", lt1_2019))
        assertEquals(null, GmEngineMonitorCatalog.find("P0197", lt4_2020))
        assertEquals(null, GmEngineMonitorCatalog.find("P0196", lt1_2016))
        assertEquals(null, GmEngineMonitorCatalog.find("P0196", lt4_2024))
        assertEquals(null, GmEngineMonitorCatalog.find("P0011", lt4_2019))
    }

    @Test
    fun lltDtcCatalogProvidesBilingualVehicleSpecificInterpretations() {
        val llt2010 = VehicleProfile(modelYear = 2010, engine = CamaroEngine.LLT)
        val llt2011 = VehicleProfile(modelYear = 2011, engine = CamaroEngine.LLT)
        val lfx2012 = VehicleProfile(modelYear = 2012, engine = CamaroEngine.LFX)
        val codes = listOf("P0008", "P0009", "P000A", "P000B", "P000C", "P000D", "P0010", "P0011", "P0013", "P0014", "P0016", "P0017", "P0018", "P0019", "P0020", "P0021", "P0023", "P0024")

        codes.forEach { code ->
            assertNotNull("Missing LLT explanation for $code", CamaroDtcCatalog.find(code, llt2010))
            assertNotNull("Missing LLT explanation for $code", CamaroDtcCatalog.find(code, llt2011))
        }
        assertEquals(18, CamaroLltDtcCatalog.supportedCodes().size)
        assertTrue(CamaroDtcCatalog.coverageNotice(llt2010).first.contains("LLT"))
        assertNotNull(CamaroDtcCatalog.find("P0010", lfx2012))
    }

    @Test
    fun gm2010And2011LltMonitorsUseYearSpecificCalibrationEvidence() {
        val llt2010 = VehicleProfile(modelYear = 2010, engine = CamaroEngine.LLT)
        val llt2011 = VehicleProfile(modelYear = 2011, engine = CamaroEngine.LLT)
        val llt2012 = VehicleProfile(modelYear = 2012, engine = CamaroEngine.LLT)

        assertTrue(GmEngineMonitorCatalog.find("P0011", llt2010)?.malfunctionCriteriaEn?.contains("7.0–8.0") == true)
        assertTrue(GmEngineMonitorCatalog.find("P0011", llt2011)?.malfunctionCriteriaEn?.contains("6.0–11.0") == true)
        assertTrue(GmEngineMonitorCatalog.find("P000A", llt2010)?.malfunctionCriteriaEn?.contains("1.5") == true)
        assertTrue(GmEngineMonitorCatalog.find("P000A", llt2011)?.malfunctionCriteriaEn?.contains("2.5") == true)
        assertTrue(GmEngineMonitorCatalog.find("P0016", llt2010)?.malfunctionCriteriaEn?.contains("9.9") == true)
        assertTrue(GmEngineMonitorCatalog.find("P0016", llt2011)?.malfunctionCriteriaEn?.contains("11.7") == true)
        assertEquals("10OBDG02", GmEngineMonitorCatalog.sourceLabel(llt2010))
        assertEquals("11OBDG06", GmEngineMonitorCatalog.sourceLabel(llt2011))
        assertEquals(null, GmEngineMonitorCatalog.find("P0011", llt2012))
        assertEquals(18, Gm2010LltMonitorCatalog.supportedCodes().size)
        assertEquals(18, Gm2011LltMonitorCatalog.supportedCodes().size)
    }

    @Test
    fun gm2013And2014LfxCamshaftMonitorsUseTheirOwnCertificationThresholds() {
        val lfx2013 = VehicleProfile(modelYear = 2013, engine = CamaroEngine.LFX)
        val lfx2014 = VehicleProfile(modelYear = 2014, engine = CamaroEngine.LFX)
        val lfx2015 = VehicleProfile(modelYear = 2015, engine = CamaroEngine.LFX)

        val intake2013 = GmLfxMonitorCatalog.find("P0011", lfx2013)
        assertNotNull(intake2013)
        assertTrue(intake2013?.failureCriteriaEn?.contains("50 failures in 150 samples") == true)
        assertTrue(GmLfxMonitorCatalog.find("P0014", lfx2014)?.failureCriteriaEn?.contains("100 failures in 300 samples") == true)
        assertTrue(GmLfxMonitorCatalog.find("P0016", lfx2013)?.malfunctionCriteriaEn?.contains("6.0 crank degrees") == true)
        assertEquals("13OBDG05A", GmLfxMonitorCatalog.sourceLabel(lfx2013))
        assertEquals("14OBDG06A", GmLfxMonitorCatalog.sourceLabel(lfx2014))
        assertEquals("15OBDG06", GmLfxMonitorCatalog.sourceLabel(lfx2015))
        assertTrue(CamaroDtcCatalog.coverageNotice(lfx2015).first.contains("15OBDG06"))
        assertEquals(null, GmLfxMonitorCatalog.find("P0011", lfx2015))
        assertEquals(12, Gm2013And2014LfxMonitorCatalog.supportedCodes().size)
    }

    @Test
    fun detailedLfxCamshaftInterpretationsCoverAllFourActuatorCircuitsAndBothPerformanceBanks() {
        val lfx = VehicleProfile(modelYear = 2012, engine = CamaroEngine.LFX)
        listOf("P0010", "P0011", "P0013", "P0014", "P0020", "P0021", "P0023", "P0024").forEach { code ->
            assertNotNull("Missing LFX explanation for $code", CamaroDtcCatalog.find(code, lfx))
        }
        assertEquals(null, CamaroDtcCatalog.find("P0021", VehicleProfile(modelYear = 2018, engine = CamaroEngine.LT1)))
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
        assertEquals(32, Gm2012LfxMonitorCatalog.supportedCodes().size)
        listOf("P0089", "P0191", "P00C6").forEach { code ->
            assertNotNull("Missing GM monitor evidence for $code", Gm2012LfxMonitorCatalog.find(code, lfx2012))
        }
        assertNotNull(CamaroDtcCatalog.find("P00C6", lfx2012))
        assertEquals(null, CamaroDtcCatalog.find("P00C6", lfx2013))
        listOf("P0101", "P0102", "P0103", "P0128", "P0171", "P0172", "P0174", "P0175").forEach { code ->
            assertNotNull("Missing GM monitor evidence for $code", Gm2012LfxMonitorCatalog.find(code, lfx2012))
            assertNotNull("Missing LFX explanation for $code", CamaroDtcCatalog.find(code, lfx2012))
        }
        listOf("P0030", "P0036", "P0050", "P0053", "P0054", "P0059", "P0060").forEach { code ->
            assertNotNull("Missing GM monitor evidence for $code", Gm2012LfxMonitorCatalog.find(code, lfx2012))
            assertNotNull("Missing LFX explanation for $code", CamaroDtcCatalog.find(code, lfx2012))
        }
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
