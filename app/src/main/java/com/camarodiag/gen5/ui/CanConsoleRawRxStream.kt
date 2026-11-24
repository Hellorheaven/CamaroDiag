package com.camarodiag.gen5.ui

import com.camarodiag.gen5.data.obd.CanConsoleRecordParser
import com.camarodiag.gen5.data.obd.CanConsoleStreamFramer
import com.camarodiag.gen5.data.obd.ParsedCanConsoleRecord

/**
 * Incremental, lossless RX-event extraction for one physical RAW CAN TX.
 *
 * The ELM acknowledgement buffer and diagnostic matcher retain at most a
 * recent window; neither is the complete console event history. Decode at
 * each transport callback and persist batches immediately via the caller.
 * The UI may still keep only its most recent 300 records in memory.
 */
internal class CanConsoleRawRxStream {
    data class Batch(
        val records: List<ParsedCanConsoleRecord>,
        val receivedAtMillis: Long,
        val discardedOversizedLines: Int
    )

    private val framer = CanConsoleStreamFramer()
    private var lastReceivedAtMillis = 0L
    private var finished = false
    var recordCount: Long = 0L
        private set

    fun append(chunk: String, receivedAtMillis: Long): Batch {
        check(!finished) { "The RAW CAN RX session has already completed" }
        require(receivedAtMillis >= 0L)
        if (chunk.isNotEmpty()) lastReceivedAtMillis = receivedAtMillis
        return decode(framer.append(chunk), receivedAtMillis)
    }

    /** Drain a final, unterminated ELM line once, including on failure/Stop. */
    fun finish(): Batch {
        check(!finished) { "The RAW CAN RX session has already completed" }
        finished = true
        return decode(framer.drainTrailing(), lastReceivedAtMillis)
    }

    private fun decode(batch: CanConsoleStreamFramer.Batch, timestamp: Long): Batch {
        val records = CanConsoleRecordParser.parse(batch.completeLines)
        recordCount += records.size
        return Batch(records, timestamp, batch.discardedLines)
    }
}
