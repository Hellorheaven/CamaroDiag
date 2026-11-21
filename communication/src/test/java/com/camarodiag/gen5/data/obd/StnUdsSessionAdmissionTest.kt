package com.camarodiag.gen5.data.obd

import com.camarodiag.gen5.domain.model.AdapterCapabilities
import com.camarodiag.gen5.domain.model.AdapterTier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StnUdsSessionAdmissionTest {
    private fun adapter(name: String) = AdapterCapabilities(
        deviceName = name,
        tier = AdapterTier.PRO_STN,
        firmwareId = "STN2255 v5.10.3",
        supportsCanHeaders = true,
        supportsCanTransmit = true
    )

    @Test fun everyProbedStnModelUsesOneSharedReadOnlySessionGate() {
        for (name in listOf("OBDLink MX+", "OBDLink MX", "OBDLink EX",
                "OBDLink CX", "OBDLink LX", "OBDLink SX")) {
            assertTrue(name, StnUdsSessionAdmission.allowsRead(adapter(name), true))
        }
    }

    @Test fun nameAloneCannotAuthorizePhysicalUds() {
        val forged = adapter("OBDLink MX+")
        assertFalse(StnUdsSessionAdmission.allowsRead(forged.copy(
            tier = AdapterTier.CLONE_ELM
        ), true))
        assertFalse(StnUdsSessionAdmission.allowsRead(forged.copy(
            firmwareId = "ELM327 v2.1"
        ), true))
        assertFalse(StnUdsSessionAdmission.allowsRead(forged.copy(
            supportsCanHeaders = false
        ), true))
        assertFalse(StnUdsSessionAdmission.allowsRead(forged.copy(
            supportsCanTransmit = false
        ), true))
        assertFalse(StnUdsSessionAdmission.allowsRead(forged, false))
        assertFalse(StnUdsSessionAdmission.allowsRead(null, true))
    }

    @Test fun sourceModelDoesNotMatterOncePhysicalEvidenceIsEstablished() {
        assertTrue(StnUdsSessionAdmission.allowsRead(
            adapter("Generic Bluetooth label"), true
        ))
        // Actual hardware model recognition is repeated via STI/STDI by
        // StnUdsPayloadTransport before any UDS request can be transmitted.
    }
}
