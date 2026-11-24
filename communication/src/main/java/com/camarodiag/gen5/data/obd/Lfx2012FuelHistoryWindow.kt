package com.camarodiag.gen5.data.obd

/**
 * Bounded, in-memory history of read-only fuel observations.
 *
 * A new vehicle, connection generation, physical source, or non-increasing
 * timestamp starts a fresh history. No transport or ECU operation is performed.
 */
class Lfx2012FuelHistoryWindow {
    private val samples = ArrayDeque<Lfx2012FuelSessionHistory.Sample>()

    @Synchronized
    fun clear() {
        samples.clear()
    }

    @Synchronized
    fun append(
        identity: Lfx2012FuelSessionHistory.SessionIdentity,
        snapshot: Lfx2012FuelReadOnly.Snapshot
    ): Lfx2012FuelSessionHistory.Report {
        val sample = Lfx2012FuelSessionHistory.Sample(identity, snapshot)
        if (samples.isNotEmpty() &&
            (samples.last().identity != identity ||
                samples.last().snapshot.observedAtUtcMillis >= snapshot.observedAtUtcMillis)
        ) {
            samples.clear()
        }
        if (samples.size == Lfx2012FuelSessionHistory.MAX_SNAPSHOTS) {
            samples.removeFirst()
        }
        samples.addLast(sample)
        return Lfx2012FuelSessionHistory.summarize(samples.toList())
    }

    @Synchronized
    fun copySamples(): List<Lfx2012FuelSessionHistory.Sample> = samples.toList()

    @Synchronized
    fun reportOrNull(): Lfx2012FuelSessionHistory.Report? =
        if (samples.isEmpty()) null else Lfx2012FuelSessionHistory.summarize(samples.toList())
}
