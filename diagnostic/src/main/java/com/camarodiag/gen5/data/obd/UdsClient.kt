package com.camarodiag.gen5.data.obd

import java.util.Locale

/**
 * Explicit UDS permissions. The default policy is read-only.
 *
 * A write/programming grant must be created by the trusted profile-validation path after
 * vehicle VIN, ECU identity, CAL ID/CVN and documentary evidence have been checked.
 * No GM write profile is bundled until those values and procedures are documented.
 */
data class UdsAccessPolicy internal constructor(
    val readDids: Set<Int>,
    val writeDids: Set<Int>,
    val allowedSessions: Set<Int>,
    val allowedRoutineIds: Set<Int>,
    val ioControlDids: Set<Int>,
    val allowedSecurityLevels: Set<Int>,
    val allowCommunicationControl: Boolean,
    val allowDtcSetting: Boolean,
    val memoryRegions: List<UdsMemoryRegion>,
    val maxTransferBytes: Int,
    val allowClearDtc: Boolean,
    val allowEcuReset: Boolean,
    val requireSecurityAccess: Boolean,
    val evidenceReference: String
) {
    companion object {
        fun readOnly(readDids: Set<Int> = emptySet()) = UdsAccessPolicy(
            readDids = readDids,
            writeDids = emptySet(),
            allowedSessions = setOf(0x01),
            allowedRoutineIds = emptySet(),
            ioControlDids = emptySet(),
            allowedSecurityLevels = emptySet(),
            allowCommunicationControl = false,
            allowDtcSetting = false,
            memoryRegions = emptyList(),
            maxTransferBytes = 0,
            allowClearDtc = false,
            allowEcuReset = false,
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
            allowedRoutineIds: Set<Int>,
            ioControlDids: Set<Int> = emptySet(),
            allowedSecurityLevels: Set<Int> = emptySet(),
            allowCommunicationControl: Boolean = false,
            allowDtcSetting: Boolean = false,
            memoryRegions: List<UdsMemoryRegion>,
            maxTransferBytes: Int,
            allowClearDtc: Boolean,
            allowEcuReset: Boolean,
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
            require(evidenceReference.isNotBlank()) { "Traceable OEM evidence is required." }
            require(readDids.all { it in 0..0xFFFF } && writeDids.all { it in 0..0xFFFF })
            require(allowedSessions.isNotEmpty() && allowedSessions.all { it in 1..0x7F })
            require(allowedRoutineIds.all { it in 0..0xFFFF } && ioControlDids.all { it in 0..0xFFFF })
            require(allowedSecurityLevels.all { it in 1..0x7D && it % 2 == 1 })
            require(maxTransferBytes >= 0)
            return UdsAccessPolicy(
                readDids, writeDids, allowedSessions, allowedRoutineIds, ioControlDids,
                allowedSecurityLevels, allowCommunicationControl, allowDtcSetting, memoryRegions,
                maxTransferBytes, allowClearDtc, allowEcuReset, requireSecurityAccess, evidenceReference
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

data class UdsResponse(val service: Int, val payload: ByteArray)

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
class UdsClient(
    transport: RawCanFrameTransport,
    requestCanId: Int,
    responseCanId: Int,
    extendedCanId: Boolean = false,
    private val policy: UdsAccessPolicy = UdsAccessPolicy.readOnly(),
    private val timeoutMs: Long = 3000L
) {
    private val channel = IsoTpChannel(transport, requestCanId, responseCanId, extendedCanId)
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
        require(response.size >= 2 && (response[1].toInt() and 0x7F) == session) {
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

    suspend fun readDtcByStatusMask(statusMask: Int = 0xFF): List<UdsDtcRecord> {
        require(statusMask in 0..0xFF)
        val data = readDtcInformation(0x02, byteArrayOf(statusMask.toByte()))
        require(data.isNotEmpty()) { "Incomplete ReadDTCInformation response." }
        val availabilityMask = data[0].toInt() and 0xFF
        val records = data.copyOfRange(1, data.size)
        require(records.size % 4 == 0) { "DTC record payload is not a multiple of four bytes." }
        return records.asList().chunked(4).map { record ->
            val raw = record.map { it.toByte() }.toByteArray()
            UdsDtcRecord(
                dtc = ((raw[0].toInt() and 0xFF) shl 16) or
                    ((raw[1].toInt() and 0xFF) shl 8) or (raw[2].toInt() and 0xFF),
                status = raw[3].toInt() and 0xFF,
                raw = byteArrayOf(availabilityMask.toByte()) + raw
            )
        }
    }

    suspend fun clearDiagnosticInformation(groupOfDtc: Int = 0xFFFFFF) {
        require(policy.allowClearDtc) { "DTC clearing is disabled by the ECU profile." }
        require(groupOfDtc in 0..0xFFFFFF)
        requireProgrammingSession()
        requireSecurityIfConfigured()
        request(0x14, byteArrayOf(
            (groupOfDtc shr 16).toByte(), (groupOfDtc shr 8).toByte(), groupOfDtc.toByte()
        ))
    }

    suspend fun ecuReset(resetType: Int) {
        require(policy.allowEcuReset) { "ECU reset is disabled by the ECU profile." }
        require(resetType in 1..0x7F)
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
        require(routineId in policy.allowedRoutineIds) { "Routine is not allowlisted for this ECU." }
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
        requireAllowedMemory(address, size.toLong())
        require(size in 1..4092) { "Memory read length exceeds the supported single UDS response limit." }
        val format = addressLength shl 4 or sizeLength
        val requestData = byteArrayOf(format.toByte()) + encodeUnsigned(address, addressLength) + encodeUnsigned(size.toLong(), sizeLength)
        val response = request(0x23, requestData)
        return response.copyOfRange(1, response.size)
    }

    suspend fun writeMemoryByAddressAndLength(address: Long, data: ByteArray, addressLength: Int = 4, sizeLength: Int = 2) {
        requireAllowedMemory(address, data.size.toLong())
        require(data.isNotEmpty() && data.size <= 4090)
        requireProgrammingSession()
        requireSecurityIfConfigured()
        val format = addressLength shl 4 or sizeLength
        val requestData = byteArrayOf(format.toByte()) + encodeUnsigned(address, addressLength) +
            encodeUnsigned(data.size.toLong(), sizeLength) + data
        request(0x3D, requestData)
    }

    suspend fun requestDownload(address: Long, size: Long, dataFormatIdentifier: Int = 0, addressLength: Int = 4, sizeLength: Int = 4) {
        require(!downloadActive && !uploadActive) { "A data transfer is already active." }
        requireAllowedMemory(address, size)
        require(size <= policy.maxTransferBytes.toLong()) { "Transfer exceeds the verified profile limit." }
        requireProgrammingSession()
        requireSecurityIfConfigured()
        require(dataFormatIdentifier in 0..0xFF)
        val format = addressLength shl 4 or sizeLength
        val response = request(
            0x34,
            byteArrayOf(dataFormatIdentifier.toByte(), format.toByte()) +
                encodeUnsigned(address, addressLength) + encodeUnsigned(size, sizeLength)
        )
        require(response.size >= 3) { "Incomplete RequestDownload response." }
        val lengthFormat = response[1].toInt() and 0xFF
        val lengthBytes = lengthFormat and 0x0F
        require(lengthBytes in 1..4 && response.size >= 2 + lengthBytes)
        downloadMaxBlockLength = readUnsigned(response, 2, lengthBytes).toInt()
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
        requireAllowedMemory(address, size)
        require(size <= policy.maxTransferBytes.toLong()) { "Transfer exceeds the verified profile limit." }
        requireProgrammingSession()
        requireSecurityIfConfigured()
        require(dataFormatIdentifier in 0..0xFF)
        val format = addressLength shl 4 or sizeLength
        val response = request(0x35, byteArrayOf(dataFormatIdentifier.toByte(), format.toByte()) +
            encodeUnsigned(address, addressLength) + encodeUnsigned(size, sizeLength))
        require(response.size >= 3) { "Incomplete RequestUpload response." }
        val lengthBytes = response[1].toInt() and 0x0F
        require(lengthBytes in 1..4 && response.size >= 2 + lengthBytes)
        downloadMaxBlockLength = readUnsigned(response, 2, lengthBytes).toInt()
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
        require(did in policy.ioControlDids) { "I/O control DID is not allowlisted for this ECU." }
        require(controlParameter in 0..0xFF)
        requireProgrammingSession()
        requireSecurityIfConfigured()
        val response = request(0x2F, byteArrayOf((did shr 8).toByte(), did.toByte(), controlParameter.toByte()) + data)
        require(response.size >= 4 && readU16(response, 1) == did &&
            (response[3].toInt() and 0xFF) == controlParameter) { "ECU did not confirm I/O control." }
        return response.copyOfRange(4, response.size)
    }

    suspend fun communicationControl(controlType: Int, communicationType: Int) {
        require(policy.allowCommunicationControl) { "Communication control is disabled by the ECU profile." }
        require(controlType in 0..3 && communicationType in 0..0xFF)
        requireProgrammingSession()
        requireSecurityIfConfigured()
        val response = request(0x28, byteArrayOf(controlType.toByte(), communicationType.toByte()))
        require(response.size >= 3 && (response[1].toInt() and 0x7F) == controlType &&
            (response[2].toInt() and 0xFF) == communicationType)
    }

    suspend fun controlDtcSetting(settingType: Int, record: ByteArray = byteArrayOf()) {
        require(policy.allowDtcSetting) { "DTC setting control is disabled by the ECU profile." }
        require(settingType in 1..2)
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

    private fun requireAllowedMemory(address: Long, size: Long) {
        require(policy.memoryRegions.any { it.contains(address, size) }) {
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
