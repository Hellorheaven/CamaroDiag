package com.camarodiag.gen5.data.obd

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Connects the verified physical J2534 acquisition to the immutable,
 * offline restoration image package without invoking a programming protocol.
 *
 * This only exports exactly the captured memory scope; it cannot infer
 * missing flash/EEPROM regions or claim full ECU coverage.
 */
object EcuLiveBackupImageExport {
    fun saveVerifiedCapture(
        captureResult: J2534LiveMemoryBackup.CaptureResult,
        physicalRoute: EcuBackupManifest.Channel,
        identity: EcuRestoreImageFile.Identity,
        directory: File,
        firmwareEvidence: EcuBackupManifest.FirmwareEvidence? = null
    ): EcuRestorePackage.Contents {
        require(captureResult.capture.origin ==
            EcuBackupManifest.Origin.REPORTED_HARDWARE_READ) {
            "A simulated or imported read cannot be published as a live ECU acquisition"
        }
        require(captureResult.verification.consistent &&
            captureResult.manifest.vehicleVin == identity.vin) {
            "The physical ECU capture or selected vehicle is inconsistent"
        }
        if (firmwareEvidence != null) {
            require(captureResult.manifest.firmwareEvidence == null ||
                captureResult.manifest.firmwareEvidence == firmwareEvidence) {
                "Physical firmware evidence differs from the captured ECU"
            }
        }
        val archive = ByteArrayOutputStream()
        captureResult.export(archive, physicalRoute, firmwareEvidence)
        val imported = EcuBackupArchive.import(
            ByteArrayInputStream(archive.toByteArray()),
            EcuBackupArchive.MAX_J2534_IMPORT_UNCOMPRESSED_BYTES
        )
        val captured = imported.captures.single()
        require(captured.expectedBytes == captured.firstRead.size.toLong()) {
            "Partial memory read cannot form an exact restoration region"
        }
        return EcuRestorePackage.createNew(
            directory, imported,
            listOf(EcuRestoreImage.MemoryRegion(
                captured.region, captured.startAddress, captured.expectedBytes
            )),
            identity
        )
    }
}
