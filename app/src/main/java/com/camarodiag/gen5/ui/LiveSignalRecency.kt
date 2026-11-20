package com.camarodiag.gen5.ui

/** Avoid showing a previously observed PID as currently measured forever. */
internal class LiveSignalRecency(private val maxAgeMillis: Long = 30_000L) {
    init {
        require(maxAgeMillis > 0L)
    }

    private val lastSeenAt = mutableMapOf<String, Long>()

    fun record(id: String, timestampMillis: Long) {
        lastSeenAt[id] = timestampMillis
    }

    fun isRecent(id: String, timestampMillis: Long): Boolean =
        lastSeenAt[id]?.let { last ->
            timestampMillis >= last && timestampMillis - last <= maxAgeMillis
        } ?: false

    fun clear() {
        lastSeenAt.clear()
    }
}
