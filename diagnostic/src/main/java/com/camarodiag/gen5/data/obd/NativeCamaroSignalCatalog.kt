package com.camarodiag.gen5.data.obd

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * First-party Camaro signal catalogue.
 *
 * This catalogue is intentionally independent from OBDb. A definition is eligible
 * for live requests only after its physical addressing and decoding have been
 * verified against a documented Camaro configuration and an on-vehicle capture.
 * Empty/undocumented entries are never promoted to executable signals.
 */
data class NativeCamaroSignal(
    val id: String,
    val name: String,
    val category: String,
    val modelYears: Set<Int>,
    val engineCodes: Set<String>,
    val service: Int,
    val identifierHex: String,
    val requestCanId: Int?,
    val responseCanId: Int?,
    val bitOffset: Int,
    val bitLength: Int,
    val scale: Double,
    val offset: Double,
    val unit: String,
    val verificationStatus: VerificationStatus,
    val evidenceReferences: List<String>,
    val verifiedVehicleConfigurations: Set<String>
) {
    enum class VerificationStatus { PROPOSED, DOCUMENTED, VEHICLE_VERIFIED }

    val isExecutable: Boolean
        get() = verificationStatus == VerificationStatus.VEHICLE_VERIFIED &&
            service == 0x22 &&
            identifierHex.length == 4 && identifierHex.isHex() &&
            requestCanId.isValid11BitCanId() &&
            responseCanId.isValid11BitCanId() &&
            requestCanId != responseCanId &&
            bitOffset >= 0 && bitLength in 1..256 &&
            scale.isFinite() && scale != 0.0 && offset.isFinite() &&
            evidenceReferences.isNotEmpty() &&
            verifiedVehicleConfigurations.isNotEmpty()

    fun decode(payload: ByteArray): Double {
        require(isExecutable) { "Native signal is not verified for live use: $id" }
        require(payload.isNotEmpty()) { "Empty UDS response payload." }
        val totalBits = payload.size * 8
        require(bitOffset + bitLength <= totalBits) {
            "Signal $id requires ${bitOffset + bitLength} bits; received $totalBits."
        }
        var raw = 0L
        for (index in 0 until bitLength) {
            val absoluteBit = bitOffset + index
            val byteIndex = absoluteBit / 8
            val bitInByte = 7 - (absoluteBit % 8)
            raw = (raw shl 1) or ((payload[byteIndex].toInt() ushr bitInByte) and 1).toLong()
        }
        return raw.toDouble() * scale + offset
    }
}

data class NativeCamaroSignalCatalog(
    val schemaVersion: Int,
    val signals: List<NativeCamaroSignal>
) {
    fun executableFor(modelYear: Int, engineCode: String): List<NativeCamaroSignal> =
        signals.filter {
            it.isExecutable &&
                (it.modelYears.isEmpty() || modelYear in it.modelYears) &&
                (it.engineCodes.isEmpty() || engineCode.uppercase(Locale.ROOT) in it.engineCodes)
        }

    fun find(id: String): NativeCamaroSignal? =
        signals.firstOrNull { it.id.equals(id, ignoreCase = true) }

    companion object {
        const val ASSET_NAME = "camaro-native-signalset-v1.json"

        fun fromJson(json: String): NativeCamaroSignalCatalog {
            val root = JSONObject(json)
            val version = root.optInt("schemaVersion", 0)
            require(version == 1) { "Unsupported native Camaro signal schema version: $version" }
            val signals = root.optJSONArray("signals").objects().map { item ->
                val status = NativeCamaroSignal.VerificationStatus.valueOf(
                    item.optString("verificationStatus", "PROPOSED").uppercase(Locale.ROOT)
                )
                NativeCamaroSignal(
                    id = item.optString("id"),
                    name = item.optString("name"),
                    category = item.optString("category"),
                    modelYears = item.optJSONArray("modelYears").ints(),
                    engineCodes = item.optJSONArray("engineCodes").strings().map {
                        it.uppercase(Locale.ROOT)
                    }.toSet(),
                    service = item.optString("service").removePrefix("0x").toIntOrNull(16) ?: 0,
                    identifierHex = item.optString("identifier").removePrefix("0x").uppercase(Locale.ROOT),
                    requestCanId = item.optString("requestCanId").toCan11BitId(),
                    responseCanId = item.optString("responseCanId").toCan11BitId(),
                    bitOffset = item.optInt("bitOffset", 0),
                    bitLength = item.optInt("bitLength", 0),
                    scale = item.optDouble("scale", Double.NaN),
                    offset = item.optDouble("offset", 0.0),
                    unit = item.optString("unit"),
                    verificationStatus = status,
                    evidenceReferences = item.optJSONArray("evidenceReferences").strings(),
                    verifiedVehicleConfigurations = item.optJSONArray("verifiedVehicleConfigurations").strings().toSet()
                ).also { signal ->
                    require(signal.id.isNotBlank() && signal.name.isNotBlank()) {
                        "Native signal id and name are required."
                    }
                    require(signal.identifierHex.length == 4 && signal.identifierHex.isHex()) {
                        "Native signal ${signal.id} must use a four-digit UDS DID."
                    }
                    require(signal.bitOffset >= 0 && signal.bitLength in 1..256) {
                        "Native signal ${signal.id} has an invalid bit range."
                    }
                }
            }
            require(signals.map { it.id.lowercase(Locale.ROOT) }.distinct().size == signals.size) {
                "Duplicate native Camaro signal IDs."
            }
            return NativeCamaroSignalCatalog(version, signals)
        }
    }
}

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }

private fun JSONArray?.ints(): Set<Int> =
    if (this == null) emptySet() else (0 until length()).mapNotNull { optInt(it).takeIf { value -> value > 0 } }.toSet()

private fun String.isHex(): Boolean = isNotEmpty() && all { it.digitToIntOrNull(16) != null }

private fun Int?.isValid11BitCanId(): Boolean = this != null && this in 0x000..0x7FF

private fun String.toCan11BitId(): Int? =
    uppercase(Locale.ROOT).takeIf { it.length == 3 && it.isHex() }
        ?.toIntOrNull(16)?.takeIf { it in 0x000..0x7FF }
