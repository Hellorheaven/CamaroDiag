package com.camarodiag.gen5.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DoIpCommandProgressTest {
    @Test fun verifiedProgressIsOrderedAndCannotClaimCompletionEarly() {
        val progress = DoIpCommandProgress(2)
        progress.record(1, byteArrayOf(0x62, 0xF1.toByte(), 0x90.toByte()))
        assertEquals(1, progress.verified)
        assertTrue(runCatching { progress.requireComplete(2) }.isFailure)
        for (invalid in listOf(0, 1, 3)) {
            assertTrue(runCatching { progress.record(invalid, byteArrayOf(0x62)) }.isFailure)
            assertEquals(1, progress.verified)
        }
        assertTrue(runCatching { progress.record(2, byteArrayOf()) }.isFailure)
        assertEquals(1, progress.verified)
        progress.record(2, byteArrayOf(0x62))
        assertEquals(2, progress.verified)
        assertTrue(runCatching { progress.requireComplete(1) }.isFailure)
        progress.requireComplete(2)
    }

    @Test fun aFailureCanPreserveVerifiedRepliesWithoutSuccess() {
        val progress = DoIpCommandProgress(3)
        progress.record(1, byteArrayOf(0x62))
        assertEquals(1, progress.verified)
        assertTrue(runCatching { progress.requireComplete(1) }.isFailure)
        assertTrue(runCatching { progress.record(3, byteArrayOf(0x62)) }.isFailure)
        assertEquals(1, progress.verified)
        assertTrue(runCatching { DoIpCommandProgress(0) }.isFailure)
        assertTrue(runCatching { DoIpCommandProgress(17) }.isFailure)
    }
}
