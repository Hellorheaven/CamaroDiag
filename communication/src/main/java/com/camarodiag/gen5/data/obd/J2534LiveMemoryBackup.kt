package com.camarodiag.gen5.data.obd

import java.io.OutputStream

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
    )

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
        return acquire(bridge, request, EcuBackupManifest.Origin.REPORTED_HARDWARE_READ, progress)
    }

    suspend fun captureAndExport(
        bridge: J2534BridgeTransport,
        request: Request,
        output: OutputStream,
        progress: (CaptureProgress) -> Unit = {}
    ): CaptureResult {
        val read = capture(bridge, request, progress)
        EcuBackupArchive.export(
            output = output,
            captures = listOf(read.capture),
            requiredRegions = setOf(request.region),
            controllerLabel = request.controllerLabel
        )
        return read
    }

    /** Only fake transport may enter unit tests; fake data is never marked as hardware. */
    internal suspend fun captureForTest(
        transport: UdsPayloadTransport,
        request: Request,
        progress: (CaptureProgress) -> Unit = {}
    ): CaptureResult =
        acquire(transport, request, EcuBackupManifest.Origin.SIMULATION, progress)

    private suspend fun acquire(
        transport: UdsPayloadTransport,
        request: Request,
        origin: EcuBackupManifest.Origin,
        progress: (CaptureProgress) -> Unit
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

        suspend fun liveVin(): String {
            val response = transport.exchange(byteArrayOf(0x22, 0xF1.toByte(), 0x90.toByte()), TIMEOUT_MS)
            require(response.size >= 20 &&
                response[0] == 0x62.toByte() &&
                response[1] == 0xF1.toByte() && response[2] == 0x90.toByte()
            ) { "ECU did not respond to DID F190 with a valid VIN." }
            val vin = response.copyOfRange(3, 20).toString(Charsets.US_ASCII)
            require(vin == request.expectedVin) { "Live ECU VIN differs from selected vehicle." }
            return vin
        }

        fun memoryRequest(address: Long, bytes: Int): ByteArray =
            byteArrayOf(0x23, 0x24) +
                byteArrayOf(
                    (address ushr 24).toByte(), (address ushr 16).toByte(),
                    (address ushr 8).toByte(), address.toByte(),
                    (bytes ushr 8).toByte(), bytes.toByte()
                )

        suspend fun pass(passNumber: Int): ByteArray {
            val image = ByteArray(request.lengthBytes)
            var offset = 0
            progress(CaptureProgress(passNumber, 0, image.size))
            var blocks = 0
            while (offset < image.size) {
                if (blocks > 0 && blocks % 32 == 0) {
                    val ping = transport.exchange(byteArrayOf(0x3E, 0x00), TIMEOUT_MS)
                    require(ping.size >= 2 && ping[0] == 0x7E.toByte()) {
                        "ECU no longer confirms its active diagnostic session."
                    }
                }
                val length = minOf(request.chunkBytes, image.size - offset)
                val response = transport.exchange(
                    memoryRequest(request.address + offset, length), TIMEOUT_MS
                )
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
        var enteredExtended = false
        try {
            if (request.diagnosticSession == 3) {
                val response = transport.exchange(byteArrayOf(0x10, 0x03), TIMEOUT_MS)
                require(response.size >= 2 && response[0] == 0x50.toByte() &&
                    response[1] == 0x03.toByte()) {
                    "ECU did not accept the extended read session."
                }
                enteredExtended = true
            }
            val first = pass(1)
            // A second independent hardware acquisition is compulsory.
            val second = pass(2)
            // VIN could change after a session replacement or reconnect.
            liveVin()
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
            val required = setOf(request.region)
            val manifest = EcuBackupManifest.inspect(
                request.controllerLabel, System.currentTimeMillis(), listOf(capture), required
            )
            val verification = EcuBackupIntegrity.verify(manifest, listOf(capture), required)
            check(verification.consistent) { "Physical ECU memory backup failed integrity checks." }
            return CaptureResult(manifest, capture, verification)
        } finally {
            if (enteredExtended) {
                // Best-effort return to default session. If the bridge vanished,
                // there must be no automatic reconnect or write/recovery attempt.
                runCatching { transport.exchange(byteArrayOf(0x10, 0x01), TIMEOUT_MS) }
            }
        }
    }
}
