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

    @Test fun physicalInventoryDoesNotCreditUnadvertisedOrAnonymousResponses() {
        val cache = ObdPidSupportCache()
        val generation = cache.generation()
        val advertised = mutableSetOf(0x0D, 0xA6)
        val addresses = linkedMapOf<String, Set<Int>>("7E8" to advertised, "7EA" to emptySet())
        cache.publish(generation, setOf(0x0D, 0xA6), true, 100L, addresses)
        advertised.clear()
        addresses.clear()
        val inventory = checkNotNull(cache.getInventory(generation, 200L))
        val valid = CamaroUdsProtocol.PidObservation("7E8", listOf(0, 11, 217, 103))
        val other = CamaroUdsProtocol.PidObservation("7EA", listOf(0, 11, 217, 103))
        val unknown = CamaroUdsProtocol.PidObservation("7EC", listOf(0, 11, 217, 103))
        val anonymous = CamaroUdsProtocol.PidObservation(null, listOf(0, 11, 217, 103))
        val rows = listOf(other, valid, unknown, anonymous)
        assertEquals(listOf(valid), inventory.accept(0xA6, rows))
        assertEquals(emptySet<Int>(), inventory.byResponder["7EA"])
        assertTrue(inventory.accept(0x5B, rows).isEmpty())
        cache.invalidate()
        assertThrows(ObdSupersededSessionException::class.java) { cache.getInventory(generation, 200L) }
        val fresh = cache.generation()
        cache.publish(fresh, setOf(0xA6), false, 300L)
        assertEquals(rows, cache.getInventory(fresh, 301L)?.accept(0xA6, rows))
        assertNull(cache.getInventory(fresh, 10_300L))
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
