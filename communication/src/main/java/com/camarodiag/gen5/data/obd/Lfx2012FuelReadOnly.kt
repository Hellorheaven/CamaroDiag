package com.camarodiag.gen5.data.obd

/**
 * Read-only SAE Mode 01 fuel observations for the LFX 2012 screen.
 * Neither an ECU-reported ethanol value nor a trim proves a fitted flex-fuel
 * sensor, a conversion, an enabled calibration or any programming capability.
 */
object Lfx2012FuelReadOnly {
    const val ETHANOL = 0x52
    const val STFT_BANK_1 = 0x06
    const val LTFT_BANK_1 = 0x07
    const val STFT_BANK_2 = 0x08
    const val LTFT_BANK_2 = 0x09
    val PIDS = listOf(ETHANOL, STFT_BANK_1, LTFT_BANK_1, STFT_BANK_2, LTFT_BANK_2)

    enum class Source { ELM, J2534 }
    enum class Availability { REPORTED, UNSUPPORTED, NO_VALID_RESPONSE }

    /** Raw OBD replies, sourced from one actual session, before decoding. */
    data class Survey(
        val source: Source,
        val supportedPids: Set<Int>,
        val responses: Map<Int, String?>
    ) {
        init {
            require(responses.keys == PIDS.toSet()) {
                "A fuel survey must name every requested PID, including unsupported ones."
            }
            require(responses.all { (pid, reply) -> pid in supportedPids || reply == null }) {
                "An unadvertised PID cannot be claimed as measured."
            }
        }
    }

    data class Reading(
        val pid: Int,
        val availability: Availability,
        val percent: Double? = null,
        val raw: Int? = null
    ) {
        init {
            require(pid in PIDS)
            require((availability == Availability.REPORTED) ==
                (percent != null && raw != null))
            require(percent == null || percent in -100.0..100.0)
            require(raw == null || raw in 0..255)
        }
    }

    data class Snapshot(
        val source: Source,
        val observedAtUtcMillis: Long,
        val readings: List<Reading>
    ) {
        init {
            require(observedAtUtcMillis > 0L)
            require(readings.map { it.pid } == PIDS) {
                "Missing or reordered physical fuel readings."
            }
        }
        fun reading(pid: Int): Reading = readings.first { it.pid == pid }
        val ethanolPercent: Double? get() = reading(ETHANOL).percent
        /** Always false: physical PID support is not evidence of FlexFuel activation. */
        val flexFuelCalibratedOrSensorVerified: Boolean get() = false
    }

    /** Invalid raw payload is explicitly unavailable, never 0%. */
    fun inspect(
        survey: Survey,
        rawData: Map<Int, IntArray?>,
        observedAtUtcMillis: Long
    ): Snapshot {
        require(rawData.keys == PIDS.toSet()) { "All fuel PIDs must have a decoding slot." }
        val readings = PIDS.map { pid ->
            if (pid !in survey.supportedPids) {
                Reading(pid, Availability.UNSUPPORTED)
            } else {
                val data = rawData[pid]
                if (data == null || data.size != 1 || data[0] !in 0..255) {
                    Reading(pid, Availability.NO_VALID_RESPONSE)
                } else {
                    val value = if (pid == ETHANOL)
                        data[0] * 100.0 / 255.0
                    else data[0] * 100.0 / 128.0 - 100.0
                    Reading(pid, Availability.REPORTED, value, data[0])
                }
            }
        }
        return Snapshot(survey.source, observedAtUtcMillis, readings)
    }
}
