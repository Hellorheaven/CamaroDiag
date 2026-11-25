package com.camarodiag.gen5.data.obd

import com.camarodiag.gen5.domain.model.DiagnosticDtc

/**
 * DTC format identifiers returned by UDS ReadDTCInformation (ISO 14229).
 * Unknown identifiers are preserved as raw 24-bit values, never guessed.
 */
enum class UdsDtcFormat(val identifier: Int) {
    SAE_J2012_DA_FORMAT_00(0x00),
    ISO_14229_1(0x01),
    SAE_J1939_73(0x02),
    ISO_11992_4(0x03),
    SAE_J2012_DA_FORMAT_04(0x04);

    companion object {
        fun from(identifier: Int): UdsDtcFormat? = entries.firstOrNull { it.identifier == identifier }
    }
}

data class DecodedUdsDtcRecord(
    val rawDtc: Int,
    val code: String,
    val statusByte: Int,
    val status: UdsDtcStatus
)

data class UdsDtcStatus(
    val testFailed: Boolean,
    val testFailedThisOperationCycle: Boolean,
    val pending: Boolean,
    val confirmed: Boolean,
    val testNotCompletedSinceLastClear: Boolean,
    val testFailedSinceLastClear: Boolean,
    val testNotCompletedThisOperationCycle: Boolean,
    val warningIndicatorRequested: Boolean
)

/** Maps a decoded UDS record into the application's shared DTC presentation model. */
fun DecodedUdsDtcRecord.toDiagnosticDtc(moduleId: String): DiagnosticDtc {
    require(moduleId.isNotBlank()) { "The responding ECU module ID is required." }
    val stateEn = buildList {
        if (status.testFailed) add("failed")
        if (status.testFailedThisOperationCycle) add("failed this operation cycle")
        if (status.pending) add("pending")
        if (status.confirmed) add("confirmed")
        if (status.testNotCompletedSinceLastClear) add("test not completed since last clear")
        if (status.testFailedSinceLastClear) add("failed since last clear")
        if (status.testNotCompletedThisOperationCycle) add("test not completed this operation cycle")
        if (status.warningIndicatorRequested) add("warning indicator requested")
    }.ifEmpty { listOf("status not asserted") }.joinToString(", ")
    val stateFr = buildList {
        if (status.testFailed) add("défaut présent")
        if (status.testFailedThisOperationCycle) add("défaut présent pendant ce cycle de fonctionnement")
        if (status.pending) add("en attente")
        if (status.confirmed) add("confirmé")
        if (status.testNotCompletedSinceLastClear) add("test non terminé depuis le dernier effacement")
        if (status.testFailedSinceLastClear) add("défaut détecté depuis le dernier effacement")
        if (status.testNotCompletedThisOperationCycle) add("test non terminé pendant ce cycle de fonctionnement")
        if (status.warningIndicatorRequested) add("voyant demandé")
    }.ifEmpty { listOf("aucun état de défaut actif") }.joinToString(", ")
    return DiagnosticDtc(
        code = code,
        module = moduleId,
        statusEn = stateEn,
        statusFr = stateFr,
        descEn = "Raw UDS DTC 0x" + rawDtc.toString(16).uppercase().padStart(6, '0') +
            ". A verified OEM definition for module $moduleId and this ECU calibration is not loaded.",
        descFr = "DTC UDS brut 0x" + rawDtc.toString(16).uppercase().padStart(6, '0') +
            ". Aucune définition constructeur vérifiée n'est chargée pour le calculateur $moduleId et cette calibration.",
        isPending = status.pending
    )
}

data class UdsDtcReport(
    val statusAvailabilityMask: Int,
    /** Null when the ECU did not provide a DTC format (e.g. 19/02). */
    val formatIdentifier: Int?,
    val format: UdsDtcFormat?,
    val records: List<DecodedUdsDtcRecord>
)

data class UdsDtcCountReport(val availabilityMask: Int, val formatIdentifier: Int, val count: Int)

data class UdsDtcDetail(val dtc: Int, val status: Int, val records: ByteArray)

data class UdsSnapshotReference(val dtc: Int, val record: Int)
data class UdsFaultCounter(val dtc: Int, val counter: Int)
data class UdsSeverityRecord(val dtc: Int, val severity: Int, val functionalUnit: Int, val status: Int)
data class UdsSeverityReport(val subfunction: Int, val availabilityMask: Int,
    val records: List<UdsSeverityRecord> = emptyList(), val count: Int? = null, val formatIdentifier: Int? = null)

data class UdsMemoryDtcReport(val memory: Int, val list: UdsDtcReport? = null, val detail: UdsDtcDetail? = null)

data class UdsExtendedRecordReport(val record: Int, val availability: Int?,
    val entries: List<UdsDtcDetail>)

data class UdsLegacyDtcReport(val subfunction: Int, val list: UdsDtcReport? = null,
    val count: UdsDtcCountReport? = null, val detail: UdsDtcDetail? = null)

data class UdsStoredDtcRecord(val record: Int, val detail: UdsDtcDetail?, val rawResponse: ByteArray)

object UdsDtcDecoder {
    fun storedRecordRequest(record: Int): ByteArray {
        require(record in 0..254)
        return byteArrayOf(0x19, 0x05, record.toByte())
    }

    fun decodeStoredRecord(record: Int, reply: ByteArray): UdsStoredDtcRecord {
        val request = storedRecordRequest(record)
        require(reply.size >= 3 && reply[0] == 0x59.toByte() &&
            reply[1] == request[1] && reply[2] == request[2])
        if (reply.size == 3) return UdsStoredDtcRecord(record, null, reply.copyOf())
        require(reply.size >= 8)
        val didCount = reply[7].toInt() and 255
        require(reply.size - 8 >= didCount * 2 && (didCount != 0 || reply.size == 8))
        val dtc = ((reply[3].toInt() and 255) shl 16) or
            ((reply[4].toInt() and 255) shl 8) or (reply[5].toInt() and 255)
        return UdsStoredDtcRecord(record, UdsDtcDetail(dtc, reply[6].toInt() and 255,
            reply.copyOfRange(7, reply.size)), reply.copyOf())
    }

    fun legacyRequest(sub: Int, mask: Int, dtc: Int, record: Int): ByteArray {
        require(mask in 0..255)
        if (sub in setOf(0x0F, 0x11, 0x12, 0x13)) return byteArrayOf(0x19, sub.toByte(), mask.toByte())
        require(sub == 0x10 && dtc in 0..0xFFFFFF && record in 1..255 && record !in 0xF0..0xFD)
        return byteArrayOf(0x19, 0x10, (dtc shr 16).toByte(), (dtc shr 8).toByte(), dtc.toByte(), record.toByte())
    }

    fun decodeLegacy(request: ByteArray, reply: ByteArray): UdsLegacyDtcReport {
        require(request.size >= 2 && request[0] == 0x19.toByte() && reply.size >= 2 &&
            reply[0] == 0x59.toByte() && reply[1] == request[1])
        val sub = request[1].toInt() and 255
        return when (sub) {
            0x0F, 0x13 -> {
                require(request.size == 3)
                UdsLegacyDtcReport(sub, list = decodeStatusMaskReport(reply.copyOfRange(2, reply.size), request[2].toInt() and 255))
            }
            0x11, 0x12 -> {
                require(request.size == 3 && reply.size == 6)
                val normalized = reply.copyOf().apply { this[1] = 1 }
                UdsLegacyDtcReport(sub, count = decodeCountByStatusMask(normalized))
            }
            0x10 -> {
                require(request.size == 6 && reply.size >= 6)
                val dtc = ((request[2].toInt() and 255) shl 16) or
                    ((request[3].toInt() and 255) shl 8) or (request[4].toInt() and 255)
                val record = request[5].toInt() and 255
                require(record in 1..255 && record !in 0xF0..0xFD)
                if (reply.size > 6 && record !in setOf(0xFE, 0xFF)) require(reply[6] == request[5])
                UdsLegacyDtcReport(sub, detail = decodeDetail(6, dtc, reply.copyOf().apply { this[1] = 6 }))
            }
            else -> error("Unsupported legacy DTC subfunction")
        }
    }

    fun extendedRecordRequest(record: Int, size: Int?): ByteArray {
        require(record in 1..0xEF && (size == null || size in 1..4090))
        return byteArrayOf(0x19, if (size == null) 0x1A else 0x16, record.toByte())
    }

    fun decodeExtendedRecord(record: Int, size: Int?, reply: ByteArray): UdsExtendedRecordReport {
        val request = extendedRecordRequest(record, size)
        val start = if (size == null) 4 else 3
        val width = 4 + (size ?: 0)
        require(reply.size >= start && reply[0] == 0x59.toByte() && reply[1] == request[1] &&
            reply[start - 1] == request[2] && (reply.size - start) % width == 0)
        val available = if (size == null) reply[2].toInt() and 255 else null
        val seen = mutableSetOf<Int>()
        val entries = (start until reply.size step width).map { offset ->
            val dtc = ((reply[offset].toInt() and 255) shl 16) or
                ((reply[offset + 1].toInt() and 255) shl 8) or (reply[offset + 2].toInt() and 255)
            val status = reply[offset + 3].toInt() and 255
            require(seen.add(dtc) && (available == null || (status and available.inv()) == 0))
            UdsDtcDetail(dtc, status, reply.copyOfRange(offset + 4, offset + width))
        }
        return UdsExtendedRecordReport(record, available, entries)
    }

    fun userMemoryRequest(sub: Int, memory: Int, mask: Int, dtc: Int, record: Int): ByteArray {
        require(memory in 0..255 && mask in 0..255)
        if (sub == 0x17) return byteArrayOf(0x19, 0x17, mask.toByte(), memory.toByte())
        require(sub in 0x18..0x19 && dtc in 0..0xFFFFFF && record in 1..255 &&
            (sub != 0x19 || record !in 0xF0..0xFD))
        return byteArrayOf(0x19, sub.toByte(), (dtc shr 16).toByte(), (dtc shr 8).toByte(),
            dtc.toByte(), record.toByte(), memory.toByte())
    }

    fun decodeUserMemory(request: ByteArray, reply: ByteArray): UdsMemoryDtcReport {
        fun Byte.u8() = toInt() and 255
        require(request.size >= 2 && request[0] == 0x19.toByte() &&
            reply.size >= 3 && reply[0] == 0x59.toByte() && reply[1] == request[1])
        val sub = request[1].u8()
        require(sub in 0x17..0x19 && request.size == if (sub == 0x17) 4 else 7)
        val memory = request.last().u8()
        require(reply[2].u8() == memory)
        if (sub == 0x17) {
            require(reply.size >= 4)
            return UdsMemoryDtcReport(memory,
                list = decodeStatusMaskReport(reply.copyOfRange(3, reply.size), request[2].u8()))
        }
        require(reply.size >= 7 && reply.copyOfRange(3, 6).contentEquals(request.copyOfRange(2, 5)))
        val record = request[5].u8()
        require(record in 1..255 && (sub != 0x19 || record !in 0xF0..0xFD))
        if (reply.size > 7 && record != 0xFF && (sub != 0x19 || record != 0xFE))
            require(reply[7].u8() == record)
        val dtc = (reply[3].u8() shl 16) or (reply[4].u8() shl 8) or reply[5].u8()
        return UdsMemoryDtcReport(memory, detail = UdsDtcDetail(dtc, reply[6].u8(),
            reply.copyOfRange(7, reply.size)))
    }

    fun severityRequest(subfunction: Int, severityMask: Int, statusMask: Int, dtc: Int): ByteArray {
        require(subfunction in 7..9 && severityMask in 0..255 && statusMask in 0..255 && dtc in 0..0xFFFFFF)
        return if (subfunction == 9) byteArrayOf(0x19, 9, (dtc shr 16).toByte(), (dtc shr 8).toByte(), dtc.toByte())
        else byteArrayOf(0x19, subfunction.toByte(), severityMask.toByte(), statusMask.toByte())
    }

    /** 19/07 count; 19/08 filtered records; 19/09 zero or one specific DTC record. */
    fun decodeSeverity(request: ByteArray, reply: ByteArray): UdsSeverityReport {
        require(request.size >= 2 && request[0] == 0x19.toByte() && reply.size >= 3 &&
            reply[0] == 0x59.toByte() && reply[1] == request[1])
        fun Byte.u8() = toInt() and 255
        val sub = request[1].u8()
        require(sub in 7..9 && request.size == (if (sub == 9) 5 else 4))
        val availability = reply[2].u8()
        if (sub == 7) {
            require(reply.size == 6)
            val count = (reply[4].u8() shl 8) or reply[5].u8()
            require(count == 0 || (request[2].u8() != 0 && (request[3].u8() and availability) != 0))
            return UdsSeverityReport(sub, availability, count = count, formatIdentifier = reply[3].u8())
        }
        require((reply.size - 3) % 6 == 0 && (sub != 9 || reply.size in setOf(3, 9)))
        val records = (3 until reply.size step 6).map { offset ->
            val dtc = (reply[offset+2].u8() shl 16) or (reply[offset+3].u8() shl 8) or reply[offset+4].u8()
            val row = UdsSeverityRecord(dtc, reply[offset].u8(), reply[offset+1].u8(), reply[offset+5].u8())
            require((row.status and availability.inv() and 255) == 0)
            if (sub == 9) require(reply.copyOfRange(offset+2, offset+5).contentEquals(request.copyOfRange(2, 5)))
            else require((row.severity and request[2].u8()) != 0 && (row.status and request[3].u8() and availability) != 0)
            row
        }
        require(records.map { it.dtc }.distinct().size == records.size)
        return UdsSeverityReport(sub, availability, records)
    }

    /** 19/14 contains DTC/FDC pairs, no availability mask or status byte.
     * Keep the signed ECU value even when it is nonpositive; do not infer a confirmed fault. */
    fun decodeFaultCounters(reply: ByteArray): List<UdsFaultCounter> {
        require(reply.size >= 2 && reply[0] == 0x59.toByte() && reply[1] == 0x14.toByte() &&
            (reply.size - 2) % 4 == 0)
        val rows = (2 until reply.size step 4).map { offset ->
            val dtc = ((reply[offset].toInt() and 255) shl 16) or
                ((reply[offset + 1].toInt() and 255) shl 8) or (reply[offset + 2].toInt() and 255)
            UdsFaultCounter(dtc, reply[offset + 3].toInt())
        }
        require(rows.map { it.dtc }.distinct().size == rows.size)
        return rows
    }

    /** 19/0A lists supported DTCs, including zero-status entries. */
    fun decodeSupportedDtcs(reply: ByteArray): UdsDtcReport = decodeDtcInventory(0x0A, reply)

    /** 19/15 selects permanent DTCs independently of their current status. */
    fun decodePermanentDtcs(reply: ByteArray): UdsDtcReport = decodeDtcInventory(0x15, reply)

    private fun decodeDtcInventory(subfunction: Int, reply: ByteArray): UdsDtcReport {
        require(reply.size >= 3 && reply[0] == 0x59.toByte() && reply[1] == subfunction.toByte() &&
            (reply.size - 3) % 4 == 0)
        val availability = reply[2].toInt() and 255
        val report = decodeRecords(availability, null, reply.copyOfRange(3, reply.size))
        require(report.records.map { it.rawDtc }.distinct().size == report.records.size &&
            report.records.all { (it.statusByte and availability.inv() and 255) == 0 })
        return report
    }

    /** 19/0B..0E returns at most one historical DTC, not a complete active fault list.
     * Its current status can differ from the event that selected it. */
    fun decodeHistoricalDtc(subfunction: Int, reply: ByteArray): UdsDtcReport {
        require(subfunction in 0x0B..0x0E && reply.size in setOf(3, 7) &&
            reply[0] == 0x59.toByte() && reply[1] == subfunction.toByte())
        val availability = reply[2].toInt() and 255
        val report = decodeRecords(availability, null, reply.copyOfRange(3, reply.size))
        require(report.records.all { (it.statusByte and availability.inv() and 255) == 0 })
        return report
    }

    /** 19/03 lists DTC and snapshot record pairs, not snapshot contents. */
    fun decodeSnapshotIndex(reply: ByteArray): List<UdsSnapshotReference> {
        require(reply.size >= 2 && reply[0] == 0x59.toByte() && reply[1] == 3.toByte() &&
            (reply.size - 2) % 4 == 0)
        val rows = (2 until reply.size step 4).map { offset ->
            val dtc = ((reply[offset].toInt() and 255) shl 16) or
                ((reply[offset + 1].toInt() and 255) shl 8) or (reply[offset + 2].toInt() and 255)
            UdsSnapshotReference(dtc, reply[offset + 3].toInt() and 255)
        }
        require(rows.distinct().size == rows.size)
        return rows
    }

    fun readDetailRequest(subFunction: Int, dtc: Int, record: Int): ByteArray {
        require(subFunction in setOf(4, 6) && dtc in 0..0xFFFFFF && record in 1..255)
        return byteArrayOf(0x19, subFunction.toByte(), (dtc shr 16).toByte(),
            (dtc shr 8).toByte(), dtc.toByte(), record.toByte())
    }

    /** Keep records raw: their lengths/semantics depend on verified ECU definitions. */
    fun decodeDetail(subFunction: Int, expectedDtc: Int, reply: ByteArray): UdsDtcDetail {
        require(subFunction in setOf(4, 6) && expectedDtc in 0..0xFFFFFF &&
            reply.size >= 6 && reply[0] == 0x59.toByte() && reply[1] == subFunction.toByte())
        val dtc = ((reply[2].toInt() and 255) shl 16) or
            ((reply[3].toInt() and 255) shl 8) or (reply[4].toInt() and 255)
        require(dtc == expectedDtc) { "DTC detail belongs to another fault." }
        return UdsDtcDetail(dtc, reply[5].toInt() and 255, reply.copyOfRange(6, reply.size))
    }
    /** ISO 14229 19/01: 59 01 availability format countHi countLo. */
    fun decodeCountByStatusMask(reply: ByteArray): UdsDtcCountReport {
        require(reply.size == 6 && reply[0] == 0x59.toByte() && reply[1] == 0x01.toByte()) {
            "Invalid UDS 19/01 count response."
        }
        return UdsDtcCountReport(reply[2].toInt() and 255, reply[3].toInt() and 255,
            ((reply[4].toInt() and 255) shl 8) or (reply[5].toInt() and 255))
    }
    /**
     * Historical/offline format-tagged input, NOT a standard 19/02 wire response:
     * DTCStatusAvailabilityMask, DTCFormatIdentifier, then 4-byte records
     * (3-byte DTC + 1-byte status).
     */
    fun decodeReadDtcInformation(payload: ByteArray): UdsDtcReport {
        require(payload.size >= 2) {
            "UDS DTC payload must include the status availability mask and DTC format identifier."
        }
        require((payload.size - 2) % 4 == 0) {
            "UDS DTC payload has a truncated DTCAndStatusRecord."
        }

        return decodeRecords(payload[0].toInt() and 0xFF, payload[1].toInt() and 0xFF,
            payload.copyOfRange(2, payload.size))
    }

    /** ISO 14229 19/02: availability mask followed directly by DTC/status records.
     * A format may only be supplied from an independently documented/observed source.
     */
    fun decodeStatusMaskReport(payload: ByteArray, requestedMask: Int,
        formatIdentifier: Int? = null): UdsDtcReport {
        require(requestedMask in 0..255 && (formatIdentifier == null || formatIdentifier in 0..255))
        require(payload.isNotEmpty() && (payload.size - 1) % 4 == 0) {
            "UDS 19/02 payload has a missing availability mask or truncated record."
        }
        val availability = payload[0].toInt() and 255
        val report = decodeRecords(availability, formatIdentifier, payload.copyOfRange(1, payload.size))
        val effective = availability and requestedMask
        require(report.records.map { it.rawDtc }.distinct().size == report.records.size &&
            report.records.all { (it.statusByte and effective) != 0 &&
                (it.statusByte and availability.inv() and 255) == 0 }) {
            "UDS 19/02 returned duplicate DTCs or status outside the requested available mask."
        }
        return report
    }

    private fun decodeRecords(availability: Int, formatIdentifier: Int?, data: ByteArray): UdsDtcReport {
        val format = formatIdentifier?.let(UdsDtcFormat::from)
        val records = data.toList().chunked(4).map { bytes ->
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            val b2 = bytes[2].toInt() and 0xFF
            val status = bytes[3].toInt() and 0xFF
            // Only status bits advertised by DTCStatusAvailabilityMask are meaningful.
            // Keep the raw byte intact in statusByte for diagnostics and evidence.
            val availableStatus = status and availability
            val raw = (b0 shl 16) or (b1 shl 8) or b2
            DecodedUdsDtcRecord(
                rawDtc = raw,
                code = formatDisplayCode(format, b0, b1, b2),
                statusByte = status,
                status = UdsDtcStatus(
                    testFailed = availableStatus and 0x01 != 0,
                    testFailedThisOperationCycle = availableStatus and 0x02 != 0,
                    pending = availableStatus and 0x04 != 0,
                    confirmed = availableStatus and 0x08 != 0,
                    testNotCompletedSinceLastClear = availableStatus and 0x10 != 0,
                    testFailedSinceLastClear = availableStatus and 0x20 != 0,
                    testNotCompletedThisOperationCycle = availableStatus and 0x40 != 0,
                    warningIndicatorRequested = availableStatus and 0x80 != 0
                )
            )
        }
        return UdsDtcReport(availability, formatIdentifier, format, records)
    }

    private fun formatDisplayCode(format: UdsDtcFormat?, b0: Int, b1: Int, b2: Int): String =
        when (format) {
            UdsDtcFormat.SAE_J2012_DA_FORMAT_00 -> formatJ2012Dtc(b0, b1, b2)
            // Format 0x04 uses a distinct SAE J2012-DA base-code list (ISO 27145).
            // Keep it raw until that separate table is implemented; never decode it as format 0x00.
            else -> "0x" + ((b0 shl 16) or (b1 shl 8) or b2)
                .toString(16).uppercase().padStart(6, '0')
        }

    /** SAE J2012 3-byte representation: five-character DTC plus failure-type byte. */
    private fun formatJ2012Dtc(b0: Int, b1: Int, failureType: Int): String {
        val family = when (b0 ushr 6) {
            0 -> 'P'
            1 -> 'C'
            2 -> 'B'
            else -> 'U'
        }
        val firstDigit = (b0 ushr 4) and 0x03
        val secondDigit = b0 and 0x0F
        return "$family$firstDigit" +
            secondDigit.toString(16).uppercase() +
            (b1 ushr 4).toString(16).uppercase() +
            (b1 and 0x0F).toString(16).uppercase() +
            "-" + failureType.toString(16).uppercase().padStart(2, '0')
    }
}
