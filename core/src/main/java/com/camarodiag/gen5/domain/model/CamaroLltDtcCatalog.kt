package com.camarodiag.gen5.domain.model

private fun lltElectricalExplanation(
    bank: Int,
    camshaftEn: String,
    camshaftFr: String
) = DtcVehicleExplanation(
    "Le calculateur moteur détecte une interruption ou une anomalie électrique dans le circuit de commande de l'actionneur de déphasage d'admission/échappement du banc $bank. Le code cible le circuit de sortie, pas automatiquement l'électrovanne.",
    "The ECM detects an open or electrical fault in the Bank $bank $camshaftEn camshaft actuator control circuit. The code identifies the output circuit, not automatically the solenoid.",
    listOf("Faisceau coupé ou en court-circuit", "Connecteur ou broche oxydé(e), desserré(e) ou reculée", "Électrovanne d'actionnement défectueuse", "Alimentation, masse ou étage de sortie ECM en défaut"),
    listOf("Open or shorted harness", "Corroded, loose or backed-out connector terminal", "Faulty actuator solenoid", "Power, ground or ECM output-stage fault"),
    listOf("Inspecter le faisceau et le connecteur de l'actionneur du banc $bank", "Contrôler alimentation, masse et continuité selon le schéma LLT", "Vérifier la commande électrique avant de remplacer l'électrovanne"),
    listOf("Inspect Bank $bank actuator wiring and connector", "Check power, ground and continuity against the LLT wiring diagram", "Verify electrical command before replacing the solenoid"),
    "Modérée à élevée — défaut de commande du déphasage",
    "Moderate to high — cam phasing control circuit fault"
)

private fun lltPerformanceExplanation(
    bank: Int,
    camshaftEn: String,
    camshaftFr: String
) = DtcVehicleExplanation(
    "Le calculateur constate que l'arbre à cames d'$camshaftFr du banc $bank ne suit pas correctement la consigne de déphasage. Le contrôle compare l'angle réel à la cible et surveille la réponse du système.",
    "The ECM detects that Bank $bank $camshaftEn camshaft timing is not following the commanded target. The monitor compares actual angle with the target and evaluates system response.",
    listOf("Niveau, viscosité ou état d'huile inadapté", "Pression ou débit d'huile insuffisant vers le système VVT", "Électrovanne de déphasage encrassée ou bloquée", "Déphaseur, chaîne ou tendeur de distribution usé/décalé"),
    listOf("Incorrect oil level, viscosity or condition", "Insufficient oil pressure or flow to the VVT system", "Sticking or restricted camshaft actuator solenoid", "Worn or misaligned phaser, timing chain or tensioner"),
    listOf("Vérifier niveau, spécification et état de l'huile", "Comparer angle demandé et angle réel avec l'outil de diagnostic", "Contrôler circuit et électrovanne avant d'ouvrir la distribution", "Vérifier chaîne et tendeur si l'écart persiste"),
    listOf("Verify oil level, specification and condition", "Compare commanded and actual cam angle with a scan tool", "Check the circuit and solenoid before opening the timing system", "Inspect chain and tensioner if the deviation remains"),
    "Élevée — contrôler rapidement en cas de bruit ou fonctionnement irrégulier",
    "High — inspect promptly if noise or rough running is present"
)

private fun lltSlowResponseExplanation(
    bank: Int,
    camshaftEn: String,
    camshaftFr: String
) = DtcVehicleExplanation(
    "Le calculateur détecte une réponse trop lente de l'arbre à cames d'$camshaftFr du banc $bank par rapport à la consigne. Le défaut concerne la vitesse de réaction du déphasage, pas uniquement sa position finale.",
    "The ECM detects that Bank $bank $camshaftEn camshaft response is too slow relative to the commanded target. The fault concerns phaser response time, not only its final position.",
    listOf("Huile moteur trop visqueuse, dégradée ou niveau insuffisant", "Passage d'huile ou filtre d'électrovanne obstrué", "Électrovanne VVT lente ou grippée", "Déphaseur ou tendeur de chaîne usé"),
    listOf("Oil too viscous, degraded or low", "Restricted oil passage or actuator filter", "Slow or sticking VVT solenoid", "Worn cam phaser or timing-chain tensioner"),
    listOf("Contrôler huile et pression moteur", "Observer temps de réponse entre angle demandé et réel", "Tester l'électrovanne et vérifier les passages d'huile", "Contrôler le déphaseur et la distribution si la réponse reste lente"),
    listOf("Check engine oil and pressure", "Observe response time between commanded and actual cam angle", "Test the solenoid and inspect oil passages", "Inspect the phaser and timing system if response remains slow"),
    "Élevée — un défaut persistant peut perturber le calage moteur",
    "High — a persistent fault can disturb engine timing"
)

private fun lltCorrelationExplanation(
    bank: Int,
    sensor: String,
    camshaftEn: String,
    camshaftFr: String
) = DtcVehicleExplanation(
    "Le calculateur détecte une incohérence de synchronisation entre le vilebrequin et l'arbre à cames du banc $bank, capteur $sensor ($camshaftFr). Le défaut peut provenir du signal capteur ou d'un décalage mécanique de distribution.",
    "The ECM detects a crankshaft-to-camshaft synchronization mismatch on Bank $bank, Sensor $sensor ($camshaftEn). The fault may come from the sensor signal or mechanical timing alignment.",
    listOf("Chaîne de distribution détendue ou tendeur défaillant", "Déphaseur ou pignon intermédiaire décalé", "Capteur vilebrequin/arbre à cames ou connecteur intermittent", "Reluctor de vilebrequin déplacé ou signal électrique perturbé"),
    listOf("Stretched timing chain or failed tensioner", "Misaligned cam phaser or intermediate sprocket", "Intermittent crankshaft/camshaft sensor or connector", "Shifted crankshaft reluctor or disturbed electrical signal"),
    listOf("Vérifier les défauts associés des capteurs vilebrequin et arbres à cames", "Comparer les signaux et angles de synchronisation", "Contrôler chaîne, tendeur, pignons et déphaseurs selon la procédure LLT", "Inspecter le reluctor si les contrôles précédents sont corrects"),
    listOf("Check related crankshaft and camshaft sensor DTCs", "Compare synchronization signals and learned angles", "Inspect chain, tensioner, sprockets and phasers using the LLT procedure", "Inspect the reluctor if previous checks are correct"),
    "Élevée — risque de mauvais calage moteur",
    "High — engine timing may be incorrect"
)

private fun lltIdlerExplanation(bank: Int) = DtcVehicleExplanation(
    "Le calculateur détecte un défaut de performance d'alignement du système de position moteur côté pignon intermédiaire, banc $bank. Les angles appris des arbres à cames sortent de la fenêtre attendue.",
    "The ECM detects an engine-position alignment performance fault at the Bank $bank intermediate sprocket. Learned camshaft angles are outside the expected window.",
    listOf("Chaîne de distribution détendue ou allongée", "Tendeur ou guide de chaîne défectueux", "Pignon intermédiaire ou reluctor décalé", "Capteur vilebrequin/arbre à cames ou adaptation incorrecte"),
    listOf("Loose or stretched timing chain", "Faulty chain tensioner or guide", "Misaligned intermediate sprocket or reluctor", "Crankshaft/camshaft sensor or adaptation fault"),
    listOf("Contrôler les angles d'adaptation des deux arbres à cames", "Vérifier les DTC de capteurs et circuits de commande", "Inspecter chaîne, guides, tendeur et pignon intermédiaire selon SI"),
    listOf("Check learned adaptation angles for both camshafts", "Check sensor and actuator-circuit DTCs", "Inspect chain, guides, tensioner and intermediate sprocket using SI"),
    "Élevée — contrôle mécanique recommandé",
    "High — mechanical inspection recommended"
)

object CamaroLltDtcCatalog {
    private val explanations = mapOf(
        "P0008" to lltIdlerExplanation(1),
        "P0009" to lltIdlerExplanation(2),
        "P000A" to lltSlowResponseExplanation(1, "intake", "admission"),
        "P000B" to lltSlowResponseExplanation(1, "exhaust", "échappement"),
        "P000C" to lltSlowResponseExplanation(2, "intake", "admission"),
        "P000D" to lltSlowResponseExplanation(2, "exhaust", "échappement"),
        "P0010" to lltElectricalExplanation(1, "intake", "admission"),
        "P0011" to lltPerformanceExplanation(1, "intake", "admission"),
        "P0013" to lltElectricalExplanation(1, "exhaust", "échappement"),
        "P0014" to lltPerformanceExplanation(1, "exhaust", "échappement"),
        "P0016" to lltCorrelationExplanation(1, "A", "intake", "admission"),
        "P0017" to lltCorrelationExplanation(1, "B", "exhaust", "échappement"),
        "P0018" to lltCorrelationExplanation(2, "A", "intake", "admission"),
        "P0019" to lltCorrelationExplanation(2, "B", "exhaust", "échappement"),
        "P0020" to lltElectricalExplanation(2, "intake", "admission"),
        "P0021" to lltPerformanceExplanation(2, "intake", "admission"),
        "P0023" to lltElectricalExplanation(2, "exhaust", "échappement"),
        "P0024" to lltPerformanceExplanation(2, "exhaust", "échappement")
    )

    fun find(code: String, vehicle: VehicleProfile): DtcVehicleExplanation? {
        if (vehicle.engine != CamaroEngine.LLT || vehicle.modelYear !in 2010..2011) return null
        return explanations[code.trim().uppercase(java.util.Locale.ROOT)]
    }

    fun supportedCodes(): Set<String> = explanations.keys
}
