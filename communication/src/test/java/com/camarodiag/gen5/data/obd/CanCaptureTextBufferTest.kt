package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CanCaptureTextBufferTest {
    @Test fun retainsOrderedChunksUntilFull() {
        val buffer = CanCaptureTextBuffer(maxChars = 8)
        buffer.append("AA")
        buffer.append("BB")
        assertEquals("AABB", buffer.snapshot())
        buffer.append("CCDD")
        assertEquals("AABBCCDD", buffer.snapshot())
    }

    @Test fun longCaptureKeepsRecentTailInsteadOfStopping() {
        val buffer = CanCaptureTextBuffer(maxChars = 8)
        buffer.append("12345678")
        buffer.append("90")
        buffer.append("ABC")
        assertEquals("67890ABC", buffer.snapshot())
    }

    @Test fun oversizedChunkKeepsTail() {
        val buffer = CanCaptureTextBuffer(maxChars = 5)
        buffer.append("abc")
        buffer.append("0123456789")
        assertEquals("56789", buffer.snapshot())
    }

    @Test fun zeroCapacityIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            CanCaptureTextBuffer(maxChars = 0)
        }
    }
}
