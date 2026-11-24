package com.camarodiag.gen5.data.obd

import org.junit.Assert.*
import org.junit.Test
import com.camarodiag.gen5.data.obd.DiagnosticRouteSelector.Protocol
import com.camarodiag.gen5.data.obd.DiagnosticRouteSelector.Link
import com.camarodiag.gen5.data.obd.DiagnosticRouteSelector.Framing
import com.camarodiag.gen5.data.obd.DiagnosticRouteSelector.Service
import com.camarodiag.gen5.data.obd.DiagnosticRouteSelector.Target
import com.camarodiag.gen5.data.obd.DiagnosticRouteSelector.Capability
import com.camarodiag.gen5.data.obd.DiagnosticRouteSelector.Evidence
import com.camarodiag.gen5.data.obd.DiagnosticRouteSelector.Operation
import com.camarodiag.gen5.data.obd.DiagnosticRouteSelector.Rejection

class DiagnosticRouteSelectorTest {
    private val raw = Protocol(Link.CAN, Framing.RAW, Service.RAW)
    private val uds = Protocol(Link.CAN, Framing.ISO_TP, Service.UDS)
    private val sw = Protocol(Link.SW_CAN, Framing.ISO_TP, Service.GMLAN)
    private val target = Target("2012-LFX", "ECM", setOf(Link.CAN), setOf(raw, uds), true)
    private val caps = listOf(
        Capability(raw, Evidence.VERIFIED, true, setOf(Operation.CONSOLE)),
        Capability(uds, Evidence.VERIFIED, true, setOf(Operation.DTC, Operation.MONITOR)),
        Capability(sw, Evidence.VERIFIED, true, setOf(Operation.DTC))
    )
    @Test fun sameConnectorAndEcuUseDifferentProtocolByOperation() {
        assertEquals(raw, DiagnosticRouteSelector.select(caps, target, Operation.CONSOLE).routes.single().capability.protocol)
        assertEquals(uds, DiagnosticRouteSelector.select(caps, target, Operation.DTC).routes.single().capability.protocol)
    }
    @Test fun unknownAndUnsupportedNeverBecomeExecutable() {
        val result = DiagnosticRouteSelector.select(caps.map { it.copy(evidence = Evidence.ADVERTISED) }, target, Operation.DTC)
        assertTrue(result.routes.isEmpty())
        assertTrue(result.excluded.all { Rejection.UNVERIFIED in it.reasons })
        assertTrue(Rejection.VEHICLE_LINK in result.excluded.last().reasons)
        assertTrue(Rejection.ECU_PROTOCOL in result.excluded.last().reasons)
    }
    @Test fun unknownAddressAndUnimplementedOperationAreRejected() {
        assertTrue(DiagnosticRouteSelector.select(caps, target.copy(addressingVerified = false), Operation.CONSOLE).routes.isEmpty())
        assertTrue(DiagnosticRouteSelector.select(caps, target, Operation.PROGRAM).routes.isEmpty())
        assertTrue(DiagnosticRouteSelector.select(caps.map { it.copy(implemented = false) }, target, Operation.DTC).routes.isEmpty())
    }
    @Test fun elmWireProfilesCoverBothStandardSpeedsAndWidths() {
        assertEquals(6, DiagnosticRouteSelector.elmCanProtocol(false, 500000))
        assertEquals(7, DiagnosticRouteSelector.elmCanProtocol(true, 500000))
        assertEquals(8, DiagnosticRouteSelector.elmCanProtocol(false, 250000))
        assertEquals(9, DiagnosticRouteSelector.elmCanProtocol(true, 250000))
        assertThrows(IllegalArgumentException::class.java) { DiagnosticRouteSelector.elmCanProtocol(false, 33333) }
    }
}
