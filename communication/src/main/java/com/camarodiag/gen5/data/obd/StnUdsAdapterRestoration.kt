package com.camarodiag.gen5.data.obd

/**
 * Restores the standard functional OBD session after a physically addressed STPX
 * diagnostic transaction. Without this, ATCRA and STCFCPA may leave subsequent
 * Mode 01/09 traffic restricted to the preceding ECU response address.
 *
 * The commands follow ScanTool/OBDLink Family Reference and Programming Manual:
 * ATCRA (without argument), STCFCPC, STCSEGR, STCSEGT and STFA.
 * This restores CamaroDiag's normal 11-bit HS-CAN mode, not arbitrary pre-app
 * user settings. No destructive ECU service is sent.
 */
internal object StnUdsAdapterRestoration {
    val commands = listOf(
        "STCFCPC", // Clear physical-response ISO-TP flow-control address pairs.
        "ATCRA",   // Reset CAN receive address mask/filter.
        "STCSEGR 0", // Disable STN receive ISO-TP reassembly.
        "STCSEGT 0", // Disable STN transmit ISO-TP segmentation.
        "STFA",    // Restore automatic OBD response filtering.
        "ATSP6",   // CamaroDiag standard 11-bit, 500 kbit/s HS-CAN mode.
        "ATSH 7DF", // Standard functional powertrain request header.
        "ATCAF1",  // ELM-compatible automatic CAN formatting.
        "ATH1",    // Preserve headers for real ECU responder attribution.
        "ATS0"     // Restore compact responses expected by the OBD parser.
    )

    data class Result(val failedCommands: List<String>) {
        val succeeded: Boolean get() = failedCommands.isEmpty()
    }

    /** Always attempt every cleanup command, even when an earlier one fails. */
    suspend fun restore(send: suspend (String) -> String): Result {
        val failures = mutableListOf<String>()
        for (command in commands) {
            val response = runCatching { send(command) }.getOrNull()
            val acknowledged = response?.replace('\r', '\n')?.lines()?.any {
                it.trim().equals("OK", ignoreCase = true)
            } == true
            if (!acknowledged) failures += command
        }
        return Result(failures)
    }
}
