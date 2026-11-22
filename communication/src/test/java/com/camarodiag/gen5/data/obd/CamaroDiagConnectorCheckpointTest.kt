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

    @Test fun sharedFirmwareGoldenCheckpointHasIdenticalWireInterpretation() {
        // Same fixed vector as Connectors PR #6 native journal_record_test.cpp.
        val wire = "43444a0102000000010000000102030405060708090a0b0c0d0e0f10202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3fa75e21b6"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val audit = CamaroDiagConnectorCheckpoint.inspect(wire,
            "0102030405060708090a0b0c0d0e0f10",
            "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f")
        assertEquals(CamaroDiagConnectorCheckpoint.Stage.ERASE_INTENT, audit.record?.stage)
        assertEquals(1L, audit.record?.sequence)
        assertEquals(CamaroDiagConnectorCheckpoint.Disposition.RECOVERY_REQUIRED, audit.disposition)
        assertFalse(audit.permitsProgramming)
        assertFalse(audit.permitsAutomaticRecovery)
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
    @org.junit.Test fun streamInspectorRejectsTruncatedAndOverlongWithoutUnboundedRead() {
        val huge = object : java.io.InputStream() {
            var reads = 0
            override fun read(): Int {
                reads++
                check(reads <= 65)
                return 1
            }
        }
        val oversized = CamaroDiagConnectorCheckpoint.inspectStream(huge)
        org.junit.Assert.assertEquals(CamaroDiagConnectorCheckpoint.Disposition.UNAVAILABLE_OR_CORRUPT, oversized.disposition)
        org.junit.Assert.assertEquals(65, huge.reads)
        val truncated = CamaroDiagConnectorCheckpoint.inspectStream(ByteArray(63).inputStream())
        org.junit.Assert.assertEquals(CamaroDiagConnectorCheckpoint.Disposition.UNAVAILABLE_OR_CORRUPT, truncated.disposition)
    }
}
