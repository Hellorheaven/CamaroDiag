package com.camarodiag.gen5.data.obd

/**
 * Restores the standard functional OBD session after a physically addressed STPX
 * diagnostic transaction. Without this, ATCRA and STCFCPA may leave subsequent
 * Mode 01/09 traffic restricted to the preceding ECU response address.
 *
 * The commands follow ScanTool/OBDLink Family Reference and Programming Manual:
 * ATCRA (without argument), STCFCPC, STCSEGR, STCSEGT and STFA.
 * Restore the application's negotiated OBD profile, not arbitrary pre-app
 * settings. No destructive ECU service is sent.
 */
internal object StnUdsAdapterRestoration {
    fun commandsFor(protocol: Int) = listOf(
        "STCFCPC", // Clear physical-response ISO-TP flow-control address pairs.
        "ATCRA",   // Reset CAN receive address mask/filter.
        "STCSEGR 0", // Disable STN receive ISO-TP reassembly.
        "STCSEGT 0", // Disable STN transmit ISO-TP segmentation.
        "STFA"     // Restore automatic OBD response filtering.
    ) + ElmObdSessionProfile(protocol).restoreCommands()

    val commands = commandsFor(6)

    data class Result(val failedCommands: List<String>) {
        val succeeded: Boolean get() = failedCommands.isEmpty()
    }

    /** Always attempt every cleanup command, even when an earlier one fails. */
    suspend fun restore(protocol: Int = 6, send: suspend (String) -> String): Result {
        val failures = mutableListOf<String>()
        for (command in commandsFor(protocol)) {
            val response = runCatching { send(command) }.getOrNull()
            val acknowledged = response?.let(CanConsoleAdapterRestorationPolicy::accepted) == true
            if (!acknowledged) failures += command
        }
        return Result(failures)
    }
}
