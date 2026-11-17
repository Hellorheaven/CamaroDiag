package com.camarodiag.gen5.domain.model

private fun v8CircuitDtc(camshaftEn: String, camshaftFr: String) = DtcVehicleExplanation(
    "Le calculateur détecte un défaut électrique dans le circuit de commande VVT d'$camshaftFr du banc 1. Cette surveillance concerne le circuit de sortie ; elle ne condamne pas automatiquement l'électrovanne.",
    "The ECM detects an electrical fault in the Bank 1 $camshaftEn VVT actuator circuit. This monitor concerns the output circuit; it does not automatically condemn the solenoid.",
    listOf("Faisceau ou connecteur ouvert, en court-circuit ou oxydé", "Électrovanne VVT défectueuse", "Alimentation, masse ou driver ECM en défaut"),
    listOf("Open, shorted or corroded harness/connector", "Faulty VVT actuator solenoid", "Power, ground or ECM driver fault"),
    listOf("Contrôler connecteur et faisceau de l'actionneur banc 1", "Vérifier alimentation, masse et continuité", "Tester la commande avant remplacement de l'électrovanne"),
    listOf("Inspect Bank 1 actuator connector and harness", "Check power, ground and continuity", "Test driver command before replacing the solenoid"),
    "Modérée à élevée", "Moderate to high"
)

private fun v8PerformanceDtc(camshaftEn: String, camshaftFr: String) = DtcVehicleExplanation(
    "Sur le L99, le calculateur constate que le déphasage d'$camshaftFr du banc 1 ne suit pas la position demandée. Le contrôle dépend des seuils de calibration GM et de la pression d'huile disponible au phaser.",
    "On the L99, the ECM detects that Bank 1 $camshaftEn cam phasing does not follow the desired position. The monitor uses GM calibration thresholds and depends on oil pressure available to the phaser.",
    listOf("Niveau, viscosité ou état d'huile incorrect", "Pression ou débit d'huile insuffisant", "Électrovanne VVT grippée ou obstruée", "Déphaseur ou distribution mécanique défectueux"),
    listOf("Incorrect oil level, viscosity or condition", "Insufficient oil pressure or flow", "Sticking or restricted VVT solenoid", "Faulty phaser or mechanical timing"),
    listOf("Contrôler huile et pression moteur", "Comparer angle demandé et réel avec l'outil de diagnostic", "Contrôler électrovanne et circuit avant ouverture de la distribution"),
    listOf("Check engine oil and pressure", "Compare commanded and actual cam angle with a scan tool", "Check solenoid and circuit before opening the timing system"),
    "Élevée — diagnostic rapide recommandé", "High — prompt diagnosis recommended"
)

private fun v8CorrelationDtc(sensor: String, camshaftEn: String, camshaftFr: String) = DtcVehicleExplanation(
    "Le calculateur détecte un décalage de synchronisation entre le vilebrequin et l'arbre à cames du banc 1, capteur $sensor. Le défaut peut être électrique ou mécanique.",
    "The ECM detects a crankshaft-to-camshaft synchronization mismatch on Bank 1, Sensor $sensor. The fault may be electrical or mechanical.",
    listOf("Chaîne de distribution détendue ou tendeur défectueux", "Capteur vilebrequin/arbre à cames ou faisceau intermittent", "Reluctor déplacé ou signal perturbé", "Sur le L99, déphaseur non revenu en position de repos"),
    listOf("Loose timing chain or faulty tensioner", "Intermittent crankshaft/camshaft sensor or wiring", "Shifted reluctor or disturbed signal", "On L99, phaser not returning to its parked position"),
    listOf("Contrôler les signaux de synchronisation et les défauts capteurs associés", "Comparer l'angle appris et la position de repos", "Inspecter chaîne, tendeur et reluctor avant remplacement de capteur"),
    listOf("Check synchronization signals and related sensor DTCs", "Compare learned angle and parked position", "Inspect chain, tensioner and reluctor before replacing a sensor"),
    "Élevée — mauvais calage moteur possible", "High — incorrect engine timing possible"
)

object CamaroV8DtcCatalog {
    private val l99 = mapOf(
        "P0010" to v8CircuitDtc("intake", "admission"),
        "P0011" to v8PerformanceDtc("intake", "admission"),
        "P0013" to v8CircuitDtc("exhaust", "échappement"),
        "P0014" to v8PerformanceDtc("exhaust", "échappement"),
        "P0016" to v8CorrelationDtc("A", "intake", "admission"),
        "P0017" to v8CorrelationDtc("B", "exhaust", "échappement")
    )
    private val ls3 = mapOf(
        "P0016" to v8CorrelationDtc("A", "intake", "admission"),
        "P0017" to v8CorrelationDtc("B", "exhaust", "échappement")
    )

    fun find(code: String, vehicle: VehicleProfile): DtcVehicleExplanation? {
        if (vehicle.modelYear !in 2010..2011) return null
        val normalized = code.trim().uppercase(java.util.Locale.ROOT)
        return when (vehicle.engine) {
            CamaroEngine.L99 -> l99[normalized]
            CamaroEngine.LS3 -> ls3[normalized]
            else -> null
        }
    }

    fun isKnownProfile(vehicle: VehicleProfile): Boolean =
        vehicle.engine in setOf(CamaroEngine.L99, CamaroEngine.LS3) && vehicle.modelYear in 2010..2011
}
