package com.camarodiag.gen5.ui

import java.util.Locale

data class RecordedLivePid(
    val id: String,
    val label: String,
    val value: Double,
    val unit: String
)

data class RecordedLiveSample(
    val timestampMillis: Long,
    val values: List<RecordedLivePid>
)

data class LiveRecordingState(
    val isRecording: Boolean = false,
    val sampleCount: Int = 0,
    val startedAtMillis: Long? = null,
    val stoppedAtMillis: Long? = null,
    val message: String = ""
)

/** CSV export for live sensor recordings. This is data logging, not ECU calibration writing. */
object LiveTelemetryCsv {
    fun encode(
        samples: List<RecordedLiveSample>,
        vin: String,
        vehicleLabel: String
    ): String = buildString {
        appendLine("timestamp_ms,vin,vehicle,pid,label,value,unit")
        samples.forEach { sample ->
            sample.values.forEach { pid ->
                append(sample.timestampMillis)
                append(',')
                append(csv(vin))
                append(',')
                append(csv(vehicleLabel))
                append(',')
                append(csv(pid.id))
                append(',')
                append(csv(pid.label))
                append(',')
                append(String.format(Locale.US, "%.5f", pid.value))
                append(',')
                append(csv(pid.unit))
                append('\n')
            }
        }
    }

    private fun csv(value: String): String =
        "\"" + value.replace("\"", "\"\"") + "\""
}
