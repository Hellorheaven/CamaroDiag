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
    @Test fun rawDiagnosticAndBackupRoutesDoNotNeedFlashDrivers() {
        val available = listOf(
            Capability(raw, Evidence.VERIFIED, true,
                setOf(Operation.CONSOLE, Operation.BACKUP)),
            Capability(uds, Evidence.VERIFIED, true,
                setOf(Operation.DTC, Operation.IDENTIFY))
        )
        assertEquals(1, DiagnosticRouteSelector.select(available, target,
            Operation.CONSOLE).routes.size)
        assertEquals(1, DiagnosticRouteSelector.select(available, target,
            Operation.BACKUP).routes.size)
        assertEquals(1, DiagnosticRouteSelector.select(available, target,
            Operation.DTC).routes.size)
    }

    @Test fun advertisedFlashRouteCannotBypassActualRegisteredDriver() {
        val firmware = EcuProgrammingDriverContract.FirmwareTarget("E39", "board", "os")
        val qualifiedTarget = target.copy(
            programmingFirmware = firmware,
            programmingRegion = EcuBackupCoverage.Region.CALIBRATION,
            programmingAccess = EcuBenchExpertPlan.AccessMethod.OBD
        )
        val claimed = Capability(uds, Evidence.VERIFIED, true,
            setOf(Operation.DTC, Operation.PROGRAM, Operation.RESTORE),
            programmingTransport = EcuProgrammingDriverContract.Transport.J2534)
        for (operation in listOf(Operation.PROGRAM, Operation.RESTORE)) {
            val rejected = DiagnosticRouteSelector.select(
                listOf(claimed), qualifiedTarget, operation)
            assertTrue(rejected.routes.isEmpty())
            assertTrue(Rejection.UNQUALIFIED_PROGRAMMING_DRIVER in
                rejected.excluded.single().reasons)
        }
        assertEquals(1, DiagnosticRouteSelector.select(
            listOf(claimed), qualifiedTarget, Operation.DTC).routes.size)
    }

    @Test fun offlineQualifiedDriverModelRequiresExactFirmwareRegionAndInterface() {
        val firmware = EcuProgrammingDriverContract.FirmwareTarget("E39", "board", "os")
        val route = EcuBenchExpertPlan.AccessMethod.OBD
        val region = EcuBackupCoverage.Region.CALIBRATION
        val descriptor = EcuProgrammingDriverContract.Descriptor(
            id = "simulated-qualification-only",
            transport = EcuProgrammingDriverContract.Transport.J2534,
            capabilities = EcuProgrammingDriverContract.Capability.values().toSet(),
            controllerFamilies = setOf(firmware.controllerFamily),
            physicalHardwareValidated = true,
            writeTargets = setOf(firmware),
            writeQualifications = setOf(
                EcuProgrammingDriverContract.WriteQualification(firmware, region, route))
        )
        val capable = Capability(uds, Evidence.VERIFIED, true,
            setOf(Operation.PROGRAM),
            programmingTransport = descriptor.transport)
        val selected = target.copy(programmingFirmware = firmware,
            programmingRegion = region, programmingAccess = route)
        fun select(candidate: Target = selected, capability: Capability = capable,
                   driver: EcuProgrammingDriverContract.Descriptor = descriptor) =
            DiagnosticRouteSelector.selectAgainstVerifiedHardware(
                listOf(capability), candidate, Operation.PROGRAM, emptyList(), listOf(driver))
        assertEquals(1, select().routes.size)
        assertTrue(select(selected.copy(programmingFirmware =
            firmware.copy(firmwareId = "other"))).routes.isEmpty())
        assertTrue(select(selected.copy(programmingRegion =
            EcuBackupCoverage.Region.DATA_FLASH)).routes.isEmpty())
        assertTrue(select(selected.copy(programmingAccess =
            EcuBenchExpertPlan.AccessMethod.BENCH)).routes.isEmpty())
        assertTrue(select(capability = capable.copy(programmingTransport =
            EcuProgrammingDriverContract.Transport.ELM_DIAGNOSTICS)).routes.isEmpty())
        assertTrue(select(driver = descriptor.copy(physicalHardwareValidated = false)).routes.isEmpty())
        // A genuinely qualified ELM implementation is allowed: there is no
        // arbitrary read-only blacklist by adapter marketing name.
        val elm = descriptor.copy(
            transport = EcuProgrammingDriverContract.Transport.ELM_DIAGNOSTICS)
        assertEquals(1, select(
            capability = capable.copy(programmingTransport = elm.transport),
            driver = elm).routes.size)
        // A model descriptor never registers as an actual hardware driver.
        assertTrue(DiagnosticRouteSelector.select(
            listOf(capable), selected, Operation.PROGRAM).routes.isEmpty())
    }

    @Test fun elmWireProfilesCoverBothStandardSpeedsAndWidths() {
        assertEquals(6, DiagnosticRouteSelector.elmCanProtocol(false, 500000))
        assertEquals(7, DiagnosticRouteSelector.elmCanProtocol(true, 500000))
        assertEquals(8, DiagnosticRouteSelector.elmCanProtocol(false, 250000))
        assertEquals(9, DiagnosticRouteSelector.elmCanProtocol(true, 250000))
        assertThrows(IllegalArgumentException::class.java) { DiagnosticRouteSelector.elmCanProtocol(false, 33333) }
    }
}
