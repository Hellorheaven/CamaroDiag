package com.camarodiag.gen5.ui

/**
 * Known diagnostic signals. Catalog membership describes a displayable signal,
 * not proof that a specific vehicle, ECU or adapter supports it.
 * A signal is only selectable after a value is actually reported this session.
 */
data class LiveSensorDefinition(
    val id: String,
    val labelEn: String,
    val labelFr: String
)

internal object LiveSensorCatalog {
    val known: List<LiveSensorDefinition> = listOf(
        LiveSensorDefinition("RPM", "Engine speed", "Régime moteur"),
        LiveSensorDefinition("ECT", "Coolant temperature", "Température liquide de refroidissement"),
        LiveSensorDefinition("OIL", "Oil pressure (simulation only)", "Pression d'huile (simulation uniquement)"),
        LiveSensorDefinition("STFT", "Short-term fuel trim bank 1", "Correction carburant court terme banc 1"),
        LiveSensorDefinition("LTFT", "Long-term fuel trim bank 1", "Correction carburant long terme banc 1"),
        LiveSensorDefinition("STFT_B2", "Short-term fuel trim bank 2", "Correction carburant court terme banc 2"),
        LiveSensorDefinition("LTFT_B2", "Long-term fuel trim bank 2", "Correction carburant long terme banc 2"),
        LiveSensorDefinition("BAT", "Control module voltage", "Tension calculateur"),
        LiveSensorDefinition("HYBRID_BAT_LIFE", "Hybrid battery pack remaining life (PID 5B)", "Durée de vie restante batterie hybride (PID 5B)"),
        LiveSensorDefinition("SPD", "Vehicle speed", "Vitesse du véhicule"),
        LiveSensorDefinition("TPS", "Throttle position", "Position du papillon"),
        LiveSensorDefinition("REL_TPS", "Relative throttle position", "Position relative du papillon"),
        LiveSensorDefinition("CMD_TPS", "Commanded throttle position", "Commande de papillon"),
        LiveSensorDefinition("ABS_TPS_B", "Absolute throttle position B", "Position absolue papillon B"),
        LiveSensorDefinition("ABS_TPS_C", "Absolute throttle position C", "Position absolue papillon C"),
        LiveSensorDefinition("APP_D", "Accelerator pedal position D", "Position de pédale accélérateur D"),
        LiveSensorDefinition("APP_E", "Accelerator pedal position E", "Position de pédale accélérateur E"),
        LiveSensorDefinition("APP_F", "Accelerator pedal position F", "Position de pédale accélérateur F"),
        LiveSensorDefinition("LOAD", "Calculated engine load", "Charge moteur calculée"),
        LiveSensorDefinition("ABS_LOAD", "Absolute engine load", "Charge moteur absolue"),
        LiveSensorDefinition("MAP", "Intake manifold pressure", "Pression collecteur admission"),
        LiveSensorDefinition("IAT", "Intake air temperature", "Température air admission"),
        LiveSensorDefinition("AMBIENT", "Ambient air temperature", "Température extérieure"),
        LiveSensorDefinition("MAF", "Mass air flow", "Débit d'air massique"),
        LiveSensorDefinition("FUEL_PRESS", "Fuel pressure", "Pression carburant"),
        LiveSensorDefinition("TIMING", "Ignition timing advance", "Avance à l'allumage"),
        LiveSensorDefinition("EQ_RATIO", "Commanded equivalence ratio", "Rapport d'équivalence demandé"),
        LiveSensorDefinition("ETHANOL", "Ethanol content (PID 52)", "Teneur en éthanol (PID 52)"),
        LiveSensorDefinition("O2_B1S1", "Oxygen sensor B1S1 voltage", "Sonde lambda B1S1 - tension"),
        LiveSensorDefinition("O2_B1S2", "Oxygen sensor B1S2 voltage", "Sonde lambda B1S2 - tension"),
        LiveSensorDefinition("O2_B1S3", "Oxygen sensor B1S3 voltage", "Sonde lambda B1S3 - tension"),
        LiveSensorDefinition("O2_B1S4", "Oxygen sensor B1S4 voltage", "Sonde lambda B1S4 - tension"),
        LiveSensorDefinition("WBO2_B1S1", "Wideband lambda B1S1", "Lambda large bande B1S1"),
        LiveSensorDefinition("WBO2_B1S2", "Wideband lambda B1S2", "Lambda large bande B1S2"),
        LiveSensorDefinition("WBO2_B2S1", "Wideband lambda B2S1", "Lambda large bande B2S1"),
        LiveSensorDefinition("WBO2_B2S2", "Wideband lambda B2S2", "Lambda large bande B2S2"),
        LiveSensorDefinition("WBO2_I_B1S1", "WBO2_I_B1S1", "WBO2_I_B1S1"),
        LiveSensorDefinition("WBO2_I_B1S2", "WBO2_I_B1S2", "WBO2_I_B1S2"),
        LiveSensorDefinition("WBO2_I_B2S1", "WBO2_I_B2S1", "WBO2_I_B2S1"),
        LiveSensorDefinition("WBO2_I_B2S2", "WBO2_I_B2S2", "WBO2_I_B2S2")
    )

    /** Includes newly discovered signals without silently treating them as known hardware. */
    fun entriesWithObserved(observed: Collection<LiveSensorDefinition>): List<LiveSensorDefinition> =
        (known + observed).distinctBy { it.id }.sortedBy { it.id }

    /** Only signals actually read in this connection/vehicle context are selectable. */
    fun availableEntries(observed: Collection<LiveSensorDefinition>): List<LiveSensorDefinition> {
        val ids = observed.map { it.id }.toSet()
        return entriesWithObserved(observed).filter { it.id in ids }
    }

    fun mergeObserved(previous: List<LiveSensorDefinition>, fresh: Collection<LiveSensorDefinition>): List<LiveSensorDefinition> =
        availableEntries(previous + fresh)

    /** Never equate a documented PID with a successfully read PID. */
    fun isMeasured(id: String, measuredIds: Set<String>): Boolean = id in measuredIds
}
