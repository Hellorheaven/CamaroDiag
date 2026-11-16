package com.camarodiag.gen5.data.obd

import java.util.Locale

/**
 * Deterministic SAE J1979 responses for UI and regression testing without a vehicle.
 *
 * Every response is synthetic. This simulator must never be used as evidence of a
 * real Camaro's equipment, calibration, mileage, faults, or ECU identity.
 */
object CamaroLfxObdSimulator {

    private val supportedPids = setOf(
        0x05, 0x0C, 0x0D, 0x0F, 0x11, 0x1F, 0x20,
        0x22, 0x24, 0x2D, 0x2E, 0x2F, 0x31, 0x32, 0x33, 0x3C, 0x3E, 0x40,
        0x42, 0x44, 0x46, 0x49, 0x4A, 0x4C, 0x4D, 0x4E, 0x52, 0x59, 0x5A, 0x5C, 0x5E
    )

    private const val SIMULATED_VIN = "1G1TEST0000000001"
    private const val SIMULATED_CAL_ID = "LFX12SIM00000001"
    private const val SIMULATED_CVN = "12345678"
    private val simulatedEcuName = "CAMARODIAG LFX SIM".padEnd(20, ' ')

    fun createEngine(): Obd2FeatureEngine = Obd2FeatureEngine(
        ObdCommandChannel { command, _ -> response(command) }
    )

    fun response(command: String): String {
        val normalized = command.trim().uppercase(Locale.ROOT).replace(" ", "")
        return when (normalized) {
            "ATZ" -> "ELM327 v1.5"
            "ATI" -> "ELM327 v1.5 SIMULATED"
            "AT@1" -> "CamaroDiag LFX Offline Simulator"
            "ATDP" -> "ISO 15765-4 CAN (11 bit ID, 500 kbaud)"
            "ATRV" -> "12.4V"
            "ATE0", "ATL0", "ATS0", "ATS1", "ATH0", "ATH1", "ATSP6" -> "OK"
            "0100" -> bitmapResponse(0x00)
            "0120" -> bitmapResponse(0x20)
            "0140" -> bitmapResponse(0x40)
            // Two confirmed DTCs are returned by Mode 03 below; keep the PID 01
            // DTC count consistent while leaving the MIL bit clear.
            "0101" -> "410102000000"
            "0105" -> "41057F"
            "010C" -> "410C0BB8"
            "010D" -> "410D00"
            "010F" -> "410F3C"
            "0111" -> "411114"
            "011F" -> "411F003C"
            "0122" -> "412203E8"
            "0124" -> "412480004000"
            "012D" -> "412D80"
            "012E" -> "412E40"
            "012F" -> "412F80"
            "0131" -> "41310064"
            "0132" -> "41328000"
            "0133" -> "413364"
            "013C" -> "413C0320"
            "013E" -> "413E0330"
            "0142" -> "414236B0"
            "0144" -> "41448000"
            "0146" -> "41464B"
            "0149" -> "414980"
            "014A" -> "414A80"
            "014C" -> "414C10"
            "014D" -> "414D0001"
            "014E" -> "414E0002"
            "0152" -> "415280"
            "0159" -> "415903E8"
            "015A" -> "415A80"
            "015C" -> "415C64"
            "015E" -> "415E0014"
            "03" -> "4301330171"
            "07" -> "470000"
            "0A" -> "4A0000"
            "0902" -> asciiRecord(0x02, SIMULATED_VIN)
            "0904" -> asciiRecord(0x04, SIMULATED_CAL_ID)
            "0906" -> "490601" + SIMULATED_CVN
            "090A" -> asciiRecord(0x0A, simulatedEcuName)
            "0600" -> "NO DATA"
            else -> "NO DATA"
        }
    }

    private fun bitmapResponse(basePid: Int): String {
        val bytes = IntArray(4)
        supportedPids.filter { it in (basePid + 1)..(basePid + 0x20) }.forEach { pid ->
            val bit = pid - basePid - 1
            val byteIndex = bit / 8
            val mask = 1 shl (7 - bit % 8)
            bytes[byteIndex] = bytes[byteIndex] or mask
        }
        return buildString {
            append("7E8 06 41 ")
            append("%02X".format(Locale.US, basePid))
            bytes.forEach { append(" %02X".format(Locale.US, it)) }
        }
    }

    private fun asciiRecord(pid: Int, value: String): String =
        "49%02X01".format(Locale.US, pid) +
            value.toByteArray(Charsets.US_ASCII)
                .joinToString("") { "%02X".format(Locale.US, it.toInt() and 0xFF) }
}
