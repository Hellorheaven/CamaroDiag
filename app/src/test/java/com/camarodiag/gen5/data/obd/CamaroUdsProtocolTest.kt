package com.camarodiag.gen5.data.obd

import com.camarodiag.gen5.domain.model.AdapterCapabilities
import com.camarodiag.gen5.domain.model.AdapterTier
import com.camarodiag.gen5.domain.model.CamaroEngine
import com.camarodiag.gen5.domain.model.ModuleStatus
import com.camarodiag.gen5.domain.model.VehicleProfile
import org.junit.Assert.*
import org.junit.Test

class CamaroUdsProtocolTest {
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
    fun adapterCapabilitiesDefaultToDisconnectedAndFailClosed() {
        val adapter = AdapterCapabilities()
        assertEquals(AdapterTier.DISCONNECTED, adapter.tier)
        assertFalse(adapter.supportsCanHeaders)
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
    fun gen5AndGen6EngineRangesDoNotOverlap() {
        assertTrue(2012 in CamaroEngine.LFX.years)
        assertTrue(2020 in CamaroEngine.LGX.years)
        assertFalse(2020 in CamaroEngine.LFX.years)
    }
}
