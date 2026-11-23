package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Lfx2012FuelSessionHistoryTest {
    private val vin = "1G1FA1RXXR0123456"
    private val source = Lfx2012FuelReadOnly.Source.ELM
    private val identity = Lfx2012FuelSessionHistory.SessionIdentity(vin, "session-1", source)

    private fun sample(
        time: Long,
        ethanol: Int?,
        trims: Int? = 128,
        target: Lfx2012FuelSessionHistory.SessionIdentity = identity
    ): Lfx2012FuelSessionHistory.Sample {
        val supported = Lfx2012FuelReadOnly.PIDS.toSet()
        val survey = Lfx2012FuelReadOnly.Survey(
            source, supported,
            Lfx2012FuelReadOnly.PIDS.associateWith { "reported" }
        )
        val raw = Lfx2012FuelReadOnly.PIDS.associateWith { pid ->
            val data = if (pid == Lfx2012FuelReadOnly.ETHANOL) ethanol else trims
            data?.let { intArrayOf(it) }
        }
        return Lfx2012FuelSessionHistory.Sample(
            target, Lfx2012FuelReadOnly.inspect(survey, raw, time)
        )
    }

    @Test fun reportedZeroIsPreservedAndUnavailableReadingIsNotConvertedToZero() {
        val report = Lfx2012FuelSessionHistory.summarize(
            listOf(sample(100L, 0), sample(200L, null), sample(300L, 128))
        )
        val ethanol = report.readings.first { it.pid == Lfx2012FuelReadOnly.ETHANOL }
        assertEquals(2, ethanol.reportedCount)
        assertEquals(1, ethanol.noResponseCount)
        assertEquals(0.0, ethanol.firstReported!!, 0.0)
        assertTrue(ethanol.lastReported!! > 0.0)
        assertEquals(0.0, ethanol.minimum!!, 0.0)
        assertEquals(0L, report.startedAtUtcMillis - 100L)
        assertFalse(report.sensorOrFlexFuelCalibrationVerified)
    }

    @Test fun absentMeasurementsDoNotProduceInventedDeltas() {
        val report = Lfx2012FuelSessionHistory.summarize(
            listOf(sample(1L, null, null), sample(2L, 200, null))
        )
        val trim = report.readings.first { it.pid == Lfx2012FuelReadOnly.STFT_BANK_1 }
        assertEquals(0, trim.reportedCount)
        assertEquals(2, trim.noResponseCount)
        assertNull(trim.minimum)
        assertNull(trim.delta)
        val ethanol = report.readings.first { it.pid == Lfx2012FuelReadOnly.ETHANOL }
        assertEquals(1, ethanol.reportedCount)
        assertNull(ethanol.delta)
    }

    @Test fun aHistoryCannotMixSessionsOrOutOfOrderSnapshots() {
        val another = identity.copy(sessionId = "different")
        assertTrue(runCatching {
            Lfx2012FuelSessionHistory.summarize(
                listOf(sample(1L, 0), sample(2L, 128, target = another))
            )
        }.isFailure)
        assertTrue(runCatching {
            Lfx2012FuelSessionHistory.summarize(listOf(sample(2L, 0), sample(1L, 0)))
        }.isFailure)
        assertTrue(runCatching {
            Lfx2012FuelSessionHistory.summarize(listOf(sample(1L, 0), sample(1L, 0)))
        }.isFailure)
    }

    @Test fun emptyAndOversizedHistoryAreRejected() {
        assertTrue(runCatching {
            Lfx2012FuelSessionHistory.summarize(emptyList())
        }.isFailure)
        val repeated = (1L..513L).map { sample(it, 0) }
        assertTrue(runCatching {
            Lfx2012FuelSessionHistory.summarize(repeated)
        }.isFailure)
    }
}
