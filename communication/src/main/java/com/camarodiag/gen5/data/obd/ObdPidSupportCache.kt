package com.camarodiag.gen5.data.obd

/** Cache scoped to the currently selected diagnostic context. */
internal class ObdPidSupportCache {
    private data class Entry(val pids: Set<Int>, val complete: Boolean, val atMillis: Long)
    private var revision = 0L
    private var entry: Entry? = null

    @Synchronized fun generation(): Long = revision

    @Synchronized fun requireCurrent(expected: Long) {
        if (revision != expected) throw ObdSupersededSessionException(
            java.io.IOException("OBD PID discovery context changed"))
    }

    @Synchronized fun get(expected: Long, nowMillis: Long): Set<Int>? {
        requireCurrent(expected)
        return entry?.takeIf { it.complete || nowMillis - it.atMillis in 0L until 10_000L }?.pids
    }

    @Synchronized fun publish(expected: Long, pids: Set<Int>, complete: Boolean, nowMillis: Long) {
        requireCurrent(expected)
        entry = Entry(pids.toSet(), complete, nowMillis)
    }

    @Synchronized fun invalidate() {
        revision++
        entry = null
    }
}
