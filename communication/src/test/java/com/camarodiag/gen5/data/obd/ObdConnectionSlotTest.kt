package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure concurrency lifecycle tests; no Android, USB adapter or ECU. */
class ObdConnectionSlotTest {
    @Test fun olderOpenCannotPublishAfterAnotherAdapterWasSelected() {
        val slot = ObdConnectionSlot<Any>()
        val old = slot.begin()
        val fresh = slot.begin()
        val current = Any()
        assertFalse(slot.publish(old.generation, Any()))
        assertTrue(slot.publish(fresh.generation, current))
        assertSame(current, slot.snapshot())
        assertNull(slot.releaseIfCurrent(old.generation))
        assertSame(current, slot.snapshot())
    }

    @Test fun detachReturnsOnlyTheOriginalResource() {
        val slot = ObdConnectionSlot<Any>()
        val first = slot.begin()
        val original = Any()
        assertTrue(slot.publish(first.generation, original))
        val replacement = slot.begin()
        assertSame(original, replacement.previous)
        assertNull(slot.snapshot())
        val next = Any()
        assertTrue(slot.publish(replacement.generation, next))
        assertFalse(slot.isCurrent(original))
        assertTrue(slot.isCurrent(next))
        assertSame(next, slot.release())
        assertNull(slot.snapshot())
    }

    @Test fun failedCurrentOpenInvalidatesOnlyItsOwnGeneration() {
        val slot = ObdConnectionSlot<Any>()
        val failed = slot.begin()
        assertNull(slot.releaseIfCurrent(failed.generation))
        assertFalse(slot.publish(failed.generation, Any()))
        val current = slot.begin()
        val handle = Any()
        assertTrue(slot.publish(current.generation, handle))
        assertSame(handle, slot.releaseIfCurrent(current.generation))
        assertNull(slot.snapshot())
    }

    @Test fun aPublishedHandleCannotBeSilentlyOverwrittenInSameGeneration() {
        val slot = ObdConnectionSlot<Any>()
        val generation = slot.begin().generation
        val first = Any()
        assertTrue(slot.publish(generation, first))
        assertFalse(slot.publish(generation, Any()))
        assertSame(first, slot.snapshot())
    }
}
