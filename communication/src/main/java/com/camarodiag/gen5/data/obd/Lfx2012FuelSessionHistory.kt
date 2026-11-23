package com.camarodiag.gen5.data.obd

/**
 * Session-local, read-only analysis of physically reported LFX fuel observations.
 *
 * No ECU commands, conversions, calibration activation or compatibility inference.
 * Snapshots must belong to the same explicitly identified vehicle and connection
 * session. Missing PID readings stay missing; a reported 0% remains a valid value.
 */
object Lfx2012FuelSessionHistory {
    const val MAX_SNAPSHOTS = 512

    data class SessionIdentity(
        val vin: String,
        val sessionId: String,
        val source: Lfx2012FuelReadOnly.Source
    ) {
        init {
            require(vin.matches(Regex("[A-HJ-NPR-Z0-9]{17}")))
            require(sessionId.isNotBlank() && sessionId.length <= 128 &&
                sessionId.none(Char::isISOControl)) {
                "Fuel session identifier is not a valid single-line value"
            }
        }
    }

    data class Sample(
        val identity: SessionIdentity,
        val snapshot: Lfx2012FuelReadOnly.Snapshot
    ) {
        init {
            require(identity.source == snapshot.source) {
                "Fuel observation source must match its physical session"
            }
        }
    }

    data class PidSummary(
        val pid: Int,
        val reportedCount: Int,
        val unsupportedCount: Int,
        val noResponseCount: Int,
        val firstReported: Double?,
        val lastReported: Double?,
        val minimum: Double?,
        val maximum: Double?,
        val delta: Double?,
        /** Null when the very last physical survey did not report this PID. */
        val currentSnapshotPercent: Double?,
        /** Timestamp of the last actual measurement, not the survey timestamp. */
        val lastReportedAtUtcMillis: Long?
    )

    data class Report(
        val session: SessionIdentity,
        val count: Int,
        val startedAtUtcMillis: Long,
        val endedAtUtcMillis: Long,
        val readings: List<PidSummary>
    ) {
        /** PID readings alone cannot verify a physical sensor or E85 calibration. */
        val sensorOrFlexFuelCalibrationVerified: Boolean get() = false
    }

    fun summarize(samples: List<Sample>): Report {
        require(samples.isNotEmpty() && samples.size <= MAX_SNAPSHOTS) {
            "A fuel history requires a bounded, nonempty physical session"
        }
        val first = samples.first()
        require(samples.all { it.identity == first.identity }) {
            "Fuel history cannot combine different vehicles or connection sessions"
        }
        require(samples.zipWithNext().all { (a, b) ->
            a.snapshot.observedAtUtcMillis < b.snapshot.observedAtUtcMillis
        }) { "Fuel history timestamps must increase strictly" }

        val readings = Lfx2012FuelReadOnly.PIDS.map { pid ->
            val values = samples.map { it.snapshot.reading(pid) }
            val reported = values.filter {
                it.availability == Lfx2012FuelReadOnly.Availability.REPORTED
            }.map { checkNotNull(it.percent) }
            val mostRecentReportedIndex = values.indexOfLast {
                it.availability == Lfx2012FuelReadOnly.Availability.REPORTED
            }
            PidSummary(
                pid = pid,
                reportedCount = reported.size,
                unsupportedCount = values.count {
                    it.availability == Lfx2012FuelReadOnly.Availability.UNSUPPORTED
                },
                noResponseCount = values.count {
                    it.availability == Lfx2012FuelReadOnly.Availability.NO_VALID_RESPONSE
                },
                firstReported = reported.firstOrNull(),
                lastReported = reported.lastOrNull(),
                minimum = reported.minOrNull(),
                maximum = reported.maxOrNull(),
                delta = if (reported.size >= 2) reported.last() - reported.first() else null,
                currentSnapshotPercent = values.last().takeIf {
                    it.availability == Lfx2012FuelReadOnly.Availability.REPORTED
                }?.percent,
                lastReportedAtUtcMillis = if (mostRecentReportedIndex >= 0)
                    samples[mostRecentReportedIndex].snapshot.observedAtUtcMillis else null
            )
        }
        return Report(
            first.identity, samples.size,
            first.snapshot.observedAtUtcMillis,
            samples.last().snapshot.observedAtUtcMillis,
            readings
        )
    }
}
