package com.camarodiag.gen5.domain.model

import java.util.Locale

enum class AppLanguage(val code: String, val label: String) {
    EN("en", "English"),
    FR("fr", "Français"),
    DE("de", "Deutsch"),
    IT("it", "Italiano"),
    ES("es", "Español"),
    HE("he", "עברית"),
    RU("ru", "Русский"),
    ZH("zh", "简体中文"),
    JA("ja", "日本語"),
    KO("ko", "한국어"),
    AR("ar", "العربية");

    companion object {
        fun fromLocale(locale: Locale): AppLanguage =
            entries.firstOrNull { it.code == locale.language.lowercase(Locale.ROOT) } ?: EN
    }
}

enum class UnitSystem { METRIC, IMPERIAL }

enum class CamaroGeneration(val label: String, val years: IntRange) {
    GEN5("Gen 5", 2010..2015),
    GEN6("Gen 6", 2016..2024)
}

enum class MarketRegion(val code: String, val labelEn: String, val labelFr: String) {
    US_CA("US/CA", "North America (US / Canada)", "Amérique du Nord (US / Canada)"),
    EU_ECE("EU/ECE", "Europe Export (ECE / T96 / T84)", "Export Europe (ECE / T96 / T84)")
}

enum class TransmissionType(val code: String, val labelEn: String, val labelFr: String) {
    MANUAL("MT", "Manual (AY6 / TR6060 / TR3160)", "Manuelle (AY6 / TR6060 / TR3160)"),
    AUTOMATIC("AT", "Automatic (6L50 / 6L80 / 8L45 / 8L90 / 10L60 / 10L80; year-dependent)", "Automatique (6L50 / 6L80 / 8L45 / 8L90 / 10L60 / 10L80 selon année)")
}

enum class CamaroEngine(
    val rpo: String,
    val nameEn: String,
    val nameFr: String,
    val displacement: String,
    val years: IntRange,
    val ecmType: String
) {
    LLT("LLT", "3.6L V6 SIDI (2010–2011)", "3.6L V6 SIDI (2010–2011)", "3.6L V6", 2010..2011, "Bosch E69"),
    LFX("LFX", "3.6L V6 SIDI FlexFuel (2012–2015)", "3.6L V6 SIDI FlexFuel (2012–2015)", "3.6L V6", 2012..2015, "ACDelco E39 / E39A"),
    LS3("LS3", "6.2L V8 Manual (426 HP)", "6.2L V8 Manuelle (426 ch)", "6.2L V8", 2010..2015, "ACDelco E38"),
    L99("L99", "6.2L V8 AFM Auto (400 HP)", "6.2L V8 AFM Auto (400 ch)", "6.2L V8", 2010..2015, "ACDelco E38"),
    LSA("LSA", "6.2L V8 Supercharged ZL1 (580 HP)", "6.2L V8 Compresseur ZL1 (580 ch)", "6.2L V8", 2012..2015, "ACDelco E67"),
    LS7("LS7", "7.0L V8 Z/28 (505 HP)", "7.0L V8 Z/28 (505 ch)", "7.0L V8", 2014..2015, "ACDelco E38"),
    LTG("LTG", "2.0L Turbo I4 (275 HP)", "2.0L Turbo 4 cylindres (275 ch)", "2.0L Turbo", 2016..2020, "GM E80 (RPO LTG; CAL ID verification required)"),
    LGX("LGX", "3.6L V6 SIDI (335 HP)", "3.6L V6 SIDI (335 ch)", "3.6L V6", 2016..2024, "GM E92 / E93 (variant-dependent)"),
    LT1("LT1", "6.2L V8 DI (455 HP)", "6.2L V8 injection directe (455 ch)", "6.2L V8", 2016..2024, "GM E92"),
    LT4("LT4", "6.2L V8 Supercharged ZL1 (650 HP)", "6.2L V8 compresseur ZL1 (650 ch)", "6.2L V8", 2017..2024, "GM E92")
}

data class VehicleProfile(
    val modelYear: Int = 2012,
    val engine: CamaroEngine = CamaroEngine.LFX,
    val transmission: TransmissionType = TransmissionType.MANUAL,
    val market: MarketRegion = MarketRegion.EU_ECE,
    val vin: String = ""
) {
    val generation: CamaroGeneration get() = if (modelYear <= 2015) CamaroGeneration.GEN5 else CamaroGeneration.GEN6
}

enum class CanBusType(val label: String, val pinout: String) {
    HS_CAN("HS-CAN (500 kbps)", "Pins 6 & 14"),
    SW_CAN("SW-CAN GMLAN (33.3 kbps)", "Pin 1"),
    UNKNOWN("Bus to identify", "Réseau à identifier")
}

enum class AdapterTier(val titleEn: String, val titleFr: String) {
    PRO_STN("STN1170 / STN2255 (OBDLink MX+ / vLinker MC+)", "STN1170 / STN2255 (OBDLink MX+ / vLinker MC+)"),
    GENUINE_ELM("Genuine ELM327 v1.4b/v1.5 (HS-CAN Only)", "ELM327 Authentique v1.4b/v1.5 (HS-CAN Seul)"),
    CLONE_ELM("Generic ELM327 v2.1 Clone (Limited)", "Clone ELM327 v2.1 Générique (Limité)"),
    DISCONNECTED("Not connected", "Non connecté")
}

data class AdapterCapabilities(
    val deviceName: String = "Not connected",
    val macAddress: String = "",
    val tier: AdapterTier = AdapterTier.DISCONNECTED,
    val firmwareId: String = "",
    val supportsLongFrames: Boolean = false,
    val supportsSwCan: Boolean = false,
    val supportsCanHeaders: Boolean = false,
    val rawProbeLog: List<String> = emptyList()
)

enum class ModuleStatus { ACCESSIBLE, REQUIRES_SWCAN, UNSUPPORTED_BY_ADAPTER, NOT_EQUIPPED, DISCOVERY_REQUIRED }

data class EcuModule(
    val id: String,
    val shortName: String,
    val nameEn: String,
    val nameFr: String,
    val txHeader: String,
    val rxHeader: String,
    val bus: CanBusType,
    val status: ModuleStatus,
    val dtcCount: Int = 0,
    val notesEn: String = "",
    val notesFr: String = ""
)

data class LivePidValue(
    val id: String,
    val labelEn: String,
    val labelFr: String,
    val metricValue: Double,
    val metricUnit: String,
    val imperialValue: Double,
    val imperialUnit: String,
    val minMetric: Double,
    val maxMetric: Double,
    val history: List<Double> = emptyList()
)

data class DiagnosticDtc(
    val code: String,
    val module: String,
    val statusEn: String,
    val statusFr: String,
    val descEn: String,
    val descFr: String,
    val isPending: Boolean = false
)

enum class ConfidenceLevel(val labelEn: String, val labelFr: String) {
    HIGH("High Confidence", "Confiance Élevée"),
    MODERATE("Moderate / Volatile", "Modérée / Volatile"),
    UNAVAILABLE("Unavailable / Restricted", "Indisponible / Restreint")
}

data class MileageSourceReading(
    val moduleId: String,
    val moduleNameEn: String,
    val moduleNameFr: String,
    val didInfo: String,
    val kmValue: Double?,
    val confidence: ConfidenceLevel,
    val caveatEn: String,
    val caveatFr: String,
    val profileEvidenceReference: String? = null
)

data class MileageAuditReport(
    val timestamp: Long,
    val vin: String,
    val readings: List<MileageSourceReading>,
    val maxDeltaKm: Double,
    val hasDiscrepancy: Boolean,
    val summaryEn: String,
    val summaryFr: String
)
