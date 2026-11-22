package com.camarodiag.gen5.data.obd

import java.util.Locale

/**
 * Explicit UDS permissions. The default policy is read-only.
 *
 * A write/programming grant must be created by the trusted profile-validation path after
 * vehicle VIN, ECU identity, CAL ID/CVN and documentary evidence have been checked.
 * No GM write profile is bundled until those values and procedures are documented.
 */
@ConsistentCopyVisibility
data class UdsAccessPolicy internal constructor(
    val readDids: Set<Int>,
    val writeDids: Set<Int>,
    val allowedSessions: Set<Int>,
    val routineControlPermissions: Map<Int, Set<Int>>,
    val ioControlPermissions: Map<Int, Set<Int>>,
    val allowedSecurityLevels: Set<Int>,
    val allowedCommunicationControls: Set<Pair<Int, Int>>,
    val allowedDtcSettingTypes: Set<Int>,
    val allowedDtcGroups: Set<Int>,
    val allowedEcuResetTypes: Set<Int>,
    val readableMemoryRegions: List<UdsMemoryRegion>,
    val writableMemoryRegions: List<UdsMemoryRegion>,
    val maxTransferBytes: Int,
    val requireSecurityAccess: Boolean,
    val evidenceReference: String
) {
    companion object {
        // All published policy collections must be immutable, even when the
        // caller retains a mutable source set/map or casts a returned Set.
        // Authorization is checked against these snapshots, never live UI data.
        private fun <T> frozenSet(items: Collection<T>): Set<T> =
            java.util.Collections.unmodifiableSet(LinkedHashSet(items))

        private fun <T> frozenList(items: Collection<T>): List<T> =
            java.util.Collections.unmodifiableList(ArrayList(items))

        private fun <K, V> frozenMapOfSets(items: Map<K, Set<V>>): Map<K, Set<V>> =
            java.util.Collections.unmodifiableMap(
                items.mapValues { (_, allowed) -> frozenSet(allowed) }
            )

        private fun validEcuIdentifier(value: String?): Boolean {
            val normalized = value?.trim()?.uppercase(Locale.ROOT).orEmpty()
            if (normalized.isBlank() || normalized in setOf(
                    "UNKNOWN", "NONE", "N/A", "NULL", "PENDING", "TBD",
                    "NO DATA", "NO RESPONSE", "INVALID", "ERROR", "TEST",
                    "SIMULATED", "NOT SUPPORTED"
                )) return false
            val compact = normalized.removePrefix("0X").filter(Char::isLetterOrDigit)
            return compact.isNotEmpty() && !compact.all { it == '0' } &&
                !compact.all { it == 'F' }
        }

        fun readOnly(readDids: Set<Int> = emptySet()) = UdsAccessPolicy(
            readDids = frozenSet(readDids),
            writeDids = emptySet(),
            allowedSessions = setOf(0x01),
            routineControlPermissions = emptyMap(),
            ioControlPermissions = emptyMap(),
            allowedSecurityLevels = emptySet(),
            allowedCommunicationControls = emptySet(),
            allowedDtcSettingTypes = emptySet(),
            allowedDtcGroups = emptySet(),
            allowedEcuResetTypes = emptySet(),
            readableMemoryRegions = emptyList(),
            writableMemoryRegions = emptyList(),
            maxTransferBytes = 0,
            requireSecurityAccess = true,
            evidenceReference = "Built-in read-only policy"
        )

        internal fun verified(
            vehicleVin: String,
            identity: UdsEcuIdentity,
            expectedVin: String,
            expectedCalId: String,
            expectedCvn: String,
            expectedControllerFamily: String,
            expectedHardwarePartNumber: String,
            expectedOperatingSystemId: String,
            readDids: Set<Int>,
            writeDids: Set<Int>,
            allowedSessions: Set<Int>,
            routineControlPermissions: Map<Int, Set<Int>>,
            ioControlPermissions: Map<Int, Set<Int>> = emptyMap(),
            allowedSecurityLevels: Set<Int> = emptySet(),
            allowedCommunicationControls: Set<Pair<Int, Int>> = emptySet(),
            allowedDtcSettingTypes: Set<Int> = emptySet(),
            allowedDtcGroups: Set<Int> = emptySet(),
            allowedEcuResetTypes: Set<Int> = emptySet(),
            readableMemoryRegions: List<UdsMemoryRegion>,
            writableMemoryRegions: List<UdsMemoryRegion>,
            maxTransferBytes: Int,
            requireSecurityAccess: Boolean,
            evidenceReference: String
        ): UdsAccessPolicy {
            require(vehicleVin.matches(Regex("[A-HJ-NPR-Z0-9]{17}")) &&
                vehicleVin.equals(expectedVin, true) &&
                identity.vin.equals(expectedVin, true)) { "VIN verification failed." }
            require(identity.calibrationId.equals(expectedCalId, true)) { "CAL ID verification failed." }
            require(identity.calibrationVerificationNumber.equals(expectedCvn, true)) { "CVN verification failed." }
            require(identity.controllerFamily.equals(expectedControllerFamily, true)) { "Controller family verification failed." }
            require(identity.hardwarePartNumber.equals(expectedHardwarePartNumber, true)) { "Hardware part number verification failed." }
            require(identity.operatingSystemId.equals(expectedOperatingSystemId, true)) { "Operating-system verification failed." }
            require(listOf(expectedCalId, expectedCvn, expectedControllerFamily,
                    expectedHardwarePartNumber, expectedOperatingSystemId,
                    identity.calibrationId, identity.calibrationVerificationNumber,
                    identity.controllerFamily, identity.hardwarePartNumber,
                    identity.operatingSystemId).all(::validEcuIdentifier)) {
                "Placeholder or invalid ECU identity cannot authorize programming."
            }
            require(evidenceReference.isNotBlank()) { "Traceable OEM evidence is required." }
            // Freeze *before* validating: source collections may be mutable and
            // must not be able to widen later, even via their nested map sets.
            val safeReadDids = frozenSet(readDids)
            val safeWriteDids = frozenSet(writeDids)
            val safeSessions = frozenSet(allowedSessions)
            val safeRoutineControls = frozenMapOfSets(routineControlPermissions)
            val safeIoControls = frozenMapOfSets(ioControlPermissions)
            val safeSecurityLevels = frozenSet(allowedSecurityLevels)
            val safeCommunicationControls = frozenSet(allowedCommunicationControls)
            val safeDtcSettings = frozenSet(allowedDtcSettingTypes)
            val safeDtcGroups = frozenSet(allowedDtcGroups)
            val safeEcuResets = frozenSet(allowedEcuResetTypes)
            val safeReadableRegions = frozenList(readableMemoryRegions)
            val safeWritableRegions = frozenList(writableMemoryRegions)
            require(safeReadDids.all { it in 0..0xFFFF } &&
                safeWriteDids.all { it in 0..0xFFFF })
            require(safeSessions.isNotEmpty() && safeSessions.all { it in 1..0x7F })
            require(safeRoutineControls.all { (id, types) -> id in 0..0xFFFF && types.isNotEmpty() && types.all { it in 1..3 } })
            require(safeIoControls.all { (id, types) -> id in 0..0xFFFF && types.isNotEmpty() && types.all { it in 0..0xFF } })
            require(safeSecurityLevels.all { it in 1..0x7D && it % 2 == 1 })
            require(safeCommunicationControls.all { (type, communication) -> type in 0..3 && communication in 0..0xFF })
            require(safeDtcSettings.all { it in 1..2 })
            require(safeDtcGroups.all { it in 0..0xFFFFFF })
            require(safeEcuResets.all { it in 1..0x7F })
            require(maxTransferBytes >= 0)
            val mutatingCapabilitiesGranted = safeWriteDids.isNotEmpty() ||
                safeWritableRegions.isNotEmpty() || safeRoutineControls.isNotEmpty() ||
                safeIoControls.isNotEmpty() || safeCommunicationControls.isNotEmpty() ||
                safeDtcSettings.isNotEmpty() || safeDtcGroups.isNotEmpty() ||
                safeEcuResets.isNotEmpty()
            require(!mutatingCapabilitiesGranted || requireSecurityAccess) {
                "A verified ECU policy cannot grant state-changing operations without SecurityAccess."
            }
            require(!mutatingCapabilitiesGranted || safeSecurityLevels.isNotEmpty()) {
                "State-changing ECU permissions require an explicit SecurityAccess level."
            }
            return UdsAccessPolicy(
                safeReadDids, safeWriteDids, safeSessions, safeRoutineControls, safeIoControls,
                safeSecurityLevels, safeCommunicationControls, safeDtcSettings,
                safeDtcGroups, safeEcuResets, safeReadableRegions, safeWritableRegions,
                maxTransferBytes, requireSecurityAccess, evidenceReference
            )
        }
    }
}

data class UdsMemoryRegion(val startAddress: Long, val length: Long) {
    init {
        require(startAddress >= 0 && length > 0 && startAddress <= 0xFFFFFFFFL)
        require(length <= 0x1_0000_0000L - startAddress)
    }

    fun contains(address: Long, size: Long): Boolean =
        address >= startAddress && size > 0 && address <= startAddress + length - size
}

data class UdsDtcRecord(
    val dtc: Int,
    val status: Int,
    val raw: ByteArray
)

/**
 * ISO 14229-1 client over ISO 15765-2 / Classical CAN.
 * Read services are available by default. Any state-changing service requires an
 * explicit, identity-bound policy; no seed/key algorithm or GM address is guessed.
 */
class UdsClient private constructor(
    private val channel: UdsPayloadTransport,
    private val policy: UdsAccessPolicy,
    private val timeoutMs: Long,
    @Suppress("UNUSED_PARAMETER") marker: Boolean
) {
    constructor(
        transport: RawCanFrameTransport,
        requestCanId: Int,
        responseCanId: Int,
        extendedCanId: Boolean = false,
        policy: UdsAccessPolicy = UdsAccessPolicy.readOnly(),
        timeoutMs: Long = 3000L
    ) : this(IsoTpUdsPayloadTransport(transport, requestCanId, responseCanId, extendedCanId), policy, timeoutMs, true)

    constructor(
        payloadTransport: UdsPayloadTransport,
        policy: UdsAccessPolicy = UdsAccessPolicy.readOnly(),
        timeoutMs: Long = 3000L
    ) : this(payloadTransport, policy, timeoutMs, true)

    init {
        require(timeoutMs in 1..30_000L) { "UDS timeout must be between 1 and 30000 ms." }
    }

    private var activeSession = 0x01
    private val requestedSecuritySeeds = mutableSetOf<Int>()
    private var securityUnlocked = false
    private var downloadMaxBlockLength = 0
    private var transferSequence = 1
    private var downloadActive = false
    private var transferredBytes = 0
    private var expectedTransferBytes = 0L
    private var uploadActive = false

    suspend fun diagnosticSessionControl(session: Int): ByteArray {
        require(session in policy.allowedSessions) { "Diagnostic session is not allowed by the verified ECU profile." }
        val response = request(0x10, byteArrayOf(session.toByte()))
        // ISO 14229 positive session response: SID + echoed subfunction,
        // optionally followed by the complete four-byte P2/P2* timing tuple.
        // A partial timing tuple cannot authorize later security/write calls.
        require(response.size == 2 || response.size == 6) {
            "ECU returned a truncated or overlong diagnostic session acknowledgement."
        }
        require((response[1].toInt() and 0xFF) == session) {
            "ECU did not echo the requested diagnostic session."
        }
        activeSession = session
        securityUnlocked = false
        requestedSecuritySeeds.clear()
        return response.copyOfRange(2, response.size)
    }

    suspend fun testerPresent(suppressPositiveResponse: Boolean = false) {
        val subFunction = if (suppressPositiveResponse) 0x80 else 0x00
        if (suppressPositiveResponse) {
            channel.sendOnly(byteArrayOf(0x3E, subFunction.toByte()), timeoutMs)
        } else {
            val response = request(0x3E, byteArrayOf(subFunction.toByte()))
            require(response.size >= 2 && (response[1].toInt() and 0x7F) == 0)
        }
    }

    suspend fun readDataByIdentifier(did: Int): ByteArray {
        require(did in 0..0xFFFF)
        require(policy.readDids.isEmpty() || did in policy.readDids) { "DID is not in the read allowlist." }
        val response = request(0x22, byteArrayOf((did shr 8).toByte(), did.toByte()))
        require(response.size >= 3 && readU16(response, 1) == did) { "ECU returned a different DID." }
        return response.copyOfRange(3, response.size)
    }

    suspend fun writeDataByIdentifier(did: Int, data: ByteArray) {
        require(did in policy.writeDids) { "DID write is not allowed by the verified ECU profile." }
        require(data.isNotEmpty() && data.size <= 0x0FFF - 3)
        requireProgrammingSession()
        requireSecurityIfConfigured()
        val response = request(0x2E, byteArrayOf((did shr 8).toByte(), did.toByte()) + data)
        require(response.size >= 3 && readU16(response, 1) == did) { "ECU did not confirm the written DID." }
    }

    suspend fun readDtcInformation(subFunction: Int, parameters: ByteArray = byteArrayOf()): ByteArray {
        require(subFunction in 0..0x7F)
        val response = request(0x19, byteArrayOf(subFunction.toByte()) + parameters)
        require(response.size >= 2 && (response[1].toInt() and 0x7F) == subFunction) {
            "ECU did not echo the requested DTC subfunction."
        }
        return response.copyOfRange(2, response.size)
    }

    suspend fun readDtcReport(statusMask: Int = 0xFF): UdsDtcReport {
        require(statusMask in 0..0xFF)
        val data = readDtcInformation(0x02, byteArrayOf(statusMask.toByte()))
        return UdsDtcDecoder.decodeReadDtcInformation(data)
    }

    suspend fun readDtcByStatusMask(statusMask: Int = 0xFF): List<UdsDtcRecord> {
        val report = readDtcReport(statusMask)
        return report.records.map { record ->
            val rawBytes = byteArrayOf(
                record.rawDtc.ushr(16).toByte(),
                record.rawDtc.ushr(8).toByte(),
                record.rawDtc.toByte(),
                record.statusByte.toByte()
            )
            UdsDtcRecord(
                dtc = record.rawDtc,
                status = record.statusByte,
                raw = byteArrayOf(
                    report.statusAvailabilityMask.toByte(),
                    report.formatIdentifier.toByte()
                ) + rawBytes
            )
        }
    }

    suspend fun clearDiagnosticInformation(groupOfDtc: Int = 0xFFFFFF) {
        require(groupOfDtc in policy.allowedDtcGroups) { "DTC group is not allowlisted for this ECU." }
        requireProgrammingSession()
        requireSecurityIfConfigured()
        request(0x14, byteArrayOf(
            (groupOfDtc shr 16).toByte(), (groupOfDtc shr 8).toByte(), groupOfDtc.toByte()
        ))
    }

    suspend fun ecuReset(resetType: Int) {
        require(resetType in policy.allowedEcuResetTypes) { "ECU reset type is not allowlisted for this ECU." }
        requireProgrammingSession()
        requireSecurityIfConfigured()
        val response = request(0x11, byteArrayOf(resetType.toByte()))
        require(response.size >= 2 && (response[1].toInt() and 0x7F) == resetType) { "ECU did not echo reset type." }
        activeSession = 0x01
        securityUnlocked = false
        downloadActive = false
    }

    /** Requests a seed. The app deliberately does not implement or guess OEM key algorithms. */
    suspend fun requestSecuritySeed(level: Int): ByteArray {
        require(level in policy.allowedSecurityLevels) { "Security level is not allowlisted for this ECU." }
        require(level in 1..0x7D && level % 2 == 1)
        require(activeSession != 0x01) { "Security access requires a non-default diagnostic session." }
        val response = request(0x27, byteArrayOf(level.toByte()))
        require(response.size >= 2 && (response[1].toInt() and 0x7F) == level)
        requestedSecuritySeeds += level
        return response.copyOfRange(2, response.size)
    }

    /** Sends a key generated by an authorized OEM algorithm supplied outside this project. */
    suspend fun submitSecurityKey(level: Int, key: ByteArray) {
        require(level in 2..0x7E && level % 2 == 0 && level - 1 in policy.allowedSecurityLevels)
        require(level - 1 in requestedSecuritySeeds) { "A matching security seed has not been requested." }
        require(key.isNotEmpty() && key.size <= 0x0FFF - 2)
        val response = request(0x27, byteArrayOf(level.toByte()) + key)
        require(response.size >= 2 && (response[1].toInt() and 0x7F) == level)
        securityUnlocked = true
        requestedSecuritySeeds.remove(level - 1)
    }

    suspend fun routineControl(controlType: Int, routineId: Int, parameters: ByteArray = byteArrayOf()): ByteArray {
        require(controlType in 1..3)
        require(controlType in policy.routineControlPermissions[routineId].orEmpty()) {
            "Routine/control-type pair is not allowlisted for this ECU."
        }
        requireProgrammingSession()
        requireSecurityIfConfigured()
        val requestData = byteArrayOf(
            controlType.toByte(), (routineId shr 8).toByte(), routineId.toByte()
        ) + parameters
        val response = request(0x31, requestData)
        require(response.size >= 4 && (response[1].toInt() and 0x7F) == controlType && readU16(response, 2) == routineId)
        return response.copyOfRange(4, response.size)
    }

    suspend fun readMemoryByAddressAndLength(address: Long, size: Int, addressLength: Int = 4, sizeLength: Int = 2): ByteArray {
        requireAllowedMemory(address, size.toLong(), policy.readableMemoryRegions)
        requireProgrammingSession()
        requireSecurityIfConfigured()
        require(size in 1..4092 && size <= policy.maxTransferBytes) {
            "Memory read length exceeds the verified profile limit."
        }
        // ISO 14229 ALFI: upper nibble = memory size width, lower nibble = address width.
        val format = sizeLength shl 4 or addressLength
        val requestData = byteArrayOf(format.toByte()) + encodeUnsigned(address, addressLength) + encodeUnsigned(size.toLong(), sizeLength)
        val response = request(0x23, requestData)
        require(response.size == size + 1) { "ECU memory read is incomplete or longer than requested." }
        return response.copyOfRange(1, response.size)
    }

    suspend fun writeMemoryByAddressAndLength(address: Long, data: ByteArray, addressLength: Int = 4, sizeLength: Int = 2) {
        requireAllowedMemory(address, data.size.toLong(), policy.writableMemoryRegions)
        require(data.isNotEmpty() && data.size <= policy.maxTransferBytes &&
            data.size + 2 + addressLength + sizeLength <= 4095)
        requireProgrammingSession()
        requireSecurityIfConfigured()
        // ISO 14229 ALFI: upper nibble = memory size width, lower nibble = address width.
        val format = sizeLength shl 4 or addressLength
        val requestData = byteArrayOf(format.toByte()) + encodeUnsigned(address, addressLength) +
            encodeUnsigned(data.size.toLong(), sizeLength) + data
        request(0x3D, requestData)
    }

    suspend fun requestDownload(address: Long, size: Long, dataFormatIdentifier: Int = 0, addressLength: Int = 4, sizeLength: Int = 4) {
        require(!downloadActive && !uploadActive) { "A data transfer is already active." }
        requireAllowedMemory(address, size, policy.writableMemoryRegions)
        require(size <= policy.maxTransferBytes.toLong()) { "Transfer exceeds the verified profile limit." }
        requireProgrammingSession()
        requireSecurityIfConfigured()
        require(dataFormatIdentifier in 0..0xFF)
        // ISO 14229 ALFI: upper nibble = memory size width, lower nibble = address width.
        val format = sizeLength shl 4 or addressLength
        val response = request(
            0x34,
            byteArrayOf(dataFormatIdentifier.toByte(), format.toByte()) +
                encodeUnsigned(address, addressLength) + encodeUnsigned(size, sizeLength)
        )
        require(response.size >= 3) { "Incomplete RequestDownload response." }
        val lengthFormat = response[1].toInt() and 0xFF
        // ISO 14229 lengthFormatIdentifier: upper nibble declares block-length bytes.
        val lengthBytes = lengthFormat ushr 4
        require(lengthFormat and 0x0F == 0) { "Reserved LFI bits must be zero." }
        require(lengthBytes in 1..4 && response.size == 2 + lengthBytes) {
            "ECU transfer block length response is truncated or contains unexpected bytes."
        }
        val maxBlockLength = readUnsigned(response, 2, lengthBytes)
        require(maxBlockLength in 3L..4095L) { "ECU block length exceeds supported UDS framing." }
        downloadMaxBlockLength = maxBlockLength.toInt()
        require(downloadMaxBlockLength > 2) { "ECU returned an invalid maximum block length." }
        transferSequence = 1
        transferredBytes = 0
        expectedTransferBytes = size
        downloadActive = true
    }

    suspend fun transferData(block: ByteArray) {
        require(downloadActive) { "No RequestDownload is active." }
        require(block.isNotEmpty() && block.size <= downloadMaxBlockLength - 2)
        require(transferredBytes.toLong() + block.size <= expectedTransferBytes) { "Transfer exceeds the requested ECU range length." }
        require(transferredBytes.toLong() + block.size <= policy.maxTransferBytes.toLong())
        val response = request(0x36, byteArrayOf(transferSequence.toByte()) + block)
        require(response.size >= 2 && (response[1].toInt() and 0xFF) == transferSequence) {
            "ECU did not acknowledge the transfer block sequence."
        }
        transferredBytes += block.size
        transferSequence = (transferSequence + 1) and 0xFF
    }

    suspend fun requestUpload(address: Long, size: Long, dataFormatIdentifier: Int = 0, addressLength: Int = 4, sizeLength: Int = 4) {
        require(!downloadActive && !uploadActive) { "A data transfer is already active." }
        requireAllowedMemory(address, size, policy.readableMemoryRegions)
        require(size <= policy.maxTransferBytes.toLong()) { "Transfer exceeds the verified profile limit." }
        requireProgrammingSession()
        requireSecurityIfConfigured()
        require(dataFormatIdentifier in 0..0xFF)
        // ISO 14229 ALFI: upper nibble = memory size width, lower nibble = address width.
        val format = sizeLength shl 4 or addressLength
        val response = request(0x35, byteArrayOf(dataFormatIdentifier.toByte(), format.toByte()) +
            encodeUnsigned(address, addressLength) + encodeUnsigned(size, sizeLength))
        require(response.size >= 3) { "Incomplete RequestUpload response." }
        val lengthFormat = response[1].toInt() and 0xFF
        val lengthBytes = lengthFormat ushr 4
        require(lengthFormat and 0x0F == 0) { "Reserved LFI bits must be zero." }
        require(lengthBytes in 1..4 && response.size == 2 + lengthBytes) {
            "ECU transfer block length response is truncated or contains unexpected bytes."
        }
        val maxBlockLength = readUnsigned(response, 2, lengthBytes)
        require(maxBlockLength in 3L..4095L) { "ECU block length exceeds supported UDS framing." }
        downloadMaxBlockLength = maxBlockLength.toInt()
        require(downloadMaxBlockLength > 2) { "ECU returned an invalid maximum upload block length." }
        transferSequence = 1
        transferredBytes = 0
        expectedTransferBytes = size
        uploadActive = true
    }

    suspend fun requestUploadData(): ByteArray {
        require(uploadActive) { "No RequestUpload is active." }
        require(transferredBytes.toLong() < expectedTransferBytes) { "The requested upload range is already complete." }
        val response = request(0x36, byteArrayOf(transferSequence.toByte()))
        require(response.size >= 2 && (response[1].toInt() and 0xFF) == transferSequence) {
            "ECU did not acknowledge the upload block sequence."
        }
        val data = response.copyOfRange(2, response.size)
        require(data.isNotEmpty() && data.size <= downloadMaxBlockLength - 2)
        require(transferredBytes.toLong() + data.size <= expectedTransferBytes)
        transferredBytes += data.size
        transferSequence = (transferSequence + 1) and 0xFF
        return data
    }

    suspend fun inputOutputControlByIdentifier(did: Int, controlParameter: Int, data: ByteArray = byteArrayOf()): ByteArray {
        require(controlParameter in policy.ioControlPermissions[did].orEmpty()) {
            "I/O control DID/parameter pair is not allowlisted for this ECU."
        }
        requireProgrammingSession()
        requireSecurityIfConfigured()
        val response = request(0x2F, byteArrayOf((did shr 8).toByte(), did.toByte(), controlParameter.toByte()) + data)
        require(response.size >= 4 && readU16(response, 1) == did &&
            (response[3].toInt() and 0xFF) == controlParameter) { "ECU did not confirm I/O control." }
        return response.copyOfRange(4, response.size)
    }

    suspend fun communicationControl(controlType: Int, communicationType: Int) {
        require((controlType to communicationType) in policy.allowedCommunicationControls) {
            "Communication control type is not allowlisted for this ECU."
        }
        requireProgrammingSession()
        requireSecurityIfConfigured()
        val response = request(0x28, byteArrayOf(controlType.toByte(), communicationType.toByte()))
        require(response.size >= 3 && (response[1].toInt() and 0x7F) == controlType &&
            (response[2].toInt() and 0xFF) == communicationType)
    }

    suspend fun controlDtcSetting(settingType: Int, record: ByteArray = byteArrayOf()) {
        require(settingType in policy.allowedDtcSettingTypes) { "DTC setting type is not allowlisted for this ECU." }
        requireProgrammingSession()
        requireSecurityIfConfigured()
        val response = request(0x85, byteArrayOf(settingType.toByte()) + record)
        require(response.size >= 2 && (response[1].toInt() and 0x7F) == settingType)
    }

    suspend fun requestTransferExit(parameters: ByteArray = byteArrayOf()) {
        require(downloadActive || uploadActive) { "No data transfer is active." }
        if (downloadActive) require(transferredBytes.toLong() == expectedTransferBytes) { "Transferred byte count does not match the requested download length." }
        if (uploadActive) require(transferredBytes.toLong() == expectedTransferBytes) { "Uploaded byte count does not match the requested upload length." }
        request(0x37, parameters)
        downloadActive = false
        uploadActive = false
        downloadMaxBlockLength = 0
        expectedTransferBytes = 0
    }

    fun isDownloadActive(): Boolean = downloadActive
    fun isUploadActive(): Boolean = uploadActive
    fun isSecurityUnlocked(): Boolean = securityUnlocked
    fun activeDiagnosticSession(): Int = activeSession

    private suspend fun request(service: Int, data: ByteArray): ByteArray {
        val response = channel.exchange(byteArrayOf(service.toByte()) + data, timeoutMs)
        require(response.isNotEmpty()) { "ECU returned an empty UDS response." }
        val responseService = response[0].toInt() and 0xFF
        if (responseService == 0x7F) {
            require(response.size >= 3) { "Incomplete UDS negative response." }
            throw UdsNegativeResponseException(response[1].toInt() and 0xFF, response[2].toInt() and 0xFF)
        }
        require(responseService == (service + 0x40)) {
            "Unexpected UDS response service 0x" + responseService.toString(16).uppercase(Locale.ROOT)
        }
        return response
    }

    private fun requireProgrammingSession() {
        require(activeSession != 0x01 && activeSession in policy.allowedSessions) {
            "A verified non-default diagnostic session is required for this operation."
        }
    }

    private fun requireSecurityIfConfigured() {
        if (policy.requireSecurityAccess) require(securityUnlocked) {
            "This operation requires an accepted SecurityAccess exchange."
        }
    }

    private fun requireAllowedMemory(address: Long, size: Long, regions: List<UdsMemoryRegion>) {
        require(regions.any { it.contains(address, size) }) {
            "Memory range is outside the verified ECU profile."
        }
    }

    private fun encodeUnsigned(value: Long, bytes: Int): ByteArray {
        require(bytes in 1..4 && value >= 0)
        val max = (1L shl (bytes * 8)) - 1L
        require(value <= max)
        return ByteArray(bytes) { index ->
            (value shr ((bytes - index - 1) * 8)).toByte()
        }
    }

    private fun readUnsigned(bytes: ByteArray, offset: Int, count: Int): Long {
        require(count in 1..4 && offset >= 0 && offset + count <= bytes.size)
        var result = 0L
        repeat(count) { result = (result shl 8) or (bytes[offset + it].toLong() and 0xFF) }
        return result
    }

    private fun readU16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
}
