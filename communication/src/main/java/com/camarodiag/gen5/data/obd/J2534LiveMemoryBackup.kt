package com.camarodiag.gen5.data.obd

import java.io.OutputStream
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Real, non-destructive memory capture over a connected Windows J2534 PassThru bridge.
 *
 * There are NO built-in GM memory maps. Each address and length must be independently
 * documented and authorized by the PC-side VIN-bound --memory-read-profile. If the
 * controller refuses 0x23 or requires unavailable SecurityAccess, capture fails closed.
 *
 * A complete requested REGION is not necessarily a complete ECU image, and two
 * consistent reads do not prove that its bytes are restorable.
 */
object J2534LiveMemoryBackup {
    private const val MAX_CAPTURE_BYTES = 4 * 1024 * 1024
    private const val TIMEOUT_MS = 6000L

    /** Exact, non-destructive reason the ECU refused a documented memory span. */
    internal class ReadRefused(
        val requestedAddress: Long,
        val requestedBytes: Int,
        val nrc: Int
    ) : IllegalStateException(
        "ECU refused memory read at 0x${requestedAddress.toString(16).uppercase()} " +
            "($requestedBytes bytes, NRC 0x${nrc.toString(16).uppercase().padStart(2, '0')}). " +
            "No security access, retries with new privileges or writes were attempted."
    )

    /**
     * UDS DiagnosticSessionControl has either a minimal echoed subfunction
     * or the full P2/P2* timing parameters. A three-, four- or five-byte
     * reply cannot establish a valid session transition.
     */
    internal fun isValidSessionReply(reply: ByteArray, expectedSubfunction: Int): Boolean =
        (reply.size == 2 || reply.size == 6) &&
            reply[0] == 0x50.toByte() &&
            (reply[1].toInt() and 0xFF) == expectedSubfunction

    /** Two independently observed GM identities, never an assumed firmware family. */
    internal fun verifiedStableFirmware(
        before: GmlanIdentityEvidence,
        after: GmlanIdentityEvidence,
        expectedVin: String
    ): EcuBackupManifest.FirmwareEvidence {
        require(before.vin == expectedVin && after == before) {
            "GM VIN/OSID changed during memory acquisition; refuse to archive firmware evidence."
        }
        return EcuBackupManifest.FirmwareEvidence(before.operatingSystemId, before.osDid)
    }

    data class Request(
        val expectedVin: String,
        val region: EcuBackupCoverage.Region,
        val address: Long,
        val lengthBytes: Int,
        val chunkBytes: Int = 128,
        /** 1 = default session; 3 = extended diagnostics (PC scope must allow it). */
        val diagnosticSession: Int = 1,
        val controllerLabel: String? = null
    )

    /** Independent acquisition 1 or 2; byte counts describe actual ECU responses. */
    data class CaptureProgress(
        val pass: Int,
        val completedBytes: Int,
        val regionBytes: Int
    ) {
        init {
            require(pass in 1..2 && regionBytes > 0 && completedBytes in 0..regionBytes)
        }
        val totalBytesRead: Long get() = (pass - 1L) * regionBytes + completedBytes
        val requiredBytesRead: Long get() = regionBytes.toLong() * 2L
    }

    data class CaptureResult(
        val manifest: EcuBackupManifest.Manifest,
        val capture: EcuBackupManifest.Capture,
        val verification: EcuBackupIntegrity.Result
    ) {
        internal fun export(
            output: OutputStream,
            channel: EcuBackupManifest.Channel? = null,
            firmwareEvidence: EcuBackupManifest.FirmwareEvidence? = null
        ) {
            require(manifest.channel == null || channel == null || channel == manifest.channel) {
                "Export route differs from the acquired ECU channel."
            }
            require(manifest.firmwareEvidence == null || firmwareEvidence == null ||
                firmwareEvidence == manifest.firmwareEvidence) {
                "Export firmware evidence differs from the acquired ECU identity."
            }
            val exportChannel = manifest.channel ?: channel
            val exportFirmware = manifest.firmwareEvidence ?: firmwareEvidence
            if (capture.origin == EcuBackupManifest.Origin.REPORTED_HARDWARE_READ) {
                requireNotNull(exportChannel) {
                    "A physically acquired backup must retain its J2534 CAN route."
                }
            }
            // Bind the archive to the acquisition's original digest, not a newly
            // computed digest of potentially mutated public capture buffers.
            check(capture.independentRead != null && capture.firstRead !== capture.independentRead) {
                "Two distinct acquisition buffers are required."
            }
            val snapshot = capture.copy(
                firstRead = capture.firstRead.copyOf(),
                independentRead = capture.independentRead?.copyOf()
            )
            val required = emptySet<EcuBackupCoverage.Region>()
            check(verification.consistent &&
                EcuBackupIntegrity.verify(manifest, listOf(snapshot), required).consistent
            ) { "Capture no longer matches the verified acquisition." }
            EcuBackupArchive.export(
                output, listOf(snapshot), required, manifest.controllerLabel,
                manifest.createdAtUtcMillis, manifest.vehicleVin, exportChannel, exportFirmware
            )
        }
    }

    /**
     * Production entry point: no simulator transport can be supplied.
     * This requires the actual PC bridge to be connected to a physical J2534 device.
     */
    suspend fun capture(
        bridge: J2534BridgeTransport,
        request: Request,
        progress: (CaptureProgress) -> Unit = {}
    ): CaptureResult {
        check(bridge.isConnected && bridge.isScopedMemoryReadEnabled) {
            "A real J2534 bridge with an approved ECU memory read scope must be connected."
        }
        val boundedRequest = bridge.requireApprovedMemoryReadScope(request)
        return acquire(bridge, boundedRequest, EcuBackupManifest.Origin.REPORTED_HARDWARE_READ, progress) {
            bridge.isConnected && bridge.isScopedMemoryReadEnabled
        }
    }

    /** Only fake transport may enter unit tests; fake data is never marked as hardware. */
    internal suspend fun captureForTest(
        transport: UdsPayloadTransport,
        request: Request,
        progress: (CaptureProgress) -> Unit = {},
        liveLinkValid: () -> Boolean = { true }
    ): CaptureResult =
        acquire(transport, request, EcuBackupManifest.Origin.SIMULATION, progress, liveLinkValid)

    private suspend fun acquire(
        transport: UdsPayloadTransport,
        request: Request,
        origin: EcuBackupManifest.Origin,
        progress: (CaptureProgress) -> Unit,
        liveLinkValid: () -> Boolean = { true }
    ): CaptureResult {
        require(request.expectedVin.matches(Regex("[A-HJ-NPR-Z0-9]{17}"))) { "Invalid target VIN." }
        require(request.address in 0L..0xFFFFFFFFL &&
            request.lengthBytes in 1..MAX_CAPTURE_BYTES &&
            request.address + request.lengthBytes <= 0x1_0000_0000L
        ) { "Invalid or overflowing ECU memory capture range." }
        require(request.chunkBytes in 1..256) { "J2534 memory read blocks must be 1..256 bytes." }
        require(request.diagnosticSession == 1 || request.diagnosticSession == 3) {
            "Only default and extended read sessions are supported."
        }

        var identityLost = false
        suspend fun liveVin(): String = try {
            check(liveLinkValid()) { "Physical bridge or scoped read permission was lost." }
            val response = transport.exchange(byteArrayOf(0x22, 0xF1.toByte(), 0x90.toByte()), TIMEOUT_MS)
            require(response.size == 20 &&
                response[0] == 0x62.toByte() &&
                response[1] == 0xF1.toByte() && response[2] == 0x90.toByte()
            ) { "ECU did not respond to DID F190 with a valid VIN." }
            val vin = response.copyOfRange(3, 20).toString(Charsets.US_ASCII)
            require(vin == request.expectedVin) { "Live ECU VIN differs from selected vehicle." }
            vin
        } catch (cancelled: CancellationException) {
            // Cancellation alone does not prove a different ECU. The
            // NonCancellable session cleanup independently rechecks the
            // original link's VIN before it can send a session reset.
            throw cancelled
        } catch (failure: Exception) {
            // Malformed, missing or mismatched identity is uncertain;
            // never send a session change to that target.
            identityLost = true
            throw failure
        }

        fun memoryRequest(address: Long, bytes: Int): ByteArray {
            // ISO 14229 ALFI: 4-byte address; use a 1-byte size for 1..255
            // and a 2-byte size only for 256. Match the Windows native reader.
            val addressBytes = byteArrayOf(
                (address ushr 24).toByte(), (address ushr 16).toByte(),
                (address ushr 8).toByte(), address.toByte()
            )
            return if (bytes > 255) {
                byteArrayOf(0x23, 0x24) + addressBytes +
                    byteArrayOf((bytes ushr 8).toByte(), bytes.toByte())
            } else {
                byteArrayOf(0x23, 0x14) + addressBytes + byteArrayOf(bytes.toByte())
            }
        }

        suspend fun pass(passNumber: Int): ByteArray {
            val image = ByteArray(request.lengthBytes)
            var offset = 0
            progress(CaptureProgress(passNumber, 0, image.size))
            var blocks = 0
            while (offset < image.size) {
                currentCoroutineContext().ensureActive()
                check(liveLinkValid()) { "Physical bridge or scoped read permission was lost." }
                if (blocks > 0 && blocks % 32 == 0) {
                    val ping = transport.exchange(byteArrayOf(0x3E, 0x00), TIMEOUT_MS)
                    require(ping.size == 2 && ping[0] == 0x7E.toByte() && ping[1] == 0x00.toByte()) {
                        "ECU no longer confirms its active diagnostic session."
                    }
                    // A live socket can remain connected while the interface is
                    // reattached to a different ECU. TesterPresent alone cannot
                    // prove identity; recheck the hardware VIN periodically.
                    liveVin()
                }
                val length = minOf(request.chunkBytes, image.size - offset)
                val requestedAddress = request.address + offset
                val response = transport.exchange(
                    memoryRequest(requestedAddress, length), TIMEOUT_MS
                )
                if (response.size == 3 &&
                    response[0] == 0x7F.toByte() && response[1] == 0x23.toByte()
                ) {
                    throw ReadRefused(requestedAddress, length, response[2].toInt() and 0xFF)
                }
                require(response.size == length + 1 && response[0] == 0x63.toByte()) {
                    "ECU memory response is not a complete ReadMemoryByAddress result."
                }
                response.copyInto(image, destinationOffset = offset, startIndex = 1)
                offset += length
                blocks++
                // Emit progress periodically rather than on every CAN frame.
                if (blocks % 16 == 0 || offset == image.size) {
                    progress(CaptureProgress(passNumber, offset, image.size))
                }
            }
            return image
        }

        liveVin()
        var extendedRequested = false
        var captureCompleted = false
        try {
            if (request.diagnosticSession == 3) {
                // A lost or malformed positive response can still mean the ECU changed session.
                extendedRequested = true
                val response = transport.exchange(byteArrayOf(0x10, 0x03), TIMEOUT_MS)
                require(isValidSessionReply(response, 0x03)) {
                    "ECU did not accept a well-formed extended read session."
                }
            }
            val first = pass(1)
            // Reject a swapped ECU BEFORE starting the second acquisition;
            // two matching byte arrays do not establish physical identity.
            liveVin()
            // A second independent hardware acquisition is compulsory.
            val second = pass(2)
            // VIN could change after a session replacement or reconnect.
            val observedVin = liveVin()
            check(liveLinkValid()) { "Physical bridge or scoped read permission was lost." }
            require(first.contentEquals(second)) {
                "Two physical memory acquisitions disagree; do not export this backup."
            }
            val capture = EcuBackupManifest.Capture(
                region = request.region,
                startAddress = request.address,
                expectedBytes = request.lengthBytes.toLong(),
                firstRead = first,
                independentRead = second,
                origin = origin
            )
            // The requested address span is not an independently qualified full ECU map.
            val required = emptySet<EcuBackupCoverage.Region>()
            val manifest = EcuBackupManifest.inspect(
                request.controllerLabel, System.currentTimeMillis(), listOf(capture), required, observedVin
            )
            val verification = EcuBackupIntegrity.verify(manifest, listOf(capture), required)
            check(verification.consistent) { "Physical ECU memory backup failed integrity checks." }
            currentCoroutineContext().ensureActive()
            captureCompleted = true
            return CaptureResult(manifest, capture, verification)
        } finally {
            if (extendedRequested && !identityLost) {
                // Cleanup on the original transport even after cancellation. Each
                // exchange has a bounded timeout. Never reconnect, and recheck VIN
                // before changing session so a replacement ECU is not reset.
                val reset = withContext(NonCancellable) {
                    runCatching {
                        withTimeout(2 * (TIMEOUT_MS + 3000L) + 1000L) {
                            liveVin()
                            val response = transport.exchange(byteArrayOf(0x10, 0x01), TIMEOUT_MS)
                            require(isValidSessionReply(response, 0x01)) {
                                "ECU did not confirm a well-formed return to the default diagnostic session."
                            }
                        }
                    }
                }
                // Preserve the original failure/cancellation. A nominally successful
                // extended capture is not reported as successful if cleanup failed.
                if (captureCompleted) {
                    reset.getOrThrow()
                    currentCoroutineContext().ensureActive()
                }
            }
        }
    }
}
