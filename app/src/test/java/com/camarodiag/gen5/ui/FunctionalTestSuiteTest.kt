package com.camarodiag.gen5.ui

import com.camarodiag.gen5.data.obd.CamaroUdsProtocol
import com.camarodiag.gen5.domain.model.AdapterCapabilities
import com.camarodiag.gen5.domain.model.VehicleProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FunctionalTestSuiteTest {
    private val modules = CamaroUdsProtocol.buildTopology(VehicleProfile(), AdapterCapabilities())

    @Test fun protocolCampaignsHaveIndependentStableIdentifiers() {
        assertEquals(FunctionalProtocol.entries.size, FunctionalProtocol.entries.map { it.id }.distinct().size)
        assertTrue(FunctionalProtocol.J1979.implemented)
        assertTrue(FunctionalProtocol.DOIP.implemented)
        assertTrue(FunctionalTestPlanner.j1979Checks().all { it.outcome == FunctionalTestOutcome.NOT_TESTED })
        assertTrue(FunctionalTestPlanner.j1979Checks().any { it.key == "protocol:04" })
    }

    @Test fun j1979RequiresExplicitInventoryAndDtcEvidence() {
        val state = FunctionalTestState(protocol = FunctionalProtocol.J1979,
            checks = FunctionalTestPlanner.j1979Checks())
        val report = com.camarodiag.gen5.data.obd.ObdScanReport(
            supportedPids = emptySet(), liveData = emptyList(), confirmedDtcs = emptyList(),
            pendingDtcs = emptyList(), permanentDtcs = emptyList(), vin = null,
            calibrationIds = emptyList(), calibrationVerificationNumbers = emptyList(),
            ecuNames = emptyList(), readiness = null, freezeFrame = emptyMap(), monitorTestRaw = null,
            adapterInfo = emptyMap(), respondingEcuAddresses = listOf("7E8"),
            rawLog = emptyList(), physicalCanResponder = "7E8")
        fun outcome(snapshot: FunctionalTestState, key: String) =
            snapshot.checks.single { it.key == key }.outcome
        assertEquals(setOf(3,7,10),report.unverifiedDtcModes)
        assertEquals(setOf(7,10),report.copy(verifiedDtcModes=setOf(3)).unverifiedDtcModes)
        assertEquals(setOf(3),report.copy(verifiedDtcModes=setOf(3,7,10),
            unavailableDtcModes=setOf(3)).unverifiedDtcModes)
        assertTrue(report.copy(verifiedDtcModes=setOf(3,7,10)).unverifiedDtcModes.isEmpty())
        val missing = FunctionalTestPlanner.j1979Results(state,report)
        assertEquals(FunctionalTestOutcome.NOT_TESTED,outcome(missing,"protocol:transport"))
        assertEquals(FunctionalTestOutcome.NOT_TESTED,outcome(missing,"protocol:pids"))
        assertEquals(FunctionalTestOutcome.NOT_TESTED,outcome(missing,"service:dtc-mode:03"))
        val empty = FunctionalTestPlanner.j1979Results(state,report.copy(
            pidInventoryComplete=true,verifiedDtcModes=setOf(3)))
        assertEquals(FunctionalTestOutcome.PASSED,outcome(empty,"protocol:transport"))
        assertEquals(FunctionalTestOutcome.PASSED,outcome(empty,"protocol:pids"))
        assertEquals(FunctionalTestOutcome.NOT_TESTED,outcome(empty,"protocol:live"))
        assertEquals(FunctionalTestOutcome.PASSED,outcome(empty,"service:dtc-mode:03"))
        assertEquals(FunctionalTestOutcome.NOT_TESTED,outcome(empty,"service:dtc-mode:07"))
        val unresolved = FunctionalTestPlanner.j1979Results(state,report.copy(
            pidInventoryComplete=false,firstUnresolvedPid=0xE1))
        assertEquals(FunctionalTestOutcome.NOT_TESTED,outcome(unresolved,"protocol:pids"))
        val noInitial = FunctionalTestPlanner.j1979Results(state,report.copy(
            pidInventoryComplete=false,firstUnresolvedPid=1))
        assertEquals(FunctionalTestOutcome.NOT_TESTED,outcome(noInitial,"protocol:transport"))
    }

    @Test fun emptyMonitorInventoryIsNotAMonitorResult() {
        val evidence = FunctionalTestPlanner::monitorEvidence
        assertEquals(true to false, evidence("DISCOVERED_MIDS=0"))
        assertEquals(true to false, evidence("DISCOVERED_TIDS=0"))
        assertEquals(true to true, evidence("MID=01 TID=01 UASID=24 RAW=0001 MIN_RAW=0000 MAX_RAW=FFFF"))
        assertEquals(true to true, evidence("TID=01 CID=01 LIMIT_TYPE=MAX RAW=0001 LIMIT_RAW=FFFF"))
        assertEquals(false to false, evidence("MID=01: N/A"))
        assertEquals(false to false, evidence("DISCOVERED_MIDS=0\nPARTIAL"))
        assertEquals(false to false, evidence("OK"))
        assertEquals(false to false, evidence(null))
    }

    @Test fun legacyRecipesAreDistinctFromGenericTransportProtocols() {
        val variants = FunctionalProtocol.entries.filter { it.expectedLegacyElmProtocol != null }
        assertEquals((1..5).toList(), variants.mapNotNull { it.expectedLegacyElmProtocol }.sorted())
        assertTrue(variants.all { it.implemented && it.isJ1979Recipe })
        assertEquals(5, variants.map { it.id }.distinct().size)
        assertFalse(FunctionalProtocol.KWP.implemented)
        assertFalse(FunctionalProtocol.VPW.implemented)
        assertFalse(FunctionalProtocol.PWM.implemented)
        assertFalse(FunctionalProtocol.ISO9141.implemented)
        assertTrue(FunctionalTestPlanner.j1979Checks().any { it.key == "protocol:transport" })
    }

    @Test fun legacyAckDoesNotProveMissingPendingCodesOrEvapCompletion() {
        val target = com.camarodiag.gen5.data.obd.LegacyMutationTarget(2,0x10,listOf(0x10,0x11),true)
        val state = FunctionalTestState(legacyTarget=target,checks=FunctionalTestPlanner.legacyMutationChecks())
        val result = com.camarodiag.gen5.data.obd.LegacyMutationResult(target,"ACKNOWLEDGED_PARTIAL",
            "PENDING_READBACK_UNAVAILABLE",true,"1G1FA1RXXR0123456",
            mapOf("10" to "ACKNOWLEDGED","11" to "ACKNOWLEDGED"),emptyMap(),
            mapOf("10" to mapOf("03" to emptyList<String>(),"07" to null),
                "11" to mapOf("03" to emptyList<String>(),"07" to emptyList<String>())),emptyList())
        fun status(snapshot: FunctionalTestState,key: String) =
            snapshot.checks.single { it.key == "legacy:$key" }.outcome
        val partial = FunctionalTestPlanner.legacyMutationResults(state,result,true)
        assertEquals(FunctionalTestOutcome.PASSED,status(partial,"ack"))
        assertEquals(FunctionalTestOutcome.PASSED,status(partial,"baseline"))
        assertEquals(FunctionalTestOutcome.NOT_TESTED,status(partial,"remaining"))
        val evap = FunctionalTestPlanner.legacyMutationResults(state,result.copy(
            target=target.copy(clear=false),outcome="ACKNOWLEDGED",reason="LEAK_TEST_NOT_PERFORMED"),true)
        assertEquals(FunctionalTestOutcome.NOT_TESTED,status(evap,"evap"))
        assertEquals(FunctionalTestOutcome.NOT_TESTED,status(evap,"remaining"))
    }

    @Test fun resetAcknowledgmentDoesNotProveAllFaultsAbsent() {
        val vin = "1G1FA1RXXR0123456"
        val state = FunctionalTestState(clearCampaign = true,
            checks = FunctionalTestPlanner.clearChecks(),
            context = FunctionalTestContext(vin, "ELM", "USB", 1))
        val result = com.camarodiag.gen5.data.obd.ObdClearRecipeResult(
            "ACKNOWLEDGED_PARTIAL", true, true, vin, "READBACK_UNAVAILABLE",
            emptyList(), mapOf("03" to listOf("P0123")),
            mapOf("03" to null, "07" to emptyList(), "0A" to listOf("P0123")),
            6, "7E0", "7E8")
        fun outcome(snapshot: FunctionalTestState, key: String) =
            snapshot.checks.single { it.key == "protocol:04-$key" }.outcome
        val partial = FunctionalTestPlanner.clearResults(state, result)
        assertEquals(FunctionalTestOutcome.PASSED, outcome(partial, "ack"))
        assertEquals(FunctionalTestOutcome.NOT_TESTED, outcome(partial, "readback"))
        assertEquals(FunctionalTestOutcome.NOT_TESTED, outcome(partial, "remaining"))
        assertEquals(FunctionalTestOutcome.PASSED, outcome(partial, "permanent"))
        val residual = FunctionalTestPlanner.clearResults(state, result.copy(
            outcome = "ACKNOWLEDGED_READBACK", reason = "DTCS_STILL_PRESENT",
            afterDtcs = mapOf("03" to listOf("P0123"), "07" to emptyList(), "0A" to listOf("P0123"))))
        assertEquals(FunctionalTestOutcome.PASSED, outcome(residual, "readback"))
        assertEquals(FunctionalTestOutcome.FAILED, outcome(residual, "remaining"))
        val zero = FunctionalTestPlanner.clearResults(state, result.copy(
            outcome = "ACKNOWLEDGED_READBACK", reason = null,
            afterDtcs = mapOf("03" to emptyList(), "07" to emptyList(), "0A" to listOf("P0123"))))
        assertEquals(FunctionalTestOutcome.PASSED, outcome(zero, "remaining"))
    }

    @Test fun evapAcknowledgmentNeverPassesMonitorCompletion() {
        val vin = "1G1FA1RXXR0123456"
        val state = FunctionalTestState(evapCampaign = true,
            checks = FunctionalTestPlanner.evapChecks(),
            context = FunctionalTestContext(vin, "ELM", "USB", 1))
        val result = com.camarodiag.gen5.data.obd.ObdEvapResult("ACKNOWLEDGED", true, vin,
            null, listOf(com.camarodiag.gen5.data.obd.ObdEvapExchange("0801", "4801")), 6, "7E0", "7E8")
        val updated = FunctionalTestPlanner.evapResults(state, result)
        assertEquals(FunctionalTestOutcome.PASSED, updated.checks.first().outcome)
        assertEquals(FunctionalTestOutcome.NOT_TESTED, updated.checks.last().outcome)
        assertEquals(vin, updated.context?.observedVin)
        assertEquals(listOf("> 0801 -> 4801"), updated.rawObdExchanges)
        val uncertain = FunctionalTestPlanner.evapResults(state,
            result.copy(outcome = "UNCERTAIN", reason = "TIMEOUT"))
        assertEquals(FunctionalTestOutcome.FAILED, uncertain.checks.first().outcome)
        assertEquals(FunctionalTestOutcome.NOT_TESTED, uncertain.checks.last().outcome)
    }

    @Test fun doIpCampaignRequiresExplicitIdentityAndPhysicalConfirmation() {
        val target = DoIpFunctionalTarget("192.0.2.10", "0E80", "1234", "1G1FA1RXXR0123456", true, true)
        assertTrue(target.valid)
        assertTrue(!target.copy(physicalVehicleConfirmed = false).valid)
        assertTrue(!target.copy(vin = "").valid)
        assertTrue(!target.copy(ecu = target.tester).valid)
        assertTrue(FunctionalTestPlanner.doIpChecks().all { it.outcome == FunctionalTestOutcome.NOT_TESTED })
        assertTrue(FunctionalTestPlanner.doIpChecks().any { it.key == "protocol:write" })
    }

    @Test fun allKnownTransportsAndEveryModuleAreListed() {
        val rows = FunctionalTestPlanner.initial(modules, "USB", physicalConnection = true)
        assertEquals(5, rows.count { it.group == "connector" })
        assertEquals(modules.size, rows.count { it.group == "ecu" })
        assertEquals(4, rows.count { it.group == "service" })
        assertEquals(1, rows.count { it.group == "connector" && it.outcome == FunctionalTestOutcome.PASSED })
        assertTrue(rows.filter { it.group == "ecu" }.all { it.outcome == FunctionalTestOutcome.NOT_TESTED })
    }

    @Test fun demoOrOfflineCannotClaimPass() {
        for (kind in listOf("BLUETOOTH", "BLE", "USB", "WIFI", "J2534_BRIDGE")) {
            val rows = FunctionalTestPlanner.initial(modules, kind, physicalConnection = false)
            assertTrue(rows.all { it.outcome == FunctionalTestOutcome.NOT_TESTED })
        }
    }

    @Test fun exportRequiresCompletedRunWithCapturedEvidenceContext() {
        val context = FunctionalTestContext("1G1FA1RXXR0123456","Test adapter","J2534_BRIDGE",42)
        val rows = FunctionalTestPlanner.initial(modules,"J2534_BRIDGE",true)
        val started = FunctionalTestState(running = true, startedAtMillis = 1,
            checks = rows, context = context)
        assertTrue(!started.canExport)
        val completed = started.copy(running = false, finishedAtMillis = 2)
        assertTrue(completed.canExport)
        assertEquals(context,completed.context)
        assertTrue(!completed.copy(context = null).canExport)
        assertTrue(!completed.copy(checks = emptyList()).canExport)
        assertTrue(!completed.copy(finishedAtMillis = null).canExport)
        // Interrupted evidence may be exported, but does not become a PASS.
        val interrupted = completed.copy(error = "Disconnected")
        assertTrue(interrupted.canExport)
        assertTrue(interrupted.checks.filter { it.group == "ecu" }
            .all { it.outcome == FunctionalTestOutcome.NOT_TESTED })
    }

    @Test fun genericObdResponderDoesNotProveCamaroEcu() {
        val rows = FunctionalTestPlanner.initial(emptyList(), "BLUETOOTH", true)
        val observed = FunctionalTestPlanner.withStandardResponders(rows, listOf("7E8", "7E8", "18DAF110"))
        assertEquals(2, observed.count { it.group == "obd_responder" && it.outcome == FunctionalTestOutcome.PASSED })
        assertTrue(observed.none { it.group == "ecu" })
        val checked = FunctionalTestPlanner.withDtcModeEvidence(observed, mapOf(3 to 0, 7 to null, 10 to 2))
        assertEquals(FunctionalTestOutcome.PASSED, checked.first { it.key == "service:dtc-mode:03" }.outcome)
        assertEquals(FunctionalTestOutcome.NOT_TESTED, checked.first { it.key == "service:dtc-mode:07" }.outcome)
        assertEquals(FunctionalTestOutcome.PASSED, checked.first { it.key == "service:dtc-mode:0A" }.outcome)
    }

    @Test fun physicalRespondersAreShownOnlyForCurrentCompletedTest() {
        val selected = ""
        val state = FunctionalTestState(
            context = FunctionalTestContext(selected, "OBDII", "BLUETOOTH", 9),
            finishedAtMillis = 2,
            checks = FunctionalTestPlanner.withStandardResponders(emptyList(),
                listOf("7E8", "7EA", "7EC", "7EE", "7EF", "7E8"))
        )
        assertEquals(listOf("7E8", "7EA", "7EC", "7EE", "7EF"),
            FunctionalTestPlanner.currentResponders(state, 9, selected))
        assertTrue(FunctionalTestPlanner.currentResponders(state, 10, selected).isEmpty())
        assertTrue(FunctionalTestPlanner.currentResponders(state, 9, "different").isEmpty())
        assertTrue(FunctionalTestPlanner.currentResponders(state.copy(running = true), 9, selected).isEmpty())
        assertTrue(FunctionalTestPlanner.currentResponders(state.copy(error = "Interrupted"), 9, selected).isEmpty())
    }

    @Test fun foreignVinIsNotCamaroCandidate() {
        val matcher = com.camarodiag.gen5.domain.model.VehicleVinMatcher
        assertTrue(matcher.isCamaroCandidate("2G1FF3D30C9000001"))
        assertTrue(matcher.isCamaroCandidate("1G1FA1RXXR0123456"))
        assertTrue(!matcher.isCamaroCandidate("3FMTK3SU0PMA00001"))
        assertTrue(!matcher.isCamaroCandidate(null))
        val context = FunctionalTestContext("", "OBDII", "BLUETOOTH", 3, observedVin = "3FMTK3SU0PMA00001")
        assertEquals("", context.selectedVin)
        assertEquals("3FMTK3SU0PMA00001", context.observedVin)
    }

    @Test fun physicalVinAndProfileMatchAreIndependentForEveryTransport() {
        val camaro = "2G1FF3D30C9000001"
        val ford = "3FMTK3SU0PMA00001"
        for (transport in listOf("BLUETOOTH", "J2534_BRIDGE")) {
            val state = FunctionalTestState(
                context = FunctionalTestContext(camaro, "Adapter", transport, 1),
                checks = FunctionalTestPlanner.initial(modules, transport, true)
            )
            val foreign = FunctionalTestPlanner.withObservedIdentity(state, ford)
            assertEquals(ford, foreign.context?.observedVin)
            assertTrue(foreign.checks.none { it.group == "ecu" })
            assertEquals(FunctionalTestOutcome.FAILED,
                foreign.checks.first { it.key == "service:vin-profile-match" }.outcome)
            val missing = FunctionalTestPlanner.withObservedIdentity(state, null)
            assertTrue(missing.checks.none { it.group == "ecu" })
            assertEquals(FunctionalTestOutcome.NOT_TESTED,
                missing.checks.first { it.key == "service:vin-profile-match" }.outcome)
            val matched = FunctionalTestPlanner.withObservedIdentity(state, camaro)
            assertEquals(modules.size, matched.checks.count { it.group == "ecu" })
            assertEquals(FunctionalTestOutcome.PASSED,
                matched.checks.first { it.key == "service:vin-profile-match" }.outcome)
            val unselected = FunctionalTestPlanner.withObservedIdentity(
                state.copy(context = state.context?.copy(selectedVin = "")), ford)
            assertTrue(unselected.checks.none { it.group == "ecu" || it.key == "service:vin-profile-match" })
            assertEquals(ford, unselected.context?.observedVin)
        }
    }

    @Test fun reportKeepsBoundedActualObdEvidenceAcrossCheckUpdates() {
        val initial = FunctionalTestState()
        val evidence = FunctionalTestPlanner.appendRawObdExchanges(initial,
            listOf("> 03 -> NO DATA", "> 07 -> 47 00"))
        assertEquals(listOf("> 03 -> NO DATA", "> 07 -> 47 00"), evidence.rawObdExchanges)
        val limited = FunctionalTestPlanner.appendRawObdExchanges(evidence,
            List(200) { "A".repeat(9000) })
        assertEquals(128, limited.rawObdExchanges.size)
        assertTrue(limited.rawObdExchanges.all { it.length <= 8192 })
        assertEquals(evidence.rawObdExchanges, evidence.copy(running = false).rawObdExchanges)
    }

    @Test fun updateChangesOnlyTheTarget() {
        val rows = FunctionalTestPlanner.initial(modules, "BLE", true)
        val updated = FunctionalTestPlanner.updated(
            rows, "module:ECM", FunctionalTestOutcome.FAILED, "No response", "Sans réponse"
        )
        assertEquals(FunctionalTestOutcome.FAILED, updated.first { it.key == "module:ECM" }.outcome)
        assertEquals(FunctionalTestOutcome.PASSED, updated.first { it.key == "connector:BLE" }.outcome)
    }
}