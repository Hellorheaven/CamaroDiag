package com.camarodiag.gen5.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class LiveSignalRecencyTest {
    @Test fun aPidIsAvailableOnlyForRecentlyObservedValues() {
        val state = LiveSignalRecency(maxAgeMillis = 30_000L)
        assertFalse(state.isRecent("ETHANOL", 1_000))
        state.record("ETHANOL", 10_000)
        assertTrue(state.isRecent("ETHANOL", 39_999))
        assertTrue(state.isRecent("ETHANOL", 40_000))
        assertFalse(state.isRecent("ETHANOL", 40_001))
        assertFalse(state.isRecent("ETHANOL", 9_999))
    }

    @Test fun eachSensorHasItsOwnExpiryAndCanBeCleared() {
        val state = LiveSignalRecency(maxAgeMillis = 1_000L)
        state.record("RPM", 1_000)
        state.record("ECT", 2_000)
        assertFalse(state.isRecent("RPM", 2_100))
        assertTrue(state.isRecent("ECT", 2_100))
        state.clear()
        assertFalse(state.isRecent("ECT", 2_100))
    }

    @Test fun rejectsZeroExpiry() {
        assertThrows(IllegalArgumentException::class.java) {
            LiveSignalRecency(0)
        }
    }
}
