package com.camarodiag.gen5.data.obd

import java.io.Writer
import java.util.Locale

/**
 * Machine-readable, read-only export of one previously validated physical
 * LFX fuel observation session. No ECU communication or calibration action.
 *
 * Empty numeric cells mean "no measurement", never 0%. Availability is
 * exported separately to distinguish unsupported from invalid responses.
 */
object Lfx2012FuelHistoryCsv {
    private const val MAX_ROWS = Lfx2012FuelSessionHistory.MAX_SNAPSHOTS

    fun export(
        samples: List<Lfx2012FuelSessionHistory.Sample>,
        output: Writer
    ) {
        // Validates VIN/session/source, strict chronology, and size before
        // streaming any bytes into a destination file.
        Lfx2012FuelSessionHistory.summarize(samples)
        require(samples.size <= MAX_ROWS)
        output.write(
            "vin,session_id,source,utc_millis,pid_hex,availability,reported_percent,raw_byte\n"
        )
        for (sample in samples) {
            val identity = sample.identity
            for (reading in sample.snapshot.readings) {
                output.write(csv(identity.vin))
                output.write(','.code)
                output.write(csv(identity.sessionId))
                output.write(','.code)
                output.write(identity.source.name)
                output.write(','.code)
                output.write(sample.snapshot.observedAtUtcMillis.toString())
                output.write(','.code)
                output.write(String.format(Locale.ROOT, "%02X", reading.pid))
                output.write(','.code)
                output.write(reading.availability.name)
                output.write(','.code)
                if (reading.percent != null) {
                    output.write(String.format(Locale.ROOT, "%.8f", reading.percent))
                }
                output.write(','.code)
                if (reading.raw != null) output.write(reading.raw.toString())
                output.write('\n'.code)
            }
        }
        output.flush()
    }

    private fun csv(value: String): String {
        // Prevent CSV formulas in arbitrary session IDs without altering the
        // underlying stored identity. Double-quote escaping is standard CSV.
        val firstNonSpace = value.trimStart()
        val unsafeFormula = firstNonSpace.startsWith('=') ||
            firstNonSpace.startsWith('+') || firstNonSpace.startsWith('-') ||
            firstNonSpace.startsWith('@')
        val sanitized = if (unsafeFormula) "'$value" else value
        return "\"" + sanitized.replace("\"", "\"\"") + "\""
    }
}
