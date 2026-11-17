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


private fun v8O2ControlDtc(bank: Int, sensor: Int) = DtcVehicleExplanation(
    "Le calculateur surveille le circuit électrique du chauffage de la sonde à oxygène du banc $bank, sonde $sensor. Une tension anormale peut provenir d'un circuit ouvert ou d'un court-circuit ; cela ne signifie pas que l'élément de mesure lambda est nécessairement défectueux.",
    "The ECM monitors the electrical heater circuit for Bank $bank Sensor $sensor. Abnormal voltage may come from an open circuit or a short; it does not necessarily mean the oxygen-measuring element has failed.",
    listOf("Faisceau ou connecteur coupé, oxydé ou en court-circuit", "Élément chauffant de la sonde défectueux", "Alimentation ou driver ECM en défaut"),
    listOf("Open, corroded or shorted wiring/connector", "Failed sensor heater element", "Power supply or ECM driver fault"),
    listOf("Contrôler connecteur et faisceau de la sonde", "Vérifier alimentation, masse et commande du chauffage", "Tester le chauffage avant remplacement de la sonde"),
    listOf("Inspect sensor connector and harness", "Check heater power, ground and driver command", "Test the heater before replacing the sensor"),
    "Modérée", "Moderate"
)

private fun v8O2ResistanceDtc(bank: Int, sensor: Int, minimum: String, maximum: String) = DtcVehicleExplanation(
    "Le calculateur détecte une résistance hors plage dans le chauffage de la sonde à oxygène du banc $bank, sonde $sensor. Les limites GM 2012 sont de $minimum à $maximum Ω pour cette position.",
    "The ECM detects heater resistance outside the expected range in Bank $bank Sensor $sensor. The 2012 GM limits for this sensor position are $minimum to $maximum Ω.",
    listOf("Élément chauffant interne ouvert ou dégradé", "Connecteur ou broches avec résistance de contact", "Faisceau endommagé"),
    listOf("Open or degraded internal heater element", "Connector or terminal contact resistance", "Damaged wiring"),
    listOf("Effectuer le contrôle à froid dans les conditions prescrites", "Mesurer la résistance et comparer à la plage GM propre à cette sonde", "Inspecter connecteur et faisceau avant remplacement"),
    listOf("Perform the check cold under the specified enable conditions", "Measure resistance and compare with the GM range for this sensor position", "Inspect connector and wiring before replacement"),
    "Modérée", "Moderate"
)

private fun v8MafDtc(supercharged: Boolean) = DtcVehicleExplanation(
    if (supercharged) "Sur le LSA suralimenté, le calculateur détecte une incohérence entre le débit MAF, les pressions MAP et le modèle d'air attendu pour le papillon et le compresseur." else "Sur les V8 atmosphériques L99/LS3, le calculateur détecte une incohérence entre le débit MAF, la pression MAP et le modèle d'air attendu.",
    if (supercharged) "On the supercharged LSA, the ECM detects an implausible relationship between MAF flow, MAP pressures and the expected throttle/supercharger airflow model." else "On naturally aspirated L99/LS3 V8 engines, the ECM detects an implausible relationship between MAF flow, MAP pressure and the expected airflow model.",
    listOf("Débitmètre contaminé ou signal incorrect", "Prise d'air ou fuite sur le circuit d'admission", "Capteur MAP ou papillon incohérent", "Faisceau, alimentation ou masse de capteur défectueux"),
    listOf("Contaminated MAF sensor or implausible signal", "Intake air leak", "Implausible MAP or throttle signal", "Faulty sensor wiring, power or ground"),
    listOf("Inspecter filtre, admission et conduits après débitmètre/compresseur", "Comparer MAF, MAP et position papillon en données réelles", "Contrôler alimentation, masse et connecteurs avant remplacement du MAF"),
    listOf("Inspect air filter, intake and ducts after the MAF/supercharger", "Compare live MAF, MAP and throttle-position data", "Check power, ground and connectors before replacing the MAF"),
    "Modérée à élevée", "Moderate to high"
)

object CamaroV8DtcCatalog {
    private val l99 = mapOf(
        "P0010" to v8CircuitDtc("intake", "admission"),
        "P0011" to v8PerformanceDtc("intake", "admission"),
        "P0013" to v8CircuitDtc("exhaust", "échappement"),
        "P0014" to v8PerformanceDtc("exhaust", "échappement"),
        "P0016" to v8CorrelationDtc("A", "intake", "admission")
    )
    private val ls3 = mapOf(
        "P0016" to v8CorrelationDtc("A", "intake", "admission")
    )
    private val common2012O2 = mapOf(
        "P0030" to v8O2ControlDtc(1, 1),
        "P0036" to v8O2ControlDtc(1, 2),
        "P0050" to v8O2ControlDtc(2, 1),
        "P0056" to v8O2ControlDtc(2, 2),
        "P0053" to v8O2ResistanceDtc(1, 1, "2.8", "9.5"),
        "P0054" to v8O2ResistanceDtc(1, 2, "4.1", "10.8"),
        "P0059" to v8O2ResistanceDtc(2, 1, "2.8", "9.5"),
        "P0060" to v8O2ResistanceDtc(2, 2, "4.1", "10.8")
    )
    private val l99_2012 = l99 + common2012O2 + ("P0101" to v8MafDtc(false))
    private val ls3_2012 = ls3 + common2012O2 + ("P0101" to v8MafDtc(false))
    private val lsa_2012 = ls3 + common2012O2 + ("P0101" to v8MafDtc(true))

    fun find(code: String, vehicle: VehicleProfile): DtcVehicleExplanation? {
        val normalized = code.trim().uppercase(java.util.Locale.ROOT)
        return when (vehicle.modelYear) {
            2010, 2011 -> when (vehicle.engine) {
                CamaroEngine.L99 -> l99[normalized]
                CamaroEngine.LS3 -> ls3[normalized]
                else -> null
            }
            2012 -> when (vehicle.engine) {
                CamaroEngine.L99 -> l99_2012[normalized]
                CamaroEngine.LS3 -> ls3_2012[normalized]
                CamaroEngine.LSA -> lsa_2012[normalized]
                else -> null
            }
            else -> null
        }
    }

    fun isKnownProfile(vehicle: VehicleProfile): Boolean =
        vehicle.engine in setOf(CamaroEngine.L99, CamaroEngine.LS3, CamaroEngine.LSA) &&
            vehicle.modelYear in 2010..2012 &&
            vehicle.modelYear in vehicle.engine.years
}
