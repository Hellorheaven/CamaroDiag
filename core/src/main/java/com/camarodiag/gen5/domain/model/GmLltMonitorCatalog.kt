package com.camarodiag.gen5.domain.model

private fun lltElectricalMonitor(
    code: String,
    bank: Int,
    camshaftEn: String,
    camshaftFr: String,
    modelYear: Int
): GmDtcMonitorEvidence {
    val batteryMaximum = if (modelYear == 2010) "18.1" else "25.5"
    val checkDuration = if (modelYear == 2010) "0.01 s" else "1.5 s"
    return GmDtcMonitorEvidence(
        code,
        "$camshaftEn camshaft actuator circuit – Bank $bank",
        "Circuit de commande de l'actionneur d'arbre à cames d'$camshaftFr – banc $bank",
        "The ECM performs an electrical continuity check of the camshaft actuator output circuit.",
        "Le calculateur effectue un contrôle de continuité électrique du circuit de sortie de l'actionneur d'arbre à cames.",
        "The output-stage continuity check detects an open circuit during the activation/deactivation test.",
        "Le contrôle de continuité de l'étage de sortie détecte un circuit ouvert pendant le test d'activation/désactivation.",
        "Engine speed above 80 RPM; battery voltage above 10 V and below $batteryMaximum V; output activated and deactivated for the complete check.",
        "Régime moteur supérieur à 80 tr/min ; tension batterie supérieure à 10 V et inférieure à $batteryMaximum V ; sortie activée puis désactivée pour le contrôle complet.",
        "$checkDuration electrical check; MIL strategy after two driving cycles.",
        "Contrôle électrique de $checkDuration ; stratégie MIL après deux cycles de conduite.",
        "2 driving cycles", 1
    )
}

private fun lltPerformanceMonitor(
    code: String,
    bank: Int,
    camshaftEn: String,
    camshaftFr: String,
    modelYear: Int
): GmDtcMonitorEvidence {
    val startError = if (modelYear == 2010) "7.0–8.0" else "6.0–11.0"
    val rpm = if (modelYear == 2010) "520" else "480"
    val responseLimit = if (modelYear == 2010) "1.5" else "2.5"
    val responseTime = if (modelYear == 2010) "2.0" else "2.5"
    return GmDtcMonitorEvidence(
        code,
        "$camshaftEn camshaft control rationality – Bank $bank",
        "Contrôle de cohérence du déphasage d'$camshaftFr – banc $bank",
        "The ECM compares actual camshaft angle with the desired target to detect a cam-control response fault.",
        "Le calculateur compare l'angle réel de l'arbre à cames à la consigne pour détecter un défaut de réponse du déphasage.",
        "The target-error difference at test start exceeds $startError cam degrees; response monitoring uses a $responseLimit-degree discriminator and checks whether actual angle reaches the target within $responseTime seconds.",
        "L'écart d'erreur de cible au démarrage du test dépasse $startError degrés de came ; la surveillance utilise un discriminateur de $responseLimit degré et vérifie si l'angle réel atteint la consigne en moins de $responseTime secondes.",
        "Engine speed above $rpm RPM; engine run time above 1 s; camshaft control circuit test complete; camshaft adaptation complete; desired camshaft position remains above the start value.",
        "Régime moteur supérieur à $rpm tr/min ; temps de fonctionnement moteur supérieur à 1 s ; test du circuit de commande terminé ; adaptation des arbres à cames terminée ; position demandée supérieure à la valeur de départ.",
        "Error counter at least 10 counts, or at least 4 counts on cold start; monitor duration approximately 600 s / two driving cycles.",
        "Compteur d'erreurs d'au moins 10 unités, ou 4 au démarrage à froid ; durée de surveillance d'environ 600 s / deux cycles de conduite.",
        "2 driving cycles", 1
    )
}

private fun lltSlowResponseMonitor(
    code: String,
    bank: Int,
    camshaftEn: String,
    camshaftFr: String,
    modelYear: Int
): GmDtcMonitorEvidence {
    val startError = if (modelYear == 2010) "7.0–8.0" else "6.0–11.0"
    val rpm = if (modelYear == 2010) "520" else "480"
    val lag = if (modelYear == 2010) "1.5" else "2.5"
    val responseTime = if (modelYear == 2010) "2.0" else "2.5"
    return GmDtcMonitorEvidence(
        code,
        "Camshaft slow response – Bank $bank $camshaftEn",
        "Réponse lente de l'arbre à cames – banc $bank, admission/échappement",
        "The ECM monitors whether the camshaft reaches its commanded target within the calibrated response time.",
        "Le calculateur surveille si l'arbre à cames atteint la consigne dans le délai de réponse calibré.",
        "The slow-response discriminator exceeds $lag cam degrees after the start-test error range of $startError degrees; actual angle remains below target beyond $responseTime seconds.",
        "Le discriminateur de réponse lente dépasse $lag degrés de came après la plage d'erreur initiale de $startError degrés ; l'angle réel reste sous la consigne au-delà de $responseTime secondes.",
        "Engine speed above $rpm RPM; engine run time above 1 s; camshaft control circuit test complete; camshaft adaptation complete; desired camshaft position remains above the start value.",
        "Régime moteur supérieur à $rpm tr/min ; temps de fonctionnement moteur supérieur à 1 s ; test du circuit de commande terminé ; adaptation des arbres à cames terminée ; position demandée supérieure à la valeur de départ.",
        "Error counter at least 10 counts, or at least 4 counts on cold start; monitor duration approximately 600 s / two driving cycles.",
        "Compteur d'erreurs d'au moins 10 unités, ou 4 au démarrage à froid ; durée de surveillance d'environ 600 s / deux cycles de conduite.",
        "2 driving cycles", 1
    )
}

private fun lltCamCrankMonitor(
    code: String,
    bank: Int,
    sensor: String,
    modelYear: Int,
    page: Int
): GmDtcMonitorEvidence {
    val adaptedAngle = if (modelYear == 2010) "9.9" else "11.7"
    return GmDtcMonitorEvidence(
        code,
        "Camshaft / crankshaft alignment – Bank $bank Sensor $sensor",
        "Alignement arbre à cames / vilebrequin – banc $bank, capteur $sensor",
        "The ECM compares the learned cam-to-crank adapted angle with the permitted alignment window.",
        "Le calculateur compare l'angle d'adaptation appris entre arbre à cames et vilebrequin à la fenêtre d'alignement autorisée.",
        "The adapted angle exceeds the calibrated ±$adaptedAngle-degree limit; the parked-cam actual angle must also remain within the documented 15–21-degree window.",
        "L'angle d'adaptation dépasse la limite calibrée de ±$adaptedAngle degrés ; l'angle réel avec déphaseur au repos doit également rester dans la fenêtre documentée de 15 à 21 degrés.",
        "Engine run time above 50 s; coolant temperature above 0 °C and below 95.25 °C; camshaft adaptation complete.",
        "Temps de fonctionnement moteur supérieur à 50 s ; température du liquide supérieure à 0 °C et inférieure à 95,25 °C ; adaptation des arbres à cames terminée.",
        "Monitor duration approximately 600 s; MIL strategy after about two driving cycles.",
        "Durée de surveillance d'environ 600 s ; stratégie MIL après environ deux cycles de conduite.",
        "2 driving cycles", page
    )
}

private fun lltIdlerSprocketMonitor(
    code: String,
    bank: Int,
    modelYear: Int
): GmDtcMonitorEvidence {
    val adaptedAngle = if (modelYear == 2010) "6.0" else "7.3"
    return GmDtcMonitorEvidence(
        code,
        "Bank $bank idler-sprocket cam/crank alignment",
        "Alignement arbre à cames / vilebrequin du pignon intermédiaire – banc $bank",
        "The ECM checks the learned alignment of both camshafts relative to the intermediate sprocket.",
        "Le calculateur contrôle l'alignement appris des deux arbres à cames par rapport au pignon intermédiaire.",
        "The adapted angle for both camshafts is outside the calibrated ±$adaptedAngle-degree limit.",
        "L'angle d'adaptation des deux arbres à cames sort de la limite calibrée de ±$adaptedAngle degrés.",
        "Camshaft adaptation complete; camshaft position sensor and actuator circuit diagnostics must be valid.",
        "Adaptation des arbres à cames terminée ; diagnostics des capteurs de position et circuits d'actionneurs valides.",
        "Failure is confirmed after two adaptation cycles.",
        "La défaillance est confirmée après deux cycles d'adaptation.",
        "2 adaptation cycles", 2
    )
}

object Gm2010LltMonitorCatalog {
    const val SOURCE_DOCUMENT =
        "https://gsitlc.ext.gm.com/gmspo/mode6/pdf/2010/10OBDG02%20Engine%20Diagnostics.pdf"
    const val GROUP_SUMMARY =
        "https://gsitlc.ext.gm.com/gmspo/mode6/pdf/2010/2010%20OBD%20Grouping%20Summary.pdf"

    private val evidence = listOf(
        lltSlowResponseMonitor("P000A", 1, "Intake", "admission", 2010),
        lltSlowResponseMonitor("P000B", 1, "Exhaust", "échappement", 2010),
        lltSlowResponseMonitor("P000C", 2, "Intake", "admission", 2010),
        lltSlowResponseMonitor("P000D", 2, "Exhaust", "échappement", 2010),
        lltIdlerSprocketMonitor("P0008", 1, 2010),
        lltIdlerSprocketMonitor("P0009", 2, 2010),
        lltElectricalMonitor("P0010", 1, "Intake", "admission", 2010),
        lltPerformanceMonitor("P0011", 1, "Intake", "admission", 2010),
        lltElectricalMonitor("P0013", 1, "Exhaust", "échappement", 2010),
        lltPerformanceMonitor("P0014", 1, "Exhaust", "échappement", 2010),
        lltCamCrankMonitor("P0016", 1, "A", 2010, 2),
        lltCamCrankMonitor("P0017", 1, "B", 2010, 2),
        lltCamCrankMonitor("P0018", 2, "A", 2010, 2),
        lltCamCrankMonitor("P0019", 2, "B", 2010, 2),
        lltElectricalMonitor("P0020", 2, "Intake", "admission", 2010),
        lltPerformanceMonitor("P0021", 2, "Intake", "admission", 2010),
        lltElectricalMonitor("P0023", 2, "Exhaust", "échappement", 2010),
        lltPerformanceMonitor("P0024", 2, "Exhaust", "échappement", 2010)
    ).associateBy { it.code }

    fun find(code: String, vehicle: VehicleProfile): GmDtcMonitorEvidence? {
        if (vehicle.engine != CamaroEngine.LLT || vehicle.modelYear != 2010) return null
        return evidence[code.trim().uppercase(java.util.Locale.ROOT)]
    }

    fun supportedCodes(): Set<String> = evidence.keys
}

object Gm2011LltMonitorCatalog {
    const val SOURCE_DOCUMENT =
        "https://gsitlc.ext.gm.com/gmspo/mode6/pdf/2011/11OBDG06%20Engine%20Diagnostics.pdf"
    const val GROUP_SUMMARY =
        "https://gsitlc.ext.gm.com/gmspo/mode6/pdf/2011/2011%20OBD%20GROUP%20SUMMARY.pdf"

    private val evidence = listOf(
        lltSlowResponseMonitor("P000A", 1, "Intake", "admission", 2011),
        lltSlowResponseMonitor("P000B", 1, "Exhaust", "échappement", 2011),
        lltSlowResponseMonitor("P000C", 2, "Intake", "admission", 2011),
        lltSlowResponseMonitor("P000D", 2, "Exhaust", "échappement", 2011),
        lltIdlerSprocketMonitor("P0008", 1, 2011),
        lltIdlerSprocketMonitor("P0009", 2, 2011),
        lltElectricalMonitor("P0010", 1, "Intake", "admission", 2011),
        lltPerformanceMonitor("P0011", 1, "Intake", "admission", 2011),
        lltElectricalMonitor("P0013", 1, "Exhaust", "échappement", 2011),
        lltPerformanceMonitor("P0014", 1, "Exhaust", "échappement", 2011),
        lltCamCrankMonitor("P0016", 1, "A", 2011, 2),
        lltCamCrankMonitor("P0017", 1, "B", 2011, 2),
        lltCamCrankMonitor("P0018", 2, "A", 2011, 2),
        lltCamCrankMonitor("P0019", 2, "B", 2011, 2),
        lltElectricalMonitor("P0020", 2, "Intake", "admission", 2011),
        lltPerformanceMonitor("P0021", 2, "Intake", "admission", 2011),
        lltElectricalMonitor("P0023", 2, "Exhaust", "échappement", 2011),
        lltPerformanceMonitor("P0024", 2, "Exhaust", "échappement", 2011)
    ).associateBy { it.code }

    fun find(code: String, vehicle: VehicleProfile): GmDtcMonitorEvidence? {
        if (vehicle.engine != CamaroEngine.LLT || vehicle.modelYear != 2011) return null
        return evidence[code.trim().uppercase(java.util.Locale.ROOT)]
    }

    fun supportedCodes(): Set<String> = evidence.keys
}

object GmLltMonitorCatalog {
    fun find(code: String, vehicle: VehicleProfile): GmDtcMonitorEvidence? =
        when (vehicle.modelYear) {
            2010 -> Gm2010LltMonitorCatalog.find(code, vehicle)
            2011 -> Gm2011LltMonitorCatalog.find(code, vehicle)
            else -> null
        }

    fun sourceLabel(vehicle: VehicleProfile): String? =
        when {
            vehicle.engine == CamaroEngine.LLT && vehicle.modelYear == 2010 -> "10OBDG02"
            vehicle.engine == CamaroEngine.LLT && vehicle.modelYear == 2011 -> "11OBDG06"
            else -> null
        }
}

object GmEngineMonitorCatalog {
    fun find(code: String, vehicle: VehicleProfile): GmDtcMonitorEvidence? =
        when (vehicle.engine) {
            CamaroEngine.LLT -> GmLltMonitorCatalog.find(code, vehicle)
            CamaroEngine.LFX -> GmLfxMonitorCatalog.find(code, vehicle)
            CamaroEngine.L99, CamaroEngine.LS3, CamaroEngine.LSA -> GmV8MonitorCatalog.find(code, vehicle)
            else -> null
        }

    fun sourceLabel(vehicle: VehicleProfile): String? =
        when (vehicle.engine) {
            CamaroEngine.LLT -> GmLltMonitorCatalog.sourceLabel(vehicle)
            CamaroEngine.LFX -> GmLfxMonitorCatalog.sourceLabel(vehicle)
            CamaroEngine.L99, CamaroEngine.LS3 -> GmV8MonitorCatalog.sourceLabel(vehicle)
            else -> null
        }
}
