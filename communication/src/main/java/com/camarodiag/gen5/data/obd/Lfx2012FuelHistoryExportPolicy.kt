package com.camarodiag.gen5.data.obd

/**
 * The user exports one immutable, completed fuel observation window.
 * A new sample, a session change or an in-flight physical read invalidates
 * an earlier export snapshot. No ECU commands or calibration writes.
 */
object Lfx2012FuelHistoryExportPolicy {
    fun canPublish(
        captured: List<Lfx2012FuelSessionHistory.Sample>,
        active: List<Lfx2012FuelSessionHistory.Sample>,
        readingInProgress: Boolean
    ): Boolean = !readingInProgress && captured.isNotEmpty() &&
        captured == active && runCatching {
            Lfx2012FuelSessionHistory.summarize(captured)
        }.isSuccess
}
