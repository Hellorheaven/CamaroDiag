package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Lfx2012FuelHistoryExportPolicyTest {
    private val identity = Lfx2012FuelSessionHistory.SessionIdentity(
        "1G1FA1RXXR0123456", "session-1", Lfx2012FuelReadOnly.Source.ELM
    )

    private fun sample(
        time: Long,
        session: Lfx2012FuelSessionHistory.SessionIdentity = identity
    ): Lfx2012FuelSessionHistory.Sample {
        val readings = Lfx2012FuelReadOnly.PIDS.map { pid ->
            Lfx2012FuelReadOnly.Reading(
                pid, Lfx2012FuelReadOnly.Availability.NO_VALID_RESPONSE
            )
        }
        return Lfx2012FuelSessionHistory.Sample(
            session,
            Lfx2012FuelReadOnly.Snapshot(session.source, time, readings)
        )
    }

    @Test fun completeUnchangedWindowCanBeExportedWithoutInventingMeasurements() {
        val samples = listOf(sample(100L), sample(200L))
        assertTrue(Lfx2012FuelHistoryExportPolicy.canPublish(samples, samples.toList(), false))
    }

    @Test fun incompleteOrChangedWindowNeverPublishes() {
        val first = sample(100L)
        val second = sample(200L)
        assertFalse(Lfx2012FuelHistoryExportPolicy.canPublish(emptyList(), emptyList(), false))
        assertFalse(Lfx2012FuelHistoryExportPolicy.canPublish(listOf(first), listOf(first), true))
        assertFalse(Lfx2012FuelHistoryExportPolicy.canPublish(
            listOf(first), listOf(first, second), false
        ))
        assertFalse(Lfx2012FuelHistoryExportPolicy.canPublish(
            listOf(first), listOf(sample(100L, identity.copy(sessionId = "new"))), false
        ))
    }

    @Test fun untrustedOrNonMonotonicSnapshotCannotBeExported() {
        val duplicateTimes = listOf(sample(100L), sample(100L))
        assertFalse(Lfx2012FuelHistoryExportPolicy.canPublish(
            duplicateTimes, duplicateTimes, false
        ))
    }
}
