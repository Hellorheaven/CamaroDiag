package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class J2534ApprovedMemoryScopeTest {
    private val vin = "1G1FA1RXXR0123456"
    private val scope = J2534ApprovedMemoryScope(
        vin, EcuBackupCoverage.Region.CALIBRATION,
        0x1000L, 0x2000L, 128, 3
    )
    private val request = J2534LiveMemoryBackup.Request(
        expectedVin = vin, region = EcuBackupCoverage.Region.CALIBRATION,
        address = 0x1100L, lengthBytes = 512, chunkBytes = 128,
        diagnosticSession = 3
    )

    @Test fun approvedBridgeProfileAllowsOnlyTheExactDocumentedRegion() {
        assertTrue(scope.permits(request))
        assertTrue(scope.permits(request.copy(address = 0x2F00L, lengthBytes = 256)))
        assertFalse(scope.permits(request.copy(region = EcuBackupCoverage.Region.OTHER)))
        assertFalse(scope.permits(request.copy(region = EcuBackupCoverage.Region.DATA_FLASH)))
        assertFalse(scope.permits(request.copy(expectedVin = "1G1FA1RXXR0123457")))
        assertFalse(scope.permits(request.copy(address = 0x0FFFL)))
        assertFalse(scope.permits(request.copy(address = 0x2FFFL, lengthBytes = 2)))
        assertFalse(scope.permits(request.copy(chunkBytes = 129)))
        assertFalse(scope.permits(request.copy(diagnosticSession = 1)))
        assertFalse(scope.permits(request.copy(lengthBytes = 0)))
        assertTrue(J2534ApprovedMemoryScope(vin, EcuBackupCoverage.Region.OTHER,
            0, 0xFFFF_FFFFL, 256, 1).permits(request.copy(
                region = EcuBackupCoverage.Region.OTHER, address = 0,
                lengthBytes = 4 * 1024 * 1024, chunkBytes = 256,
                diagnosticSession = 1)))
    }

    @Test fun malformedBridgeMemoryReadScopeCannotBeAuthorized() {
        val invalid = listOf(
            { scope.copy(vin = "NOT-A-VIN") },
            { scope.copy(startAddress = -1) },
            { scope.copy(startAddress = 0xFFFF_FFFFL, lengthBytes = 2) },
            { scope.copy(lengthBytes = 0) },
            { scope.copy(lengthBytes = 0x1_0000_0000L) },
            { scope.copy(maxReadBytes = 257) },
            { scope.copy(maxReadBytes = 0) },
            { scope.copy(diagnosticSession = 2) }
        )
        for (invalidProfile in invalid) assertTrue(runCatching { invalidProfile() }.isFailure)
    }
}
