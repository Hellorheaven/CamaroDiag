package com.camarodiag.gen5.data.obd

import java.util.zip.CRC32

/**
 * Offline inspection of the CDJ/1 checkpoint structure defined by Connectors.
 * Import inspection only: the physical CDJ/USB/1 capture firmware is deferred to C5.
 * A valid CRC or matching imported manifest is NEVER authenticated evidence.
 * Do not use this class to clear a fault, retry, reset, or authorize ECU I/O.
 */
object CamaroDiagConnectorCheckpoint {
    const val RECORD_SIZE = 64

    enum class Stage(val wireCode: Int) {
        PREPARED(1), ERASE_INTENT(2), PROGRAM_INTENT(3), VERIFY_INTENT(4),
        VERIFIED(5), RESTART_INTENT(6), POSTCHECK(7), COMPLETED(8),
        ABORTED(9), RECOVERY_REQUIRED(10)
    }

    enum class Disposition {
        UNAVAILABLE_OR_CORRUPT, RECOVERY_REQUIRED, SAFE_BUT_BLOCKED, IDENTITY_MISMATCH
    }

    data class Record(
        val stage: Stage,
        val sequence: Long,
        val transactionHex: String,
        val manifestSha256: String
    )

    data class Inspection(val record: Record?, val disposition: Disposition) {
        // Public checkpoint imports cannot prove origin, freshness or authorization.
        val authenticated: Boolean get() = false
        val permitsProgramming: Boolean get() = false
        val permitsAutomaticRecovery: Boolean get() = false
    }

    private fun u8(b: Byte) = b.toInt() and 0xff
    private fun u32(bytes: ByteArray, offset: Int): Long =
        (0..3).fold(0L) { value, i -> value or (u8(bytes[offset + i]).toLong() shl (8 * i)) }
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(u8(it)) }

    /** Read at most 65 bytes; neither huge imports nor trailing bytes are accepted.
     *  The caller owns the stream and runs this operation off the UI thread.
     */
    fun inspectStream(input: java.io.InputStream): Inspection {
        val buffer = ByteArray(RECORD_SIZE + 1)
        var used = 0
        while (used < buffer.size) {
            val count = input.read(buffer, used, buffer.size - used)
            if (count < 0) break
            if (count == 0) {
                val value = input.read()
                if (value < 0) break
                buffer[used++] = value.toByte()
            } else {
                used += count
            }
        }
        return inspect(buffer.copyOf(used))
    }

    /** Imported bytes only. A missing record must not be treated as an idle device. */
    fun inspect(
        bytes: ByteArray?,
        expectedTransactionHex: String? = null,
        expectedManifestSha256: String? = null
    ): Inspection {
        fun corrupt() = Inspection(null, Disposition.UNAVAILABLE_OR_CORRUPT)
        if (bytes == null || bytes.size != RECORD_SIZE) return corrupt()
        val data = bytes.copyOf()
        if (data[0] != 'C'.code.toByte() || data[1] != 'D'.code.toByte() ||
            data[2] != 'J'.code.toByte() || u8(data[3]) != 1 ||
            (5..7).any { data[it] != 0.toByte() }
        ) return corrupt()
        val crc = CRC32().apply { update(data, 0, 60) }.value
        if (u32(data, 60) != crc) return corrupt()
        val stage = Stage.values().firstOrNull { it.wireCode == u8(data[4]) } ?: return corrupt()
        val tx = data.copyOfRange(12, 28)
        val digest = data.copyOfRange(28, 60)
        if (tx.all { it == 0.toByte() } || digest.all { it == 0.toByte() }) return corrupt()
        val sequence = u32(data, 8)
        // A persisted checkpoint must be reachable from Prepared(0) using
        // the embedded storage successor rules. Reject otherwise valid CRCs.
        val reachable = when (stage) {
            Stage.ABORTED -> sequence == 1L
            Stage.RECOVERY_REQUIRED -> sequence in 2L..7L
            else -> sequence == (stage.wireCode - 1).toLong()
        }
        if (!reachable) return corrupt()
        val record = Record(stage, sequence, hex(tx), hex(digest))
        if ((expectedTransactionHex != null && expectedTransactionHex != record.transactionHex) ||
            (expectedManifestSha256 != null && expectedManifestSha256 != record.manifestSha256)
        ) return Inspection(record, Disposition.IDENTITY_MISMATCH)
        val disposition = when (stage) {
            Stage.PREPARED, Stage.ABORTED, Stage.COMPLETED -> Disposition.SAFE_BUT_BLOCKED
            else -> Disposition.RECOVERY_REQUIRED
        }
        return Inspection(record, disposition)
    }
}
