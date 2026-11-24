package com.camarodiag.gen5.data.obd

/**
 * Pure session ownership for removable transports.
 *
 * A potentially blocking open runs outside the lock; only the most recent
 * generation may publish its handle. Older attempts must close THEIR OWN
 * resources, never the resource installed by a newer attempt.
 */
internal class ObdConnectionSlot<T : Any> {
    data class Replacement<T>(val generation: Long, val previous: T?)

    private val lock = Any()
    private var generation = 0L
    private var active: T? = null

    fun begin(): Replacement<T> = synchronized(lock) {
        val old = active
        active = null
        generation++
        Replacement(generation, old)
    }

    fun publish(expectedGeneration: Long, candidate: T): Boolean = synchronized(lock) {
        if (generation != expectedGeneration || active != null) false
        else {
            active = candidate
            true
        }
    }

    /** Invalidates both active I/O and any not-yet-published open operation. */
    fun release(): T? = synchronized(lock) {
        val old = active
        active = null
        generation++
        old
    }

    /** An expired opener must never release a successor's handle. */
    fun releaseIfCurrent(expectedGeneration: Long): T? = synchronized(lock) {
        if (generation != expectedGeneration) null
        else {
            val old = active
            active = null
            generation++
            old
        }
    }

    fun snapshot(): T? = synchronized(lock) { active }

    fun isCurrent(expected: T): Boolean = synchronized(lock) { active === expected }
}
