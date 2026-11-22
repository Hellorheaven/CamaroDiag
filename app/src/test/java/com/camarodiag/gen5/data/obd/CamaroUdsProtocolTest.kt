package com.camarodiag.gen5.data.obd

import com.camarodiag.gen5.domain.model.AdapterCapabilities
import com.camarodiag.gen5.domain.model.AdapterTier
import com.camarodiag.gen5.domain.model.CamaroEngine
import com.camarodiag.gen5.domain.model.ModuleStatus
import com.camarodiag.gen5.domain.model.VehicleProfile
import com.camarodiag.gen5.domain.model.TransmissionType
import org.junit.Assert.*
import org.junit.Test

class CamaroUdsProtocolTest {
    @Test
    fun defaultVehicleProfileMatchesConfigured2012LfxManualCamaro() {
        val vehicle = VehicleProfile()

        assertEquals(2012, vehicle.modelYear)
        assertEquals(CamaroEngine.LFX, vehicle.engine)
        assertEquals(TransmissionType.MANUAL, vehicle.transmission)
    }

    @Test
    fun parsesStandardLivePid() {
        assertArrayEquals(intArrayOf(0x1A, 0xF8), CamaroUdsProtocol.parsePidBytes("41 0C 1A F8", "0C"))
    }

    @Test
    fun parsesOdometerPidsWithTenthKilometreResolution() {
        val pidA6 = requireNotNull(CamaroUdsProtocol.parseOdometerPidA6("41 A6 00 12 D6 87"))
        val pidD3 = requireNotNull(CamaroUdsProtocol.parseOdometerPidD3("41 D3 00 12 D6 87"))

        assertEquals(123456.7, pidA6, 0.01)
        assertEquals(123456.7, pidD3, 0.01)
    }

    @Test
    fun ignoresAdapterBannersAndOtherPidsBeforeValidSingleLineTelemetry() {
        assertArrayEquals(intArrayOf(0x1A, 0xF8),
            CamaroUdsProtocol.parsePidBytes(
                "SEARCHING...\r\n010C\r\nNO DATA\r\n7E8 04 41 0C 1A F8", "0C"))
        assertArrayEquals(intArrayOf(0x1A, 0xF8),
            CamaroUdsProtocol.parsePidBytes("7E804410C1AF8", "0C"))
        assertArrayEquals(intArrayOf(0x1A, 0xF8),
            CamaroUdsProtocol.parsePidBytes("18DAF11004410C1AF8", "0C"))
        for (invalid in listOf("410C", "41 0C", "7E8 03 41 0C 1A F8",
                "SEARCH410C1AF8", "41 0D 1A F8", "7E8 41 0C GG F8",
                "BAD 04 41 0C 1A F8", "7DF 04 41 0C 1A F8",
                "18DB33F1 04 41 0C 1A F8",
                "18DB33F104410C1AF8", "18DAF11004410C1A",
                "18DAF11003410C1AF8")) {
            assertNull("Invalid telemetry must not be interpreted: $invalid",
                CamaroUdsProtocol.parsePidBytes(invalid, "0C"))
        }
    }

    @Test
    fun odometerNeverCombinesChatterOrIncompleteAndOverlongPidReplies() {
        for (invalid in listOf(
            "A641A60012D687",
            "410C01234567",
            "41 A6 00 12 D6",
            "41 A6 00 12 D6 87 01",
            "7E8 05 41 A6 00 12 D6 87",
            "7E8 07 41 A6 00 12 D6 87",
            "SEARCHING...\nNO DATA",
            "410A410A410A",
            "41 D3 00 12 D6 87"
        )) assertNull("Never infer odometer from: $invalid",
            CamaroUdsProtocol.parseOdometerPidA6(invalid))
        val confirmed = requireNotNull(CamaroUdsProtocol.parseOdometerPidA6(
            "SEARCHING...\r\n7E8 06 41 A6 00 12 D6 87"))
        assertEquals(123456.7, confirmed, 0.01)
    }

    @Test
    fun marksStandardEcmAccessUnavailableWhenNoCanAdapterIsConnected() {
        val modules = CamaroUdsProtocol.buildTopology(
            VehicleProfile(modelYear = 2012, engine = CamaroEngine.LFX),
            AdapterCapabilities(tier = AdapterTier.DISCONNECTED)
        )

        val ecm = modules.single { it.id == "ECM" }
        assertEquals(ModuleStatus.UNSUPPORTED_BY_ADAPTER, ecm.status)
        assertEquals("", ecm.txHeader)
        assertEquals("", ecm.rxHeader)
    }

    @Test
    fun usesFunctionalObdHeadersOnlyWhenCanAdapterIsAvailable() {
        val modules = CamaroUdsProtocol.buildTopology(
            VehicleProfile(modelYear = 2018, engine = CamaroEngine.LT1),
            AdapterCapabilities(tier = AdapterTier.PRO_STN, supportsCanHeaders = true)
        )

        val ecm = modules.single { it.id == "ECM" }
        assertEquals(ModuleStatus.DISCOVERY_REQUIRED, ecm.status)
        assertEquals("7DF", ecm.txHeader)
        assertEquals("", ecm.rxHeader)
    }

    @Test
    fun doesNotAssumeHeaderOrCanProtocolSupportWithoutPositiveAdapterResponses() {
        val capabilities = CamaroUdsProtocol.evaluateAdapterProbe(
            deviceName = "Generic adapter",
            macAddress = "00:00:00:00:00:00",
            atzResp = "ELM327",
            stiResp = "?",
            atalResp = "?",
            stp61Resp = "?",
            headersResp = "?",
            protocolResp = "?"
        )

        assertFalse(capabilities.supportsCanHeaders)
    }

    @Test
    fun negativeAtRepliesDoNotGrantCanOrSwCanCapabilities() {
        for (failure in listOf("NOT OK", "NOK", "OK?", "ERROR", "UNABLE TO CONNECT",
                "OK\r\nERROR", "OK\r\nNO DATA", "OK\r\n?")) {
            val capabilities = CamaroUdsProtocol.evaluateAdapterProbe(
                deviceName = "OBDLink MX+",
                macAddress = "00:00:00:00:00:01",
                atzResp = "OBDLink",
                stiResp = "STN2120 v5.6.5",
                atalResp = failure,
                stp61Resp = failure,
                headersResp = failure,
                protocolResp = failure
            )
            assertFalse("Must not trust $failure as ATAL", capabilities.supportsLongFrames)
            assertFalse("Must not trust $failure as STP 61", capabilities.supportsSwCan)
            assertFalse("Must not trust $failure as CAN headers", capabilities.supportsCanHeaders)
        }
    }

    @Test
    fun echoedAtCommandsWithSeparateOkAcknowledgementsRemainSupported() {
        val capabilities = CamaroUdsProtocol.evaluateAdapterProbe(
            deviceName = "OBDLink MX+",
            macAddress = "00:00:00:00:00:01",
            atzResp = "OBDLink",
            stiResp = "STN2120 v5.6.5",
            atalResp = "ATAL\r\nOK\r\n>",
            stp61Resp = "STP 61\r\nOK\r\n>",
            headersResp = "ATH1\r\nOK\r\n>",
            protocolResp = "ATSP6\r\nOK\r\n>"
        )
        assertTrue(capabilities.supportsLongFrames)
        assertTrue(capabilities.supportsSwCan)
        assertTrue(capabilities.supportsCanHeaders)
        assertFalse(capabilities.supportsCanTransmit)
    }

    @Test
    fun doesNotConfuseCanHeaderSupportWithPhysicalTransmitCapability() {
        val capabilities = CamaroUdsProtocol.evaluateAdapterProbe(
            deviceName = "ELM327 Bluetooth",
            macAddress = "00:00:00:00:00:00",
            atzResp = "ELM327 v1.5",
            stiResp = "?",
            atalResp = "OK",
            stp61Resp = "?",
            headersResp = "OK",
            protocolResp = "OK"
        )

        assertTrue(capabilities.supportsCanHeaders)
        assertFalse(capabilities.supportsCanTransmit)
    }

    @Test
    fun enablesCanTransmitOnlyAfterARealStandardObdResponse() {
        val capabilities = CamaroUdsProtocol.evaluateAdapterProbe(
            deviceName = "ELM327 Bluetooth",
            macAddress = "00:00:00:00:00:00",
            atzResp = "ELM327 v1.5",
            stiResp = "?",
            atalResp = "OK",
            stp61Resp = "?",
            headersResp = "OK",
            protocolResp = "OK",
            transmitProbeResp = "SEARCHING...\r\n7E8 06 41 00 BE 3E B8 13"
        )

        assertTrue(capabilities.supportsCanHeaders)
        assertTrue(capabilities.supportsCanTransmit)
    }

    @Test
    fun rejectsAdapterChatterAndIncompletePid00FragmentsAsTransmitProof() {
        listOf(
            "0100",
            "SEARCHING...\r\nNO DATA",
            "ELM327 v4.100",
            "7E8 03 41 00 BE",
            "7E8 05 41 00 BE 3E B8 13",
            "7E8 08 41 00 BE 3E B8 13",
            "18DAF110 07 41 00 BE 3E B8 13",
            "NOPE4100BE3EB813",
            "BEE 06 41 00 BE 3E B8 13",
            "7DF 06 41 00 BE 3E B8 13",
            "18DB33F1 06 41 00 BE 3E B8 13"
        ).forEach { response ->
            val capabilities = CamaroUdsProtocol.evaluateAdapterProbe(
                deviceName = "ELM327 Bluetooth",
                macAddress = "00:00:00:00:00:00",
                atzResp = "ELM327 v1.5",
                stiResp = "?",
                atalResp = "OK",
                stp61Resp = "?",
                headersResp = "OK",
                protocolResp = "OK",
                transmitProbeResp = response
            )
            assertFalse("Must not trust response: $response", capabilities.supportsCanTransmit)
        }
    }

    @Test
    fun acceptsCompletePid00ResponseWithOrWithoutCanHeader() {
        listOf(
            "41 00 BE 3E B8 13",
            "06 41 00 BE 3E B8 13",
            "7E8 06 41 00 BE 3E B8 13",
            "18DAF110 06 41 00 BE 3E B8 13",
            "7EF 06 41 00 BE 3E B8 13"
        ).forEach { response ->
            val capabilities = CamaroUdsProtocol.evaluateAdapterProbe(
                deviceName = "ELM327 Bluetooth",
                macAddress = "00:00:00:00:00:00",
                atzResp = "ELM327 v1.5",
                stiResp = "?",
                atalResp = "OK",
                stp61Resp = "?",
                headersResp = "OK",
                protocolResp = "OK",
                transmitProbeResp = response
            )
            assertTrue("Expected a complete ECU response: $response", capabilities.supportsCanTransmit)
        }
    }

    @Test
    fun genericElmCloneCanUseStandardCanWithoutClaimingUdsCapabilities() {
        val capabilities = CamaroUdsProtocol.evaluateAdapterProbe(
            deviceName = "ELM327 Bluetooth",
            macAddress = "00:00:00:00:00:00",
            atzResp = "ELM327 v2.1",
            stiResp = "?",
            atalResp = "?",
            stp61Resp = "?",
            headersResp = "OK",
            protocolResp = "OK"
        )

        assertEquals(AdapterTier.CLONE_ELM, capabilities.tier)
        assertTrue(capabilities.supportsCanHeaders)
        assertFalse(capabilities.supportsLongFrames)
        assertFalse(capabilities.supportsSwCan)
    }

    @Test
    fun doesNotSelectStnModeFromObdLinkOrVlinkerNameWithoutFirmwareEvidence() {
        listOf("OBDLink MX+", "OBDLink CX", "vLinker MC+").forEach { name ->
            val capabilities = CamaroUdsProtocol.evaluateAdapterProbe(
                deviceName = name,
                macAddress = "00:00:00:00:00:00",
                atzResp = "OBDLink",
                stiResp = "?",
                atalResp = "?",
                stp61Resp = "OK",
                headersResp = "OK",
                protocolResp = "OK"
            )

            assertEquals(name, AdapterTier.CLONE_ELM, capabilities.tier)
            assertFalse(name, capabilities.supportsSwCan)
        }
    }

    @Test
    fun stnSubstringWithoutRealFirmwareVersionCannotElevateAdapterCapabilities() {
        for (banner in listOf("NOT STN2120", "unknown STN", "STN", "STN21201",
            "STN2120BROKEN", "STN2120 bogus", "STN2120_v6.6", "STN2120 vX.Y")) {
            val capabilities = CamaroUdsProtocol.evaluateAdapterProbe(
                deviceName = "OBDLink MX+",
                macAddress = "00:00:00:00:00:01",
                atzResp = banner,
                stiResp = banner,
                atalResp = "OK",
                stp61Resp = "OK",
                headersResp = "OK",
                protocolResp = "OK"
            )
            assertFalse("SW-CAN must require a versioned STN firmware banner: $banner",
                capabilities.supportsSwCan)
            assertTrue("Unverified STN banner must not set PRO_STN: $banner",
                capabilities.tier != AdapterTier.PRO_STN)
        }
    }

    @Test
    fun enablesSwCanOnlyForDocumentedMxHardwareWithAcceptedProtocol() {
        val mxPlus = CamaroUdsProtocol.evaluateAdapterProbe(
            deviceName = "OBDLink MX+",
            macAddress = "00:00:00:00:00:00",
            atzResp = "OBDLink",
            stiResp = "STN2120 v5.6.5",
            atalResp = "OK",
            stp61Resp = "OK"
        )
        val genericStn = CamaroUdsProtocol.evaluateAdapterProbe(
            deviceName = "Generic STN adapter",
            macAddress = "00:00:00:00:00:01",
            atzResp = "STN",
            stiResp = "STN2120 v5.6.5",
            atalResp = "OK",
            stp61Resp = "OK"
        )

        assertTrue(mxPlus.supportsSwCan)
        assertEquals(AdapterTier.PRO_STN, genericStn.tier)
        assertFalse(genericStn.supportsSwCan)
    }

    @Test
    fun doesNotClaimSwCanFromProtocolCommandAcceptanceAlone() {
        val capabilities = CamaroUdsProtocol.evaluateAdapterProbe(
            deviceName = "STN adapter",
            macAddress = "00:00:00:00:00:00",
            atzResp = "OBDLink",
            stiResp = "STN2255 v5.6.5",
            atalResp = "OK",
            stp61Resp = "OK"
        )

        assertFalse(capabilities.supportsSwCan)
    }

    @Test
    fun adapterCapabilitiesDefaultToDisconnectedAndFailClosed() {
        val adapter = AdapterCapabilities()
        assertEquals(AdapterTier.DISCONNECTED, adapter.tier)
        assertFalse(adapter.supportsCanHeaders)
        assertFalse(adapter.supportsCanTransmit)
        assertFalse(adapter.supportsLongFrames)
        assertFalse(adapter.supportsSwCan)
    }

    @Test
    fun marksStandardEcmAccessibleOnlyAfterAReal7E8Response() {
        val topology = CamaroUdsProtocol.buildTopology(
            VehicleProfile(modelYear = 2018, engine = CamaroEngine.LT1),
            AdapterCapabilities(tier = AdapterTier.PRO_STN, supportsCanHeaders = true)
        )

        val confirmed = CamaroUdsProtocol.applyStandardObdDiscovery(topology, listOf("7E8", "7E9"))
        val unconfirmed = CamaroUdsProtocol.applyStandardObdDiscovery(topology, listOf("7E9"))

        assertEquals(ModuleStatus.ACCESSIBLE, confirmed.single { it.id == "ECM" }.status)
        assertEquals("7E8", confirmed.single { it.id == "ECM" }.rxHeader)
        assertEquals(ModuleStatus.DISCOVERY_REQUIRED, unconfirmed.single { it.id == "ECM" }.status)
        assertEquals("", unconfirmed.single { it.id == "ECM" }.rxHeader)
    }

    @Test
    fun listsOptionalEcuFamiliesForDiscoveryWithoutInventingAddresses() {
        val optionalIds = setOf("HVAC", "PSCM", "RCDLR", "RADIO", "AMP", "DDM", "PDM", "FPCM", "PARK_ASSIST", "HMI")
        listOf(
            VehicleProfile(modelYear = 2012, engine = CamaroEngine.LFX),
            VehicleProfile(modelYear = 2018, engine = CamaroEngine.LT1)
        ).forEach { vehicle ->
            val modules = CamaroUdsProtocol.buildTopology(
                vehicle,
                AdapterCapabilities(tier = AdapterTier.PRO_STN, supportsCanHeaders = true)
            )
            val candidates = modules.filter { it.id in optionalIds }
            assertEquals(optionalIds, candidates.map { it.id }.toSet())
            candidates.forEach { module ->
                assertEquals(ModuleStatus.DISCOVERY_REQUIRED, module.status)
                assertEquals("", module.txHeader)
                assertEquals("", module.rxHeader)
                assertTrue(module.notesEn.contains("Optional equipment candidate"))
            }
        }
    }

    @Test
    fun gen5AndGen6EngineRangesDoNotOverlap() {
        assertTrue(2012 in CamaroEngine.LFX.years)
        assertTrue(2020 in CamaroEngine.LGX.years)
        assertFalse(2020 in CamaroEngine.LFX.years)
    }
    @Test
    fun ethanolPidRequiresExactlyOneUnsignedDataByte() {
        assertEquals(0.0, CamaroUdsProtocol.decodeEthanolPercent(intArrayOf(0))!!, 0.00001)
        assertEquals(100.0, CamaroUdsProtocol.decodeEthanolPercent(intArrayOf(255))!!, 0.00001)
        assertEquals(50.196078, CamaroUdsProtocol.decodeEthanolPercent(intArrayOf(128))!!, 0.00001)
        assertNull(CamaroUdsProtocol.decodeEthanolPercent(null))
        assertNull(CamaroUdsProtocol.decodeEthanolPercent(intArrayOf()))
        assertNull(CamaroUdsProtocol.decodeEthanolPercent(intArrayOf(128, 0)))
        assertNull(CamaroUdsProtocol.decodeEthanolPercent(intArrayOf(-1)))
        assertNull(CamaroUdsProtocol.decodeEthanolPercent(intArrayOf(256)))
    }

    @Test
    fun ethanolTelemetryDoesNotAcceptWrongOrConcatenatedPidResponses() {
        assertNull(CamaroUdsProtocol.decodeEthanolPercent(
            CamaroUdsProtocol.parsePidBytes("41 53 FF", "52")))
        assertNull(CamaroUdsProtocol.decodeEthanolPercent(
            CamaroUdsProtocol.parsePidBytes("41 52 FF 00", "52")))
        assertNull(CamaroUdsProtocol.decodeEthanolPercent(
            CamaroUdsProtocol.parsePidBytes("41 52", "52")))
        assertEquals(100.0, CamaroUdsProtocol.decodeEthanolPercent(
            CamaroUdsProtocol.parsePidBytes("41 52 FF", "52"))!!, 0.00001)
    }

    @Test
    fun standardLiveTelemetryRejectsTruncatedAndPaddedSensorResponses() {
        val samples = listOf(
            Triple("0C", "41 0C 1A F8", "41 0C 1A"),
            Triple("05", "41 05 5B", "41 05 5B 00"),
            Triple("42", "41 42 36 B0", "41 42 36"),
            Triple("52", "41 52 80", "41 52 80 00"),
            Triple("24", "41 24 80 00 40 00", "41 24 80 00"),
            Triple("01", "41 01 00 00 00 00", "41 01 00 00 00")
        )
        for ((pid, valid, invalid) in samples) {
            assertNotNull("PID $pid complete bytes", CamaroUdsProtocol.parsePidBytes(valid, pid))
            assertNull("PID $pid malformed bytes", CamaroUdsProtocol.parsePidBytes(invalid, pid))
        }
        // GM-specific mileage decoding retains its dedicated four-byte
        // contract even though it is not part of the standard PID allowlist.
        assertEquals(123456.7,
            requireNotNull(CamaroUdsProtocol.parseOdometerPidA6("41 A6 00 12 D6 87")), 0.01)
    }

    @Test
    fun primaryPowertrainTelemetryRejectsOtherPhysicalEcuResponses() {
        for (valid in listOf("7E8 03 41 52 80", "18DAF110 03 41 52 80",
            "7E803415280", "18DAF11003415280", "41 52 80")) {
            assertArrayEquals(valid, intArrayOf(128),
                CamaroUdsProtocol.parsePidBytes(valid, "52",
                    primaryPowertrainResponderOnly = true))
        }
        for (foreign in listOf("7E9 03 41 52 80", "18DAF111 03 41 52 80",
            "7E903415280", "18DAF11103415280")) {
            assertNull(foreign, CamaroUdsProtocol.parsePidBytes(foreign, "52",
                primaryPowertrainResponderOnly = true))
        }
    }

    @Test
    fun absoluteLoadPid43UsesTheSaeScalingAndCanExceedOneHundredPercent() {
        assertEquals(0.0,
            requireNotNull(CamaroUdsProtocol.decodeAbsoluteLoadPercent(intArrayOf(0, 0))), 0.001)
        assertEquals(100.0,
            requireNotNull(CamaroUdsProtocol.decodeAbsoluteLoadPercent(intArrayOf(0, 255))), 0.001)
        assertEquals(200.0,
            requireNotNull(CamaroUdsProtocol.decodeAbsoluteLoadPercent(intArrayOf(1, 254))), 0.001)
        assertEquals(25700.0,
            requireNotNull(CamaroUdsProtocol.decodeAbsoluteLoadPercent(intArrayOf(255, 255))), 0.001)
        assertNull(CamaroUdsProtocol.decodeAbsoluteLoadPercent(intArrayOf(255)))
        assertNull(CamaroUdsProtocol.decodeAbsoluteLoadPercent(intArrayOf(255, 0, 0)))
        assertNull(CamaroUdsProtocol.decodeAbsoluteLoadPercent(intArrayOf(-1, 1)))
        assertNull(CamaroUdsProtocol.decodeAbsoluteLoadPercent(intArrayOf(256, 1)))
    }

}
