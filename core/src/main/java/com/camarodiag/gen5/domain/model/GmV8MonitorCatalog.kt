package com.camarodiag.gen5.domain.model

private fun v8ElectricalMonitor(
    code: String,
    camshaftEn: String,
    camshaftFr: String,
    modelYear: Int
): GmDtcMonitorEvidence {
    val maxVoltage = if (modelYear == 2010) "18" else "32"
    val samples = if (code == "P0010") "20 failures in 25 samples" else if (modelYear == 2010) "19 failures in 30 samples" else "19 failures in 30 samples"
    return GmDtcMonitorEvidence(
        code,
        "$camshaftEn camshaft actuator solenoid circuit – Bank 1",
        "Circuit de l'électrovanne d'actionnement d'$camshaftFr – banc 1",
        "The ECM monitors the Bank 1 VVT output circuit for electrical integrity.",
        "Le calculateur surveille l'intégrité électrique du circuit de sortie VVT du banc 1.",
        "The commanded driver state and the actual control-circuit state do not match.",
        "L'état commandé du driver ne correspond pas à l'état réel du circuit de commande.",
        "System voltage above 11 V and below $maxVoltage V; output driver commanded on; ignition in CRANK or RUN.",
        "Tension système supérieure à 11 V et inférieure à $maxVoltage V ; driver commandé actif ; contact en CRANK ou RUN.",
        "$samples; 250 ms per sample; continuous monitor; Type B, 2 trips.",
        "$samples ; 250 ms par échantillon ; surveillance continue ; Type B, 2 trajets.",
        "Type B – 2 trips", 1
    )
}

private fun v8PerformanceMonitor(
    code: String,
    camshaftEn: String,
    camshaftFr: String,
    modelYear: Int
): GmDtcMonitorEvidence {
    val samples = when (code) {
        "P0011" -> "200 failures in 1,000 samples"
        else -> if (modelYear == 2010) "135 failures in 150 samples" else "100 failures in 150 samples"
    }
    val stableWindow = if (camshaftEn == "Intake") "7.5" else "1.0"
    val maxVoltage = if (modelYear == 2010) "18" else "32"
    return GmDtcMonitorEvidence(
        code,
        "$camshaftEn camshaft system performance – Bank 1",
        "Performance du système de déphasage d'$camshaftFr – banc 1",
        "The ECM compares desired and actual camshaft positions when VVT is active.",
        "Le calculateur compare les positions d'arbre à cames demandée et réelle lorsque le VVT est actif.",
        "Absolute desired-to-actual camshaft position error exceeds the engine calibration's supporting-table threshold.",
        "L'écart absolu entre position d'arbre à cames demandée et réelle dépasse le seuil défini dans la table de calibration moteur GM.",
        "Engine running; VVT enabled; desired cam position above 0; PTO inactive; system voltage above 11 V and below $maxVoltage V; desired position stable within $stableWindow cam degrees for the calibration-defined period; related circuit/sensor DTCs absent.",
        "Moteur en marche ; VVT activé ; position demandée supérieure à 0 ; PTO inactif ; tension supérieure à 11 V et inférieure à $maxVoltage V ; position demandée stable dans une fenêtre de $stableWindow degré(s) de came pendant la durée définie par la calibration ; absence de défauts inhibiteurs de circuit/capteur.",
        "$samples; 100 ms per sample; Type B, 2 trips.",
        "$samples ; 100 ms par échantillon ; Type B, 2 trajets.",
        "Type B – 2 trips", 1
    )
}

private fun v8CorrelationMonitor(
    code: String,
    sensor: String,
    camshaftEn: String,
    camshaftFr: String,
    modelYear: Int
): GmDtcMonitorEvidence {
    val before = if (sensor == "A") "11" else "8"
    val after = if (sensor == "A") "11" else "9"
    return GmDtcMonitorEvidence(
        code,
        "Crankshaft / camshaft correlation – Bank 1 Sensor $sensor",
        "Corrélation vilebrequin / arbre à cames – banc 1, capteur $sensor",
        "The ECM detects cam-to-crank misalignment by checking whether the Bank 1 camshaft sensor pulse occurs at the expected crankshaft position.",
        "Le calculateur détecte un décalage de synchronisation vilebrequin/arbre à cames en vérifiant si l'impulsion du capteur de came du banc 1 survient à la position vilebrequin attendue.",
        "Four cam sensor pulses occur more than $before crank degrees before or $after crank degrees after nominal position in one cam revolution.",
        "Quatre impulsions du capteur d'arbre à cames surviennent à plus de $before degrés vilebrequin avant ou $after degrés après la position nominale sur une révolution de came.",
        "Crankshaft and camshaft signals synchronized; cam phaser parked where fitted; engine speed below 1,200 RPM; relevant crank/cam sensor and 5 V reference faults absent.",
        "Signaux vilebrequin et arbre à cames synchronisés ; déphaseur au repos lorsqu'il est présent ; régime inférieur à 1 200 tr/min ; absence de défauts inhibiteurs des capteurs et références 5 V.",
        "Starter-assisted: 4 failures in 5 samples; running without starter: 24 failures in 30 samples; one sample per cam revolution; Type B, 2 trips.",
        "Démarrage assisté : 4 échecs sur 5 échantillons ; moteur en marche sans démarreur : 24 échecs sur 30 échantillons ; un échantillon par révolution de came ; Type B, 2 trajets.",
        "Type B – 2 trips", 2
    )
}

object GmL99MonitorCatalog {
    const val SOURCE_2010 =
        "https://gsitlc.ext.gm.com/gmspo/mode6/pdf/2010/10OBDG12%20Engine%20Diagnostics.pdf"
    const val SOURCE_2011 =
        "https://gsitlc.ext.gm.com/gmspo/mode6/pdf/2011/11OBDG07%20Engine%20Diagnostics.pdf"

    private fun evidence(year: Int) = listOf(
        v8ElectricalMonitor("P0010", "Intake", "admission", year),
        v8PerformanceMonitor("P0011", "Intake", "admission", year),
        v8ElectricalMonitor("P0013", "Exhaust", "échappement", year),
        v8PerformanceMonitor("P0014", "Exhaust", "échappement", year),
        v8CorrelationMonitor("P0016", "A", "Intake", "admission", year),
        v8CorrelationMonitor("P0017", "B", "Exhaust", "échappement", year)
    ).associateBy { it.code }

    fun find(code: String, vehicle: VehicleProfile): GmDtcMonitorEvidence? {
        if (vehicle.engine != CamaroEngine.L99 || vehicle.modelYear !in 2010..2011) return null
        return evidence(vehicle.modelYear)[code.trim().uppercase(java.util.Locale.ROOT)]
    }

    fun supportedCodes(): Set<String> = evidence(2010).keys
}

object GmLs3MonitorCatalog {
    fun find(code: String, vehicle: VehicleProfile): GmDtcMonitorEvidence? {
        if (vehicle.engine != CamaroEngine.LS3 || vehicle.modelYear !in 2010..2011) return null
        return listOf(
            v8CorrelationMonitor("P0016", "A", "Intake", "admission", vehicle.modelYear),
            v8CorrelationMonitor("P0017", "B", "Exhaust", "échappement", vehicle.modelYear)
        ).associateBy { it.code }[code.trim().uppercase(java.util.Locale.ROOT)]
    }

    fun supportedCodes(): Set<String> = setOf("P0016", "P0017")
}

object GmV8MonitorCatalog {
    fun find(code: String, vehicle: VehicleProfile): GmDtcMonitorEvidence? =
        when (vehicle.engine) {
            CamaroEngine.L99 -> GmL99MonitorCatalog.find(code, vehicle)
            CamaroEngine.LS3 -> GmLs3MonitorCatalog.find(code, vehicle)
            else -> null
        }

    fun sourceLabel(vehicle: VehicleProfile): String? =
        if (vehicle.engine in setOf(CamaroEngine.L99, CamaroEngine.LS3) && vehicle.modelYear in 2010..2011) {
            if (vehicle.modelYear == 2010) "10OBDG12" else "11OBDG07"
        } else null
}
