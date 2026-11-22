package com.camarodiag.gen5.data.obd

import java.util.zip.CRC32
import org.junit.Assert.*
import org.junit.Test

class CamaroDiagConnectorCheckpointTest {
    private val tx = "01".repeat(16)
    private val digest = "02".repeat(32)

    private fun seal(bytes: ByteArray): ByteArray {
        val crc = CRC32().apply { update(bytes, 0, 60) }.value
        for (i in 0..3) bytes[60 + i] = (crc ushr (8 * i)).toByte()
        return bytes
    }

    private fun fixture(stage: Int, sequence: Long): ByteArray {
        val bytes = ByteArray(64)
        bytes[0] = 0x43
        bytes[1] = 0x44
        bytes[2] = 0x4a
        bytes[3] = 1
        bytes[4] = stage.toByte()
        for (i in 0..3) bytes[8 + i] = (sequence ushr (8 * i)).toByte()
        bytes.fill(1, 12, 28)
        bytes.fill(2, 28, 60)
        return seal(bytes)
    }

    @Test fun embeddedCriticalStagesAlwaysRequireAssessment() {
        for (stage in 2..7) {
            val audit = CamaroDiagConnectorCheckpoint.inspect(fixture(stage, (stage - 1).toLong()), tx, digest)
            assertEquals(CamaroDiagConnectorCheckpoint.Disposition.RECOVERY_REQUIRED, audit.disposition)
            assertEquals(tx, audit.record?.transactionHex)
            assertEquals(digest, audit.record?.manifestSha256)
            assertFalse(audit.authenticated)
            assertFalse(audit.permitsProgramming)
            assertFalse(audit.permitsAutomaticRecovery)
        }
        for (seq in 2L..7L) {
            assertEquals(CamaroDiagConnectorCheckpoint.Disposition.RECOVERY_REQUIRED,
                CamaroDiagConnectorCheckpoint.inspect(fixture(10, seq)).disposition)
        }
    }

    @Test fun terminalOrPreparedCheckpointCannotAuthorizeAnything() {
        for ((stage, seq) in listOf(1 to 0L, 8 to 7L, 9 to 1L)) {
            val audit = CamaroDiagConnectorCheckpoint.inspect(fixture(stage, seq), tx, digest)
            assertEquals(CamaroDiagConnectorCheckpoint.Disposition.SAFE_BUT_BLOCKED, audit.disposition)
            assertFalse(audit.authenticated)
            assertFalse(audit.permitsProgramming)
            assertFalse(audit.permitsAutomaticRecovery)
        }
    }

    @Test fun corruptAbsentAndUnreachableRecordsFailClosed() {
        val valid = fixture(2, 1)
        val bad = listOf(
            null, byteArrayOf(), valid.copyOf(63), valid.copyOf(65),
            valid.copyOf().also { it[28] = 3 },
            seal(valid.copyOf().also { it[3] = 2 }),
            seal(valid.copyOf().also { it[7] = 1 }),
            seal(valid.copyOf().also { it.fill(0, 12, 28) }),
            seal(valid.copyOf().also { it.fill(0, 28, 60) }),
            fixture(0, 0), fixture(11, 0), fixture(1, 1), fixture(8, 0),
            fixture(10, 1), fixture(10, 0xffffffffL)
        )
        for (entry in bad) {
            val audit = CamaroDiagConnectorCheckpoint.inspect(entry)
            assertEquals(CamaroDiagConnectorCheckpoint.Disposition.UNAVAILABLE_OR_CORRUPT, audit.disposition)
            assertNull(audit.record)
            assertFalse(audit.permitsProgramming)
        }
    }

    @Test fun matchingCrcDoesNotSubstituteForExactTransactionAndManifest() {
        val bytes = fixture(5, 4)
        for ((txId, hash) in listOf("03".repeat(16) to digest, tx to "04".repeat(32), "" to digest)) {
            val audit = CamaroDiagConnectorCheckpoint.inspect(bytes, txId, hash)
            assertEquals(CamaroDiagConnectorCheckpoint.Disposition.IDENTITY_MISMATCH, audit.disposition)
            assertFalse(audit.permitsAutomaticRecovery)
        }
        val imported = CamaroDiagConnectorCheckpoint.inspect(bytes, tx, digest)
        assertFalse(imported.authenticated)
        assertEquals(CamaroDiagConnectorCheckpoint.Disposition.RECOVERY_REQUIRED, imported.disposition)
    }
}
