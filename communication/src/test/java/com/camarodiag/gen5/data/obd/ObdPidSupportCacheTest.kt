package com.camarodiag.gen5.data.obd

import org.junit.Assert.*
import org.junit.Test

class ObdPidSupportCacheTest {
    @Test fun partialNonEmptyDiscoveryExpiresButCompleteEmptyDoesNot() {
        val cache = ObdPidSupportCache()
        val generation = cache.generation()
        cache.publish(generation, setOf(0x0D), false, 100L)
        assertEquals(setOf(0x0D), cache.get(generation, 10_099L))
        assertNull(cache.get(generation, 10_100L))
        cache.publish(generation, emptySet(), true, 10_100L)
        assertEquals(emptySet<Int>(), cache.get(generation, 1_000_000L))
    }

    @Test fun invalidationRejectsLateDiscoveryAndRead() {
        val cache = ObdPidSupportCache()
        val old = cache.generation()
        cache.publish(old, setOf(0x42), true, 1L)
        cache.invalidate()
        val fresh = cache.generation()
        assertNull(cache.get(fresh, 2L))
        assertThrows(ObdSupersededSessionException::class.java) {
            cache.publish(old, setOf(0x0C), true, 3L)
        }
        assertThrows(ObdSupersededSessionException::class.java) { cache.get(old, 3L) }
        assertNull(cache.get(fresh, 3L))
    }
}
