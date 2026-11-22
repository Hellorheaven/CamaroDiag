package com.camarodiag.gen5.data.obd

import com.camarodiag.gen5.domain.model.CamaroGeneration
import com.camarodiag.gen5.domain.model.DiagnosticDtc
import com.camarodiag.gen5.domain.model.VehicleProfile
import kotlinx.coroutines.delay
import java.util.Locale

data class CanFrame(
    val arbitrationId: Int,
    val data: ByteArray,
    val extendedId: Boolean = false
) {
    init {
        val maxId = if (extendedId) 0x1FFFFFFF else 0x7FF
        require(arbitrationId in 0..maxId) { "CAN identifier is outside the selected ID format." }
        require(data.size in 1..8) { "Classical CAN payload must contain 1 to 8 bytes." }
    }
}

/** Raw CAN transport contract. Implementations must preserve CAN IDs and frame boundaries. */
interface RawCanFrameTransport {
    suspend fun send(frame: CanFrame)
    suspend fun receive(timeoutMs: Long): CanFrame?
}

/** Complete UDS payload transport. The provider may perform ISO-TP itself (e.g. STPX). */
interface UdsPayloadTransport {
    suspend fun exchange(payload: ByteArray, timeoutMs: Long): ByteArray
    suspend fun sendOnly(payload: ByteArray, timeoutMs: Long)
}

/** Adapts a raw Classical CAN provider to complete UDS payload exchanges using ISO-TP. */
class IsoTpUdsPayloadTransport(
    transport: RawCanFrameTransport,
    requestCanId: Int,
    responseCanId: Int,
    extendedCanId: Boolean = false
) : UdsPayloadTransport {
    private val channel = IsoTpChannel(transport, requestCanId, responseCanId, extendedCanId)
    override suspend fun exchange(payload: ByteArray, timeoutMs: Long): ByteArray =
        channel.exchange(payload, timeoutMs)
    override suspend fun sendOnly(payload: ByteArray, timeoutMs: Long) =
        channel.sendOnly(payload, timeoutMs)
}

class IsoTpException(message: String) : IllegalStateException(message)

/** Classical CAN ISO-TP normal-addressing channel. */
internal class IsoTpChannel(
    private val transport: RawCanFrameTransport,
    private val requestCanId: Int,
    private val responseCanId: Int,
    private val extendedCanId: Boolean = false,
    private val clock: () -> Long = System::nanoTime
) {
    suspend fun exchange(payload: ByteArray, timeoutMs: Long): ByteArray {
        require(timeoutMs in 1..30_000L) { "ISO-TP timeout must be bounded." }
        require(payload.isNotEmpty()) { "ISO-TP payload cannot be empty." }
        require(payload.size <= MAX_PAYLOAD_BYTES) { "ISO-TP payload exceeds the supported 12-bit length." }
        sendPayload(payload, timeoutMs)
        var response = receivePayload(timeoutMs)
        if (response.size >= 3 &&
            (response[0].toInt() and 0xFF) == 0x7F &&
            (response[1].toInt() and 0xFF) == (payload[0].toInt() and 0xFF) &&
            (response[2].toInt() and 0xFF) == 0x78) {
            val pendingTimeoutMs = maxOf(timeoutMs, 10_000L)
            val deadline = System.nanoTime() + pendingTimeoutMs * NANOS_PER_MILLISECOND
            do {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) throw IsoTpException("Timed out waiting for UDS ResponsePending completion.")
                val remainingMs = ((remaining + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND).coerceAtLeast(1L)
                response = receivePayload(remainingMs)
            } while (response.size >= 3 &&
                (response[0].toInt() and 0xFF) == 0x7F &&
                (response[1].toInt() and 0xFF) == (payload[0].toInt() and 0xFF) &&
                (response[2].toInt() and 0xFF) == 0x78)
        }
        return response
    }

    suspend fun sendOnly(payload: ByteArray, timeoutMs: Long) {
        require(timeoutMs in 1..30_000L) { "ISO-TP timeout must be bounded." }
        require(payload.isNotEmpty()) { "ISO-TP payload cannot be empty." }
        require(payload.size <= MAX_PAYLOAD_BYTES) { "ISO-TP payload exceeds the supported 12-bit length." }
        sendPayload(payload, timeoutMs)
    }

    private suspend fun sendPayload(payload: ByteArray, timeoutMs: Long) {
        if (payload.size <= SINGLE_FRAME_MAX_BYTES) {
            val frame = ByteArray(payload.size + 1)
            frame[0] = payload.size.toByte()
            payload.copyInto(frame, destinationOffset = 1)
            transport.send(CanFrame(requestCanId, frame, extendedCanId))
            return
        }

        val firstFrame = ByteArray(8)
        firstFrame[0] = (0x10 or ((payload.size shr 8) and 0x0F)).toByte()
        firstFrame[1] = (payload.size and 0xFF).toByte()
        payload.copyInto(firstFrame, destinationOffset = 2, startIndex = 0, endIndex = 6)
        transport.send(CanFrame(requestCanId, firstFrame, extendedCanId))

        var offset = 6
        var sequence = 1
        var sentInBlock = 0
        var blockSize = -1
        var separationMs = 0L

        while (offset < payload.size) {
            if (blockSize < 0 || (blockSize > 0 && sentInBlock >= blockSize)) {
                val flowControl = awaitFlowControl(timeoutMs)
                blockSize = flowControl.first
                separationMs = flowControl.second
                sentInBlock = 0
            }
            if (separationMs > 0) delay(separationMs)

            val count = minOf(7, payload.size - offset)
            val consecutiveFrame = ByteArray(8)
            consecutiveFrame[0] = (0x20 or (sequence and 0x0F)).toByte()
            payload.copyInto(
                consecutiveFrame,
                destinationOffset = 1,
                startIndex = offset,
                endIndex = offset + count
            )
            transport.send(CanFrame(requestCanId, consecutiveFrame, extendedCanId))
            offset += count
            sequence = (sequence + 1) and 0x0F
            sentInBlock++
        }
    }

    private suspend fun awaitFlowControl(timeoutMs: Long): Pair<Int, Long> {
        require(timeoutMs in 1..30_000L) { "Flow-control timeout is out of range." }
        val deadline = clock() + timeoutMs * NANOS_PER_MILLISECOND
        var waits = 0
        var unrelated = 0
        while (true) {
            // Wait/irrelevant flow-control frames consume a single deadline;
            // they must not repeatedly reset the adapter's timeout budget.
            val remaining = deadline - clock()
            if (remaining <= 0L)
                throw IsoTpException("ISO-TP flow-control deadline exceeded.")
            val remainingMs =
                ((remaining + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND).coerceAtLeast(1L)
            val frame = awaitFrame(remainingMs)
            requireFrameLength(frame, 3, "Flow-control frame")
            if ((frame.data[0].toInt() and 0xF0) != 0x30) {
                if (++unrelated > MAX_UNEXPECTED_FLOW_CONTROL_FRAMES)
                    throw IsoTpException("Repeated non-flow-control frames from the expected ECU.")
                continue
            }

            when (frame.data[0].toInt() and 0x0F) {
                0x00 -> return (frame.data[1].toInt() and 0xFF) to decodeSeparationTime(frame.data[2].toInt() and 0xFF)
                0x01 -> {
                    waits++
                    if (waits > MAX_FLOW_CONTROL_WAIT_FRAMES) {
                        throw IsoTpException("ISO-TP flow-control wait limit exceeded.")
                    }
                }
                0x02 -> throw IsoTpException("Remote ECU reported ISO-TP flow-control overflow.")
                else -> throw IsoTpException("Remote ECU returned an invalid ISO-TP flow-control status.")
            }
        }
    }

    private suspend fun receivePayload(timeoutMs: Long): ByteArray {
        val first = awaitFrame(timeoutMs)
        requireFrameLength(first, 2, "First ISO-TP response")
        val frameType = (first.data[0].toInt() and 0xF0) shr 4

        if (frameType == 0x0) {
            val length = first.data[0].toInt() and 0x0F
            require(length in 1..SINGLE_FRAME_MAX_BYTES && first.data.size >= length + 1) {
                "Invalid ISO-TP single-frame length."
            }
            return first.data.copyOfRange(1, length + 1)
        }

        if (frameType != 0x1) throw IsoTpException("Expected an ISO-TP single or first frame.")
        val length = ((first.data[0].toInt() and 0x0F) shl 8) or (first.data[1].toInt() and 0xFF)
        require(length in 8..MAX_PAYLOAD_BYTES) { "Invalid or unsupported ISO-TP first-frame length." }
        requireFrameLength(first, 8, "ISO-TP first response")

        val output = ByteArray(length)
        val initialCount = minOf(6, length)
        first.data.copyInto(output, destinationOffset = 0, startIndex = 2, endIndex = 2 + initialCount)
        var copied = initialCount
        sendReceiveFlowControl()

        var expectedSequence = 1
        while (copied < length) {
            val frame = awaitFrame(timeoutMs)
            requireFrameLength(frame, 2, "ISO-TP consecutive response")
            if ((frame.data[0].toInt() and 0xF0) != 0x20) {
                throw IsoTpException("Expected ISO-TP consecutive frame.")
            }
            val sequence = frame.data[0].toInt() and 0x0F
            if (sequence != expectedSequence) {
                throw IsoTpException("ISO-TP sequence mismatch: expected " + expectedSequence + ", received " + sequence + ".")
            }
            expectedSequence = (expectedSequence + 1) and 0x0F
            val count = minOf(7, length - copied)
            requireFrameLength(frame, count + 1, "ISO-TP consecutive response")
            frame.data.copyInto(output, destinationOffset = copied, startIndex = 1, endIndex = 1 + count)
            copied += count
        }
        return output
    }

    private suspend fun sendReceiveFlowControl() {
        val frame = ByteArray(8)
        frame[0] = 0x30
        frame[1] = 0x00 // unlimited block size for the response
        frame[2] = 0x00 // no requested separation time
        transport.send(CanFrame(requestCanId, frame, extendedCanId))
    }

    private suspend fun awaitFrame(timeoutMs: Long): CanFrame {
        val deadline = System.nanoTime() + timeoutMs.coerceAtLeast(1L) * NANOS_PER_MILLISECOND
        while (true) {
            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos <= 0L) {
                throw IsoTpException("Timed out waiting for CAN response 0x" + responseCanId.toString(16).uppercase(Locale.ROOT) + ".")
            }
            val remainingMs = ((remainingNanos + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND).coerceAtLeast(1L)
            val frame = transport.receive(remainingMs) ?: throw IsoTpException("Timed out waiting for ISO-TP response.")
            if (frame.arbitrationId == responseCanId && frame.extendedId == extendedCanId) return frame
        }
    }

    private fun requireFrameLength(frame: CanFrame, minimum: Int, label: String) {
        if (frame.data.size < minimum) throw IsoTpException(label + " is shorter than " + minimum + " bytes.")
    }

    private fun decodeSeparationTime(value: Int): Long = when (value) {
        in 0x00..0x7F -> value.toLong()
        in 0xF1..0xF9 -> 1L
        else -> throw IsoTpException("Unsupported ISO-TP separation-time value 0x" + value.toString(16).uppercase(Locale.ROOT) + ".")
    }

    private companion object {
        const val SINGLE_FRAME_MAX_BYTES = 7
        const val MAX_PAYLOAD_BYTES = 0x0FFF
        const val MAX_FLOW_CONTROL_WAIT_FRAMES = 5
        const val MAX_UNEXPECTED_FLOW_CONTROL_FRAMES = 8
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

class UdsNegativeResponseException(
    val requestedService: Int,
    val negativeResponseCode: Int
) : IllegalStateException(
    "UDS service 0x" + requestedService.toString(16).uppercase(Locale.ROOT).padStart(2, '0') +
        " rejected with NRC 0x" + negativeResponseCode.toString(16).uppercase(Locale.ROOT).padStart(2, '0') + "."
)

/**
 * Read-only UDS client. Only ReadDataByIdentifier (0x22) and
 * ReadDTCInformation (0x19/0x02) are exposed. It has no session-change,
 * security-access, actuator, memory-write or download API.
 */
class UdsReadOnlyClient(
    transport: RawCanFrameTransport,
    requestCanId: Int,
    responseCanId: Int,
    extendedCanId: Boolean = false,
    private val timeoutMs: Long = 3000L
) {
    private val channel = IsoTpChannel(transport, requestCanId, responseCanId, extendedCanId)

    suspend fun readDataByIdentifier(did: Int): ByteArray {
        require(did in 0..0xFFFF) { "DID must be a 16-bit value." }
        val request = byteArrayOf(0x22, (did shr 8).toByte(), did.toByte())
        val response = channel.exchange(request, timeoutMs)
        verifyPositiveResponse(response, 0x22)
        require(response.size >= 3) { "UDS ReadDataByIdentifier response is incomplete." }
        val returnedDid = ((response[1].toInt() and 0xFF) shl 8) or (response[2].toInt() and 0xFF)
        require(returnedDid == did) {
            "ECU returned DID 0x" + returnedDid.toString(16).uppercase(Locale.ROOT) +
                " instead of 0x" + did.toString(16).uppercase(Locale.ROOT) + "."
        }
        return response.copyOfRange(3, response.size)
    }

    suspend fun readVin(): String {
        val data = readDataByIdentifier(0xF190)
        val vin = data.toString(Charsets.US_ASCII).trim()
        require(vin.matches(Regex("[A-HJ-NPR-Z0-9]{17}"))) {
            "ECU did not return a valid 17-character VIN in DID F190."
        }
        return vin
    }

    suspend fun readDtcInformation(statusMask: Int = 0xFF): ByteArray {
        require(statusMask in 0..0xFF) { "DTC status mask must be one byte." }
        val response = channel.exchange(byteArrayOf(0x19, 0x02, statusMask.toByte()), timeoutMs)
        verifyPositiveResponse(response, 0x19)
        require(response.size >= 3 && (response[1].toInt() and 0xFF) == 0x02) {
            "UDS ReadDTCInformation response did not echo subfunction 0x02."
        }
        return response.copyOfRange(2, response.size)
    }

    /** Reads and decodes the ECU's declared DTC format without guessing OEM definitions. */
    suspend fun readDtcReport(statusMask: Int = 0xFF): UdsDtcReport =
        UdsDtcDecoder.decodeReadDtcInformation(readDtcInformation(statusMask))

    private fun verifyPositiveResponse(response: ByteArray, requestedService: Int) {
        require(response.isNotEmpty()) { "ECU returned an empty UDS response." }
        val service = response[0].toInt() and 0xFF
        if (service == 0x7F) {
            require(response.size >= 3) { "ECU returned an incomplete UDS negative response." }
            val rejectedService = response[1].toInt() and 0xFF
            val nrc = response[2].toInt() and 0xFF
            throw UdsNegativeResponseException(rejectedService, nrc)
        }
        require(service == requestedService + 0x40) {
            "Unexpected UDS positive service 0x" + service.toString(16).uppercase(Locale.ROOT) + "."
        }
    }
}

enum class UdsIdentityField {
    CONTROLLER_FAMILY,
    HARDWARE_PART_NUMBER,
    OPERATING_SYSTEM_ID,
    CALIBRATION_ID,
    CALIBRATION_VERIFICATION_NUMBER,
    VIN
}

enum class UdsIdentityEncoding {
    ASCII,
    HEX
}

data class UdsIdentityDidDefinition(
    val field: UdsIdentityField,
    val did: Int,
    val encoding: UdsIdentityEncoding
)

/**
 * A profile becomes usable only when exact module, vehicle, hardware, operating-system,
 * calibration, CVN and VIN values match and a traceable evidence reference is present.
 */
data class DocumentedUdsReadProfile(
    val profileId: String,
    val moduleId: String,
    val generation: CamaroGeneration,
    val modelYears: IntRange,
    val engineRpo: String,
    val controllerFamily: String,
    val requestCanId: Int,
    val responseCanId: Int,
    val extendedCanId: Boolean,
    val hardwarePartNumber: String,
    val operatingSystemId: String,
    val calibrationId: String,
    val calibrationVerificationNumber: String,
    val readableDids: Set<Int>,
    val identityDids: List<UdsIdentityDidDefinition>,
    val verifiedVin: String,
    val evidenceReference: String
) {
    private fun unverifiedIdentityLabel(value: String?): Boolean {
        val text = value?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (text.isBlank() || text in setOf("unknown", "n/a", "none", "null",
                "pending", "tbd", "test", "simulated", "invalid", "no data",
                "not supported", "-", "0", "ff")) return true
        val bytes = text.removePrefix("0x").replace(Regex("[\\s:_-]"), "")
        return bytes.isNotEmpty() && (bytes.all { it == '0' } || bytes.all { it == 'f' })
    }

    fun compatibilityDefinitionIssues(vehicle: VehicleProfile): List<String> = buildList {
        if (profileId.isBlank()) add("Profile ID is missing.")
        if (moduleId.isBlank()) add("Module ID is missing.")
        if (unverifiedIdentityLabel(controllerFamily)) add("A real ECU controller family is required.")
        if (vehicle.generation != generation || vehicle.modelYear !in modelYears) add("Model year/generation does not match.")
        if (!vehicle.engine.rpo.equals(engineRpo, ignoreCase = true)) add("Engine RPO does not match.")

        val vinPattern = Regex("[A-HJ-NPR-Z0-9]{17}")
        val selectedVin = vehicle.vin.trim().uppercase(Locale.ROOT)
        val profileVin = verifiedVin.trim().uppercase(Locale.ROOT)
        if (!selectedVin.matches(vinPattern)) add("Selected vehicle must have a valid 17-character VIN.")
        if (!profileVin.matches(vinPattern)) add("The verified profile VIN must be a valid 17-character VIN.")
        if (selectedVin != profileVin) add("Selected vehicle VIN does not match the VIN validated for this profile.")

        if (listOf(hardwarePartNumber, operatingSystemId, calibrationId,
                calibrationVerificationNumber).any(::unverifiedIdentityLabel)) {
            add("Exact non-placeholder hardware, OS, calibration and CVN values are required.")
        }

        val maxCanId = if (extendedCanId) 0x1FFFFFFF else 0x7FF
        if (requestCanId !in 0..maxCanId || responseCanId !in 0..maxCanId || requestCanId == responseCanId) {
            add("CAN addressing is invalid.")
        }
        if (readableDids.isEmpty() || readableDids.any { it !in 0..0xFFFF }) {
            add("No valid read-only DID allowlist is defined.")
        }
        val requiredIdentityFields = UdsIdentityField.entries.toSet()
        if (identityDids.size != requiredIdentityFields.size ||
            identityDids.map { it.field }.toSet() != requiredIdentityFields) {
            add("Identity DID definitions must cover all six identity fields exactly once.")
        }
        if (identityDids.any { it.did !in 0..0xFFFF || it.did !in readableDids }) {
            add("Identity DIDs must be included in the read-only allowlist.")
        }
        if (identityDids.map { it.did }.distinct().size != identityDids.size) {
            add("Identity DID definitions must not repeat a DID.")
        }
        if (evidenceReference.isBlank()) add("A traceable service-information reference is required.")
    }

    fun compatibilityIssues(vehicle: VehicleProfile, identity: UdsEcuIdentity): List<String> = buildList {
        addAll(compatibilityDefinitionIssues(vehicle))
        if (!identity.controllerFamily.equals(controllerFamily, ignoreCase = true)) {
            add("Controller family does not match.")
        }
        if (identity.hardwarePartNumber.isNullOrBlank() ||
            !identity.hardwarePartNumber.equals(hardwarePartNumber, ignoreCase = true)) {
            add("Hardware part number does not match.")
        }
        if (identity.operatingSystemId.isNullOrBlank() ||
            !identity.operatingSystemId.equals(operatingSystemId, ignoreCase = true)) {
            add("Operating-system ID does not match.")
        }
        if (identity.calibrationId.isNullOrBlank() ||
            !identity.calibrationId.equals(calibrationId, ignoreCase = true)) {
            add("Calibration ID does not match.")
        }
        if (identity.calibrationVerificationNumber.isNullOrBlank() ||
            !identity.calibrationVerificationNumber.equals(calibrationVerificationNumber, ignoreCase = true)) {
            add("CVN does not match.")
        }
        if (identity.vin.isNullOrBlank() ||
            !identity.vin.equals(verifiedVin.trim(), ignoreCase = true)) {
            add("ECU VIN does not match the VIN validated for this profile.")
        }
    }

    fun allowsDid(did: Int): Boolean = did in readableDids
}

/**
 * A verified, read-only UDS session. It is created only after the profile has
 * read back every identity DID and matched exact vehicle/controller identifiers.
 */
class ProfiledUdsReadSession private constructor(
    val profile: DocumentedUdsReadProfile,
    val identity: UdsEcuIdentity,
    private val client: UdsReadOnlyClient
) {
    suspend fun readDid(did: Int): ByteArray {
        require(profile.allowsDid(did)) {
            "DID 0x" + did.toString(16).uppercase(Locale.ROOT) + " is not in this profile's read-only allowlist."
        }
        return client.readDataByIdentifier(did)
    }

    suspend fun readDtcInformation(statusMask: Int = 0xFF): ByteArray =
        client.readDtcInformation(statusMask)

    suspend fun readDtcReport(statusMask: Int = 0xFF): UdsDtcReport =
        client.readDtcReport(statusMask)

    /** Returns ECU-tagged records compatible with the shared diagnostics presentation model. */
    suspend fun readDiagnosticDtcs(statusMask: Int = 0xFF): List<DiagnosticDtc> =
        readDtcReport(statusMask).records.map { it.toDiagnosticDtc(profile.moduleId) }

    companion object {
        suspend fun open(
            transport: RawCanFrameTransport,
            vehicle: VehicleProfile,
            profile: DocumentedUdsReadProfile,
            timeoutMs: Long = 3000L
        ): ProfiledUdsReadSession {
            require(timeoutMs in 1..30_000L) {
                "UDS ECU identification timeout must be between 1 and 30000 ms."
            }
            // Snapshot caller-owned mutable allowlists before any suspending CAN
            // exchange. A concurrent UI/profile edit cannot broaden this session.
            val approved = profile.copy(
                readableDids = profile.readableDids.toSet(),
                identityDids = profile.identityDids.toList()
            )
            val issuesBeforeReading = approved.compatibilityDefinitionIssues(vehicle)
            require(issuesBeforeReading.isEmpty()) {
                "UDS read profile rejected: " + issuesBeforeReading.joinToString("; ")
            }
            val client = UdsReadOnlyClient(
                transport = transport,
                requestCanId = approved.requestCanId,
                responseCanId = approved.responseCanId,
                extendedCanId = approved.extendedCanId,
                timeoutMs = timeoutMs
            )
            val rawIdentity = approved.identityDids.associate { definition ->
                definition.field to client.readDataByIdentifier(definition.did)
            }
            val identity = UdsEcuIdentity(
                controllerFamily = rawIdentity[UdsIdentityField.CONTROLLER_FAMILY]?.let {
                    decodeIdentity(it, approved.identityDids, UdsIdentityField.CONTROLLER_FAMILY)
                }.orEmpty(),
                hardwarePartNumber = rawIdentity[UdsIdentityField.HARDWARE_PART_NUMBER]?.let {
                    decodeIdentity(it, approved.identityDids, UdsIdentityField.HARDWARE_PART_NUMBER)
                },
                operatingSystemId = rawIdentity[UdsIdentityField.OPERATING_SYSTEM_ID]?.let {
                    decodeIdentity(it, approved.identityDids, UdsIdentityField.OPERATING_SYSTEM_ID)
                },
                calibrationId = rawIdentity[UdsIdentityField.CALIBRATION_ID]?.let {
                    decodeIdentity(it, approved.identityDids, UdsIdentityField.CALIBRATION_ID)
                },
                calibrationVerificationNumber = rawIdentity[UdsIdentityField.CALIBRATION_VERIFICATION_NUMBER]?.let {
                    decodeIdentity(it, approved.identityDids, UdsIdentityField.CALIBRATION_VERIFICATION_NUMBER)
                },
                vin = rawIdentity[UdsIdentityField.VIN]?.let {
                    decodeIdentity(it, approved.identityDids, UdsIdentityField.VIN)
                }
            )
            val issues = approved.compatibilityIssues(vehicle, identity)
            require(issues.isEmpty()) {
                "UDS module identity rejected: " + issues.joinToString("; ")
            }
            return ProfiledUdsReadSession(approved, identity, client)
        }

        private fun decodeIdentity(
            bytes: ByteArray,
            definitions: List<UdsIdentityDidDefinition>,
            field: UdsIdentityField
        ): String {
            val encoding = definitions.single { it.field == field }.encoding
            return when (encoding) {
                UdsIdentityEncoding.ASCII -> {
                    var nullPaddingStarted = false
                    require(bytes.isNotEmpty() && bytes.all { byte ->
                        val value = byte.toInt() and 0xFF
                        if (value == 0) {
                            nullPaddingStarted = true
                            true
                        } else {
                            !nullPaddingStarted && value in 0x20..0x7E
                        }
                    }) {
                        "ECU identity contains non-ASCII bytes or invalid NUL padding."
                    }
                    bytes.toString(Charsets.US_ASCII)
                        .trim()
                        .trim('\u0000')
                        .uppercase(Locale.ROOT)
                }
                UdsIdentityEncoding.HEX -> {
                    require(bytes.isNotEmpty()) { "ECU identity hex field is empty." }
                    bytes.joinToString("") {
                        "%02X".format(Locale.ROOT, it.toInt() and 0xFF)
                    }
                }
            }
        }
    }
}

data class UdsEcuIdentity(
    val controllerFamily: String,
    val hardwarePartNumber: String?,
    val operatingSystemId: String?,
    val calibrationId: String?,
    val calibrationVerificationNumber: String?,
    val vin: String?
)
