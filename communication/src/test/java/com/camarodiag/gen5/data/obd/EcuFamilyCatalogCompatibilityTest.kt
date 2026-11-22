package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Catalogue part numbers and calibration revisions do not define a new
 * communication protocol. These are purely offline tests: no ECU programming.
 */
class EcuFamilyCatalogCompatibilityTest {
    @Test fun e39ServiceSupersessionAndDonorLabelsShareOneProtocolFamily() {
        val current = Lfx2012ControllerReferences.resolveCatalogReference("12651994").single()
        val superseded = Lfx2012ControllerReferences.resolveCatalogReference("12643248").single()
        val printed = Lfx2012ControllerReferences.resolveCatalogReference("12648906").single()
        val bcc = Lfx2012ControllerReferences.resolveCatalogReference(" aa69 ").single()

        for (match in listOf(current, superseded, printed, bcc)) {
            assertEquals("E39", match.family)
            assertEquals("12651994", match.serviceGroup)
            assertFalse(match.authorizesProgramming)
        }
        assertEquals(
            Lfx2012ControllerReferences.CatalogReferenceKind.CURRENT_SERVICE_NUMBER,
            current.kind
        )
        assertEquals(
            Lfx2012ControllerReferences.CatalogReferenceKind.SUPERSEDED_SERVICE_NUMBER,
            superseded.kind
        )
        assertEquals(
            Lfx2012ControllerReferences.CatalogReferenceKind.PRINTED_PART_NUMBER,
            printed.kind
        )
        assertEquals(
            Lfx2012ControllerReferences.CatalogReferenceKind.BROADCAST_CODE,
            bcc.kind
        )
    }

    @Test fun independentServiceGroupsShareProtocolWithoutInventingHardwareEquivalence() {
        val other = Lfx2012ControllerReferences.resolveCatalogReference("12637106").single()
        val otherBcc = Lfx2012ControllerReferences.resolveCatalogReference("AAMU").single()
        assertEquals("E39", other.family)
        assertEquals("E39", otherBcc.family)
        assertEquals("12651993", other.serviceGroup)
        assertEquals(other.serviceGroup, otherBcc.serviceGroup)
        assertFalse(other.authorizesProgramming)
        assertTrue(Lfx2012ControllerReferences.resolveCatalogReference("12653998").isEmpty())
        assertTrue(Lfx2012ControllerReferences.resolveCatalogReference("12345678").isEmpty())
    }

    @Test fun oneProtocolDriverCanServeManyFirmwareVersionsButWriteApprovalIsSeparate() {
        val capabilities = EcuProgrammingDriverContract.Capability.values().toSet()
        val first = EcuProgrammingDriverContract.FirmwareTarget("E39", "ECM-E39", "CAL-A")
        val second = first.copy(firmwareId = "CAL-B")
        val unknown = first.copy(firmwareId = "CAL-UNKNOWN")
        val driver = EcuProgrammingDriverContract.Descriptor(
            id = "synthetic-protocol-test",
            transport = EcuProgrammingDriverContract.Transport.J2534,
            capabilities = capabilities,
            controllerFamilies = setOf("E39"),
            physicalHardwareValidated = true,
            writeTargets = setOf(first, second),
            writeQualifications = setOf(first, second).map {
                EcuProgrammingDriverContract.WriteQualification(
                    it, EcuBackupCoverage.Region.CALIBRATION, EcuBenchExpertPlan.AccessMethod.BENCH
                )
            }.toSet()
        )
        assertTrue(driver.supportsFamilyProtocol("E39", EcuProgrammingDriverContract.Transport.J2534))
        assertTrue(driver.supportsFamilyProtocol(" e39 ", EcuProgrammingDriverContract.Transport.J2534))
        assertTrue(driver.supportsFamilyProtocol("e39", EcuProgrammingDriverContract.Transport.J2534))
        assertFalse(driver.supportsFamilyProtocol("", EcuProgrammingDriverContract.Transport.J2534))
        // Another family/generation must still require its own protocol evidence.
        assertFalse(driver.supportsFamilyProtocol("E39A", EcuProgrammingDriverContract.Transport.J2534))
        assertFalse(driver.supportsFamilyProtocol("E92", EcuProgrammingDriverContract.Transport.J2534))
        assertFalse(driver.supportsFamilyProtocol("E39", EcuProgrammingDriverContract.Transport.BENCH))
        assertTrue(driver.supportsWrite(first, EcuProgrammingDriverContract.Transport.J2534))
        assertTrue(driver.supportsWrite(second, EcuProgrammingDriverContract.Transport.J2534))
        assertFalse(driver.supportsWrite(unknown, EcuProgrammingDriverContract.Transport.J2534))
        // A tolerant protocol lookup NEVER broadens destructive firmware approval.
        assertFalse(driver.supportsWrite(
            first.copy(controllerFamily = "e39"),
            EcuProgrammingDriverContract.Transport.J2534
        ))

        val unvalidated = driver.copy(physicalHardwareValidated = false)
        assertTrue(unvalidated.supportsFamilyProtocol("E39", EcuProgrammingDriverContract.Transport.J2534))
        assertFalse(unvalidated.supportsWrite(first, EcuProgrammingDriverContract.Transport.J2534))
    }
}
