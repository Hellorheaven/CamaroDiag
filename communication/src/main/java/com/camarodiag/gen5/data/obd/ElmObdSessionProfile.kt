package com.camarodiag.gen5.data.obd

/** Functional OBD baseline for a physical ELM session, never an ECU address guess. */
internal data class ElmObdSessionProfile(val protocol: Int) {
    init { require(protocol in 0..10) }

    fun restoreCommands(): List<String> = buildList {
        // Unknown or non-CAN protocol: reset custom CAN headers/settings before
        // restoring automatic or the selected legacy protocol. Never send a
        // CAN functional header on that channel.
        if (protocol !in 6..9) add("ATD")
        add("ATSP" + protocol.toString(16).uppercase(java.util.Locale.ROOT))
        when (protocol) {
            6, 8 -> add("ATSH 7DF")
            7, 9 -> add("ATSH 18DB33F1")
        }
        addAll(listOf("ATE0", "ATL0", "ATH1", "ATS0"))
        if (protocol in 6..9) add("ATCAF1")
    }

    companion object {
        val AUTO = ElmObdSessionProfile(0)

        /** ATDPN returns one protocol number, optionally prefixed by A (auto). */
        fun parseProtocolNumber(raw: String): Int? {
            val lines = raw.lineSequence().map { it.trim().removeSuffix(">").trim().uppercase(java.util.Locale.ROOT) }
                .filter { it.isNotEmpty() && it != "ATDPN" }.toList()
            val token = lines.singleOrNull() ?: return null
            if (!token.matches(Regex("A?[0-9A]"))) return null
            return token.last().digitToInt(16).takeIf { it in 1..10 }
        }

        /** Do not freeze an automatically selected protocol without ECU evidence. */
        fun fromObservation(reported: Int?, positivePid00: Boolean): ElmObdSessionProfile =
            // Positive Mode 01/PID 00 is evidence of a functional vehicle
            // response on the adapter-selected protocol. Preserve the actual
            // observed ELM 1..A, including J1850, ISO9141, KWP and J1939.
            // No CAN header may be inferred for non-CAN protocols.
            if (positivePid00 && reported != null && reported in 1..10)
                ElmObdSessionProfile(reported) else AUTO
    }
}
