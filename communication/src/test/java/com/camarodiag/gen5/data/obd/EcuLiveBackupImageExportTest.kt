package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class EcuLiveBackupImageExportTest {
    private val vin = "1G1FA1RXXR0123456"
    private val channel = EcuBackupManifest.Channel(0x7E0, 0x7E8, 33, 1)
    private val identity = EcuRestoreImageFile.Identity(vin, "ECU-E39", "read-osid")

    private fun capture(origin: EcuBackupManifest.Origin): J2534LiveMemoryBackup.CaptureResult {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val snapshot = EcuBackupManifest.Capture(
            EcuBackupCoverage.Region.CALIBRATION, 0x1000, 4,
            bytes.copyOf(), bytes.copyOf(), origin
        )
        val manifest = EcuBackupManifest.inspect(
            "Test ECU", 1795320000000L, listOf(snapshot), emptySet(), vehicleVin = vin
        )
        val verification = EcuBackupIntegrity.verify(manifest, listOf(snapshot), emptySet())
        assertTrue(verification.consistent)
        return J2534LiveMemoryBackup.CaptureResult(manifest, snapshot, verification)
    }

    @Test fun connectedPhysicalCaptureCanBecomeReopenableRegionImage() {
        val root = Files.createTempDirectory("c2-live").toFile()
        try {
            val dest = File(root, "physical-capture")
            EcuLiveBackupImageExport.saveVerifiedCapture(
                capture(EcuBackupManifest.Origin.REPORTED_HARDWARE_READ),
                channel, identity, dest
            )
            val reopened = EcuRestorePackage.open(dest)
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), reopened.regions.single().bytes())
            assertTrue(reopened.regions.single().geometry.startAddress == 0x1000L)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun simulationCannotBeMisrepresentedAsLiveCapture() {
        val root = Files.createTempDirectory("c2-simulation").toFile()
        try {
            val dest = File(root, "not-physical")
            assertTrue(runCatching {
                EcuLiveBackupImageExport.saveVerifiedCapture(
                    capture(EcuBackupManifest.Origin.SIMULATION),
                    channel, identity, dest
                )
            }.isFailure)
            assertTrue(!dest.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
