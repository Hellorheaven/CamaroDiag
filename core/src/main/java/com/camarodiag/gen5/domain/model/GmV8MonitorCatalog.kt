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
        ).associateBy { it.code }[code.trim().uppercase(java.util.Locale.ROOT)]
    }

    fun supportedCodes(): Set<String> = setOf("P0016")
}


private fun v8O2CircuitMonitor(code: String, bank: Int, sensor: Int, page: Int) =
    GmDtcMonitorEvidence(
        code,
        "Oxygen sensor heater control circuit – Bank $bank Sensor $sensor",
        "Circuit de commande du chauffage de sonde à oxygène – banc $bank, sonde $sensor",
        "The ECM checks the oxygen-sensor heater output-driver circuit for electrical integrity.",
        "Le calculateur contrôle l'intégrité électrique du circuit driver du chauffage de la sonde à oxygène.",
        "Voltage is low while the driver is open (short to ground or open circuit), or high while the driver is closed (short to voltage).",
        "La tension est basse lorsque le driver est ouvert (court-circuit à la masse ou circuit ouvert), ou élevée lorsque le driver est fermé (court-circuit au positif).",
        "Ignition in CRANK or RUN; ignition voltage 11–32 V; engine speed above 400 RPM.",
        "Contact en CRANK ou RUN ; tension d'allumage de 11 à 32 V ; régime moteur supérieur à 400 tr/min.",
        "20 failures in 25 samples; 250 ms per sample; continuous monitor; Type B, 2 trips.",
        "20 échecs sur 25 échantillons ; 250 ms par échantillon ; surveillance continue ; Type B, 2 trajets.",
        "Type B – 2 trips", page
    )

private fun v8O2ResistanceMonitor(
    code: String,
    bank: Int,
    sensor: Int,
    minimumOhms: String,
    maximumOhms: String,
    page: Int
) = GmDtcMonitorEvidence(
    code,
    "Oxygen sensor heater resistance – Bank $bank Sensor $sensor",
    "Résistance du chauffage de sonde à oxygène – banc $bank, sonde $sensor",
    "The ECM evaluates learned heater resistance during a valid cold start.",
    "Le calculateur évalue la résistance apprise du chauffage pendant un démarrage à froid valide.",
    "Calculated heater resistance is below $minimumOhms Ω or above $maximumOhms Ω.",
    "La résistance calculée du chauffage est inférieure à $minimumOhms Ω ou supérieure à $maximumOhms Ω.",
    "No active ECT/IAT sensor faults; coolant-to-IAT difference below 8 °C; coolant temperature −30 to 45 °C; ignition voltage below 32 V; engine soak above 28,800 s; engine run time below 3 s.",
    "Aucun défaut actif des capteurs ECT/IAT ; écart liquide-air inférieur à 8 °C ; liquide de −30 à 45 °C ; tension d'allumage inférieure à 32 V ; immobilisation supérieure à 28 800 s ; temps moteur inférieur à 3 s.",
    "Once per valid cold start; Type B, 2 trips.",
    "Une fois par démarrage à froid valide ; Type B, 2 trajets.",
    "Type B – 2 trips", page
)

private fun v8MafMonitor(supercharged: Boolean): GmDtcMonitorEvidence {
    val page = if (supercharged) 11 else 10
    val rpmMaximum = if (supercharged) "6,200" else "6,700"
    val criteriaEn = if (supercharged) {
        "MAF residual above 21 g/s, MAP1/MAP2 residual above 22.0 kPa, throttle-model residual above 400 kPa*(g/s), and supporting SCIAP residual above 14.0 kPa."
    } else {
        "MAF residual above 12 g/s, MAP2 residual above 15.0 kPa, throttle-model error threshold 250 kPa*(g/s), and minimum combined weighting factor at least 0.00."
    }
    val criteriaFr = if (supercharged) {
        "Résidu MAF supérieur à 21 g/s, résidu MAP1/MAP2 supérieur à 22,0 kPa, résidu du modèle papillon supérieur à 400 kPa*(g/s), et résidu SCIAP de support supérieur à 14,0 kPa."
    } else {
        "Résidu MAF supérieur à 12 g/s, résidu MAP2 supérieur à 15,0 kPa, seuil d'erreur du modèle papillon de 250 kPa*(g/s), et facteur de pondération combiné minimal d'au moins 0,00."
    }
    return GmDtcMonitorEvidence(
        "P0101",
        if (supercharged) "Mass airflow system performance (supercharged)" else "Mass airflow system performance (naturally aspirated)",
        if (supercharged) "Performance du système de débit d'air (moteur suralimenté)" else "Performance du système de débit d'air (moteur atmosphérique)",
        "The ECM checks whether measured MAF and MAP values are plausible against the throttle-based airflow model.",
        "Le calculateur vérifie la cohérence des valeurs MAF et MAP mesurées par rapport au modèle de débit d'air basé sur le papillon.",
        criteriaEn,
        criteriaFr,
        "Engine speed 450–$rpmMaximum RPM; coolant temperature above −7 °C and below 125 °C; intake-air temperature above −20 °C and below 125 °C; relevant MAF/MAP/throttle faults must not be active.",
        "Régime moteur de 450 à $rpmMaximum tr/min ; liquide entre −7 et 125 °C ; air d'admission entre −20 et 125 °C ; aucun défaut inhibiteur MAF/MAP/papillon actif.",
        "Continuous monitor; calculations every 12.5 ms; Type B, 2 trips.",
        "Surveillance continue ; calculs toutes les 12,5 ms ; Type B, 2 trajets.",
        "Type B – 2 trips", page
    )
}

private fun v8O2Evidence(): List<GmDtcMonitorEvidence> = listOf(
    v8O2CircuitMonitor("P0030", 1, 1, 3),
    v8O2CircuitMonitor("P0036", 1, 2, 4),
    v8O2CircuitMonitor("P0050", 2, 1, 3),
    v8O2CircuitMonitor("P0056", 2, 2, 4),
    v8O2ResistanceMonitor("P0053", 1, 1, "2.8", "9.5", 3),
    v8O2ResistanceMonitor("P0054", 1, 2, "4.1", "10.8", 3),
    v8O2ResistanceMonitor("P0059", 2, 1, "2.8", "9.5", 4),
    v8O2ResistanceMonitor("P0060", 2, 2, "4.1", "10.8", 4)
)

private fun v8L99VvtEvidence(): List<GmDtcMonitorEvidence> = listOf(
    v8ElectricalMonitor("P0010", "Intake", "admission", 2012),
    v8PerformanceMonitor("P0011", "Intake", "admission", 2012),
    v8ElectricalMonitor("P0013", "Exhaust", "échappement", 2012),
    v8PerformanceMonitor("P0014", "Exhaust", "échappement", 2012)
)

object Gm2012L99MonitorCatalog {
    private val evidence = (v8L99VvtEvidence() +
        v8CorrelationMonitor("P0016", "A", "Intake", "admission", 2012) +
        v8O2Evidence() + v8MafMonitor(false)).associateBy { it.code }

    fun find(code: String, vehicle: VehicleProfile): GmDtcMonitorEvidence? {
        if (vehicle.engine != CamaroEngine.L99 || vehicle.modelYear != 2012) return null
        return evidence[code.trim().uppercase(java.util.Locale.ROOT)]
    }

    fun supportedCodes(): Set<String> = evidence.keys
}

object Gm2012Ls3MonitorCatalog {
    private val evidence = (listOf(v8CorrelationMonitor("P0016", "A", "Intake", "admission", 2012)) +
        v8O2Evidence() + v8MafMonitor(false)).associateBy { it.code }

    fun find(code: String, vehicle: VehicleProfile): GmDtcMonitorEvidence? {
        if (vehicle.engine != CamaroEngine.LS3 || vehicle.modelYear != 2012) return null
        return evidence[code.trim().uppercase(java.util.Locale.ROOT)]
    }

    fun supportedCodes(): Set<String> = evidence.keys
}

object Gm2012LsaMonitorCatalog {
    private val evidence = (listOf(v8CorrelationMonitor("P0016", "A", "Intake", "admission", 2012)) +
        v8O2Evidence() + v8MafMonitor(true)).associateBy { it.code }

    fun find(code: String, vehicle: VehicleProfile): GmDtcMonitorEvidence? {
        if (vehicle.engine != CamaroEngine.LSA || vehicle.modelYear != 2012) return null
        return evidence[code.trim().uppercase(java.util.Locale.ROOT)]
    }

    fun supportedCodes(): Set<String> = evidence.keys
}

object GmV8MonitorCatalog {
    fun find(code: String, vehicle: VehicleProfile): GmDtcMonitorEvidence? =
        when {
            vehicle.modelYear == 2012 -> when (vehicle.engine) {
                CamaroEngine.L99 -> Gm2012L99MonitorCatalog.find(code, vehicle)
                CamaroEngine.LS3 -> Gm2012Ls3MonitorCatalog.find(code, vehicle)
                CamaroEngine.LSA -> Gm2012LsaMonitorCatalog.find(code, vehicle)
                else -> null
            }
            else -> when (vehicle.engine) {
                CamaroEngine.L99 -> GmL99MonitorCatalog.find(code, vehicle)
                CamaroEngine.LS3 -> GmLs3MonitorCatalog.find(code, vehicle)
                else -> null
            }
        }

    fun sourceLabel(vehicle: VehicleProfile): String? =
        when {
            vehicle.modelYear == 2012 && vehicle.engine in setOf(CamaroEngine.L99, CamaroEngine.LS3, CamaroEngine.LSA) -> "12OBDG07"
            vehicle.modelYear in 2010..2011 && vehicle.engine in setOf(CamaroEngine.L99, CamaroEngine.LS3) ->
                if (vehicle.modelYear == 2010) "10OBDG12" else "11OBDG07"
            else -> null
        }
}
