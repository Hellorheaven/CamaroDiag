package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Lfx2012FuelHistoryWindowTest {
    private val vin = "1G1FA1RXXR0123456"
    private val source = Lfx2012FuelReadOnly.Source.ELM
    private val identity = Lfx2012FuelSessionHistory.SessionIdentity(vin, "session-1", source)

    private fun observation(time: Long, rawEthanol: Int?): Lfx2012FuelReadOnly.Snapshot {
        val pids = Lfx2012FuelReadOnly.PIDS
        val survey = Lfx2012FuelReadOnly.Survey(
            source, pids.toSet(), pids.associateWith { "reported" }
        )
        val bytes = pids.associateWith { pid ->
            if (pid == Lfx2012FuelReadOnly.ETHANOL) rawEthanol?.let { intArrayOf(it) }
            else intArrayOf(128)
        }
        return Lfx2012FuelReadOnly.inspect(survey, bytes, time)
    }

    @Test fun preservesRealZeroAndMissingPidWithoutInventingAZero() {
        val window = Lfx2012FuelHistoryWindow()
        window.append(identity, observation(1L, 0))
        window.append(identity, observation(2L, null))
        val summary = window.reportOrNull()!!
            .readings.first { it.pid == Lfx2012FuelReadOnly.ETHANOL }
        assertEquals(1, summary.reportedCount)
        assertEquals(1, summary.noResponseCount)
        assertEquals(0.0, summary.minimum!!, 0.0)
        assertNull(summary.delta)
    }

    @Test fun changeOfSessionAndTimestampReversalCannotCombineReadings() {
        val window = Lfx2012FuelHistoryWindow()
        window.append(identity, observation(100L, 0))
        val changed = identity.copy(sessionId = "session-2")
        assertEquals(1, window.append(changed, observation(101L, 255)).count)
        assertEquals(changed, window.reportOrNull()!!.session)
        assertEquals(1, window.append(changed, observation(99L, 128)).count)
        assertEquals(99L, window.reportOrNull()!!.startedAtUtcMillis)
        window.clear()
        assertNull(window.reportOrNull())
        assertTrue(window.copySamples().isEmpty())
    }

    @Test fun boundedWindowDropsOldestWithoutForgettingTheCurrentSession() {
        val window = Lfx2012FuelHistoryWindow()
        for (time in 1L..513L) {
            window.append(identity, observation(time, 128))
        }
        val report = window.reportOrNull()!!
        assertEquals(512, report.count)
        assertEquals(2L, report.startedAtUtcMillis)
        assertEquals(513L, report.endedAtUtcMillis)
        assertEquals(512, window.copySamples().size)
        assertEquals(identity, report.session)
    }

    @Test fun returnedSampleListDoesNotExposeTheInternalQueue() {
        val window = Lfx2012FuelHistoryWindow()
        window.append(identity, observation(1L, 0))
        val snapshot = window.copySamples()
        assertEquals(1, snapshot.size)
        window.clear()
        assertEquals(1, snapshot.size)
        assertTrue(window.copySamples().isEmpty())
    }
}
