package com.camarodiag.gen5.domain.model

/**
 * GM certification monitor data for 2013 and 2014 Camaro LFX applications.
 * The two model years use different OBD certification groups, but the
 * camshaft monitor thresholds below match in the official group documents.
 */
object Gm2013And2014LfxMonitorCatalog {
    const val SOURCE_2013 =
        "https://gsi.ext.gm.com/gmspo/mode6/pdf/2013/13OBDG05A%20Engine%20Diagnostics.pdf"
    const val SOURCE_2014 =
        "https://gsitlc.ext.gm.com/gmspo/mode6/pdf/2014/14OBDG06A%20Engine%20Diagnostics.pdf"
    const val GROUP_SUMMARY_2013 =
        "https://gsi.ext.gm.com/gmspo/mode6/pdf/2013/2013%20OBD%20GROUP%20SUMMARY.pdf"
    const val GROUP_SUMMARY_2014 =
        "https://gsitlc.ext.gm.com/gmspo/mode6/pdf/2014/2014%20OBD%20GROUP%20SUMMARY.pdf"

    private val evidence = listOf(
        GmDtcMonitorEvidence(
            "P0010", "Intake camshaft actuator solenoid circuit – Bank 1",
            "Circuit de l'électrovanne d'actionnement d'admission – banc 1",
            "The ECM diagnoses the VVT high-side driver circuit for electrical faults.",
            "Le calculateur diagnostique les défauts électriques du circuit driver haut côté du VVT.",
            "Voltage is high while the driver is OFF, indicating a short to power or an open circuit. GM's table specifies ≤0.5 Ω for a short-to-power path and ≥200 kΩ for an open path.",
            "La tension est élevée lorsque le driver est désactivé, ce qui indique un court-circuit au positif ou un circuit ouvert. La table GM indique ≤0,5 Ω pour un chemin en court-circuit au positif et ≥200 kΩ pour un circuit ouvert.",
            "System supply within limits; output driver commanded ON; ignition in CRANK or RUN; system voltage above 11.00 V.",
            "Alimentation système dans les limites ; driver commandé actif ; contact en CRANK ou RUN ; tension système supérieure à 11,00 V.",
            "20 failures in 25 samples; 250 ms per sample; continuous monitor; Type B, 2 trips.",
            "20 échecs sur 25 échantillons ; 250 ms par échantillon ; surveillance continue ; Type B, 2 trajets.",
            "Type B – 2 trips", 1
        ),
        GmDtcMonitorEvidence(
            "P0011", "Intake camshaft system performance – Bank 1",
            "Performance du déphasage d'admission – banc 1",
            "The ECM compares desired and actual camshaft positions while VVT is active.",
            "Le calculateur compare les positions d'arbre à cames demandée et réelle lorsque le VVT est actif.",
            "Absolute desired-to-actual intake camshaft position error exceeds 5.00 cam degrees.",
            "L'écart absolu entre la position d'admission demandée et réelle dépasse 5,00 degrés de came.",
            "System voltage above 11 V; engine running; VVT enabled; desired position above 0; PTO inactive; position values within the calibrated travel window; desired position stable within 4.50 cam degrees for at least 1.00 s.",
            "Tension supérieure à 11 V ; moteur en marche ; VVT activé ; position demandée supérieure à 0 ; PTO inactif ; positions dans la plage de déplacement calibrée ; position demandée stable à 4,50 degrés de came près pendant au moins 1,00 s.",
            "50 failures in 150 samples; 100 ms per sample; Type B, 2 trips.",
            "50 échecs sur 150 échantillons ; 100 ms par échantillon ; Type B, 2 trajets.",
            "Type B – 2 trips", 2
        ),
        GmDtcMonitorEvidence(
            "P0013", "Exhaust camshaft actuator solenoid circuit – Bank 1",
            "Circuit de l'électrovanne d'actionnement d'échappement – banc 1",
            "The ECM diagnoses the VVT high-side driver circuit for electrical faults.",
            "Le calculateur diagnostique les défauts électriques du circuit driver haut côté du VVT.",
            "Voltage is high while the driver is OFF, indicating a short to power or an open circuit. GM's table specifies ≤0.5 Ω for a short-to-power path and ≥200 kΩ for an open path.",
            "La tension est élevée lorsque le driver est désactivé, ce qui indique un court-circuit au positif ou un circuit ouvert. La table GM indique ≤0,5 Ω pour un chemin en court-circuit au positif et ≥200 kΩ pour un circuit ouvert.",
            "System supply within limits; output driver commanded ON; ignition in CRANK or RUN; system voltage above 11.00 V.",
            "Alimentation système dans les limites ; driver commandé actif ; contact en CRANK ou RUN ; tension système supérieure à 11,00 V.",
            "20 failures in 25 samples; 250 ms per sample; continuous monitor; Type B, 2 trips.",
            "20 échecs sur 25 échantillons ; 250 ms par échantillon ; surveillance continue ; Type B, 2 trajets.",
            "Type B – 2 trips", 3
        ),
        GmDtcMonitorEvidence(
            "P0014", "Exhaust camshaft system performance – Bank 1",
            "Performance du déphasage d'échappement – banc 1",
            "The ECM compares desired and actual exhaust camshaft positions while VVT is active.",
            "Le calculateur compare les positions d'arbre à cames d'échappement demandée et réelle lorsque le VVT est actif.",
            "Absolute desired-to-actual exhaust camshaft position error exceeds 5.00 cam degrees.",
            "L'écart absolu entre la position d'échappement demandée et réelle dépasse 5,00 degrés de came.",
            "System voltage above 11 V; engine running; VVT enabled; desired position above 0; PTO inactive; position values within the calibrated travel window; desired position stable within 4.50 cam degrees for at least 1.00 s.",
            "Tension supérieure à 11 V ; moteur en marche ; VVT activé ; position demandée supérieure à 0 ; PTO inactif ; positions dans la plage de déplacement calibrée ; position demandée stable à 4,50 degrés de came près pendant au moins 1,00 s.",
            "100 failures in 300 samples; 100 ms per sample; Type B, 2 trips.",
            "100 échecs sur 300 échantillons ; 100 ms par échantillon ; Type B, 2 trajets.",
            "Type B – 2 trips", 4
        ),
        GmDtcMonitorEvidence(
            "P0016", "Crankshaft / camshaft correlation – Bank 1 Sensor A",
            "Corrélation vilebrequin / arbre à cames – banc 1, capteur A",
            "On applications with a dual intermediate sprocket, the ECM detects timing misalignment between the crankshaft, sprocket and camshafts.",
            "Sur les applications équipées d'un double pignon intermédiaire, le calculateur détecte un décalage de distribution entre le vilebrequin, le pignon et les arbres à cames.",
            "Bank 1 Sensor A pulses more than 6.0 crank degrees before or 9.9 crank degrees after nominal position in one cam revolution.",
            "Les impulsions du capteur A du banc 1 sont décalées de plus de 6,0 degrés vilebrequin avant ou de 9,9 degrés après la position nominale sur une révolution de came.",
            "Crankshaft and camshaft signals synchronized; engine spinning; cam phaser parked; no active crank/cam sensor or 5 V reference faults.",
            "Signaux vilebrequin et arbres à cames synchronisés ; moteur en rotation ; déphaseur au repos ; aucun défaut actif des capteurs vilebrequin/arbre à cames ou des références 5 V.",
            "2 failures out of 3 tests; a failed test is 1 failure in 10 samples; one sample per cam revolution; Type B, 2 trips.",
            "2 échecs sur 3 tests ; un test échoué correspond à 1 échec sur 10 échantillons ; un échantillon par révolution de came ; Type B, 2 trajets.",
            "Type B – 2 trips", 5
        ),
        GmDtcMonitorEvidence(
            "P0017", "Crankshaft / camshaft correlation – Bank 1 Sensor B",
            "Corrélation vilebrequin / arbre à cames – banc 1, capteur B",
            "On applications with a dual intermediate sprocket, the ECM detects timing misalignment between the crankshaft, sprocket and camshafts.",
            "Sur les applications équipées d'un double pignon intermédiaire, le calculateur détecte un décalage de distribution entre le vilebrequin, le pignon et les arbres à cames.",
            "Bank 1 Sensor B pulses more than 5.6 crank degrees before or 9.9 crank degrees after nominal position in one cam revolution.",
            "Les impulsions du capteur B du banc 1 sont décalées de plus de 5,6 degrés vilebrequin avant ou de 9,9 degrés après la position nominale sur une révolution de came.",
            "Crankshaft and camshaft signals synchronized; engine spinning; cam phaser parked; no active crank/cam sensor or 5 V reference faults.",
            "Signaux vilebrequin et arbres à cames synchronisés ; moteur en rotation ; déphaseur au repos ; aucun défaut actif des capteurs vilebrequin/arbre à cames ou des références 5 V.",
            "2 failures out of 3 tests; a failed test is 1 failure in 10 samples; one sample per cam revolution; Type B, 2 trips.",
            "2 échecs sur 3 tests ; un test échoué correspond à 1 échec sur 10 échantillons ; un échantillon par révolution de came ; Type B, 2 trajets.",
            "Type B – 2 trips", 7
        ),
        GmDtcMonitorEvidence(
            "P0018", "Crankshaft / camshaft correlation – Bank 2 Sensor A",
            "Corrélation vilebrequin / arbre à cames – banc 2, capteur A",
            "On applications with a dual intermediate sprocket, the ECM detects timing misalignment between the crankshaft, sprocket and camshafts.",
            "Sur les applications équipées d'un double pignon intermédiaire, le calculateur détecte un décalage de distribution entre le vilebrequin, le pignon et les arbres à cames.",
            "Bank 2 Sensor A pulses more than 6.1 crank degrees before or 9.9 crank degrees after nominal position in one cam revolution.",
            "Les impulsions du capteur A du banc 2 sont décalées de plus de 6,1 degrés vilebrequin avant ou de 9,9 degrés après la position nominale sur une révolution de came.",
            "Crankshaft and camshaft signals synchronized; engine spinning; cam phaser parked; no active crank/cam sensor or 5 V reference faults.",
            "Signaux vilebrequin et arbres à cames synchronisés ; moteur en rotation ; déphaseur au repos ; aucun défaut actif des capteurs vilebrequin/arbre à cames ou des références 5 V.",
            "2 failures out of 3 tests; a failed test is 1 failure in 10 samples; one sample per cam revolution; Type B, 2 trips.",
            "2 échecs sur 3 tests ; un test échoué correspond à 1 échec sur 10 échantillons ; un échantillon par révolution de came ; Type B, 2 trajets.",
            "Type B – 2 trips", 8
        ),
        GmDtcMonitorEvidence(
            "P0019", "Crankshaft / camshaft correlation – Bank 2 Sensor B",
            "Corrélation vilebrequin / arbre à cames – banc 2, capteur B",
            "On applications with a dual intermediate sprocket, the ECM detects timing misalignment between the crankshaft, sprocket and camshafts.",
            "Sur les applications équipées d'un double pignon intermédiaire, le calculateur détecte un décalage de distribution entre le vilebrequin, le pignon et les arbres à cames.",
            "Bank 2 Sensor B pulses more than 7.3 crank degrees before or 9.9 crank degrees after nominal position in one cam revolution.",
            "Les impulsions du capteur B du banc 2 sont décalées de plus de 7,3 degrés vilebrequin avant ou de 9,9 degrés après la position nominale sur une révolution de came.",
            "Crankshaft and camshaft signals synchronized; engine spinning; cam phaser parked; no active crank/cam sensor or 5 V reference faults.",
            "Signaux vilebrequin et arbres à cames synchronisés ; moteur en rotation ; déphaseur au repos ; aucun défaut actif des capteurs vilebrequin/arbre à cames ou des références 5 V.",
            "2 failures out of 3 tests; a failed test is 1 failure in 10 samples; one sample per cam revolution; Type B, 2 trips.",
            "2 échecs sur 3 tests ; un test échoué correspond à 1 échec sur 10 échantillons ; un échantillon par révolution de came ; Type B, 2 trajets.",
            "Type B – 2 trips", 10
        ),
        GmDtcMonitorEvidence(
            "P0020", "Intake camshaft actuator solenoid circuit – Bank 2",
            "Circuit de l'électrovanne d'actionnement d'admission – banc 2",
            "The ECM diagnoses the VVT high-side driver circuit for electrical faults.",
            "Le calculateur diagnostique les défauts électriques du circuit driver haut côté du VVT.",
            "Voltage is high while the driver is OFF, indicating a short to power or an open circuit. GM's table specifies ≤0.5 Ω for a short-to-power path and ≥200 kΩ for an open path.",
            "La tension est élevée lorsque le driver est désactivé, ce qui indique un court-circuit au positif ou un circuit ouvert. La table GM indique ≤0,5 Ω pour un chemin en court-circuit au positif et ≥200 kΩ pour un circuit ouvert.",
            "System supply within limits; output driver commanded ON; ignition in CRANK or RUN; system voltage above 11.00 V.",
            "Alimentation système dans les limites ; driver commandé actif ; contact en CRANK ou RUN ; tension système supérieure à 11,00 V.",
            "20 failures in 25 samples; 250 ms per sample; continuous monitor; Type B, 2 trips.",
            "20 échecs sur 25 échantillons ; 250 ms par échantillon ; surveillance continue ; Type B, 2 trajets.",
            "Type B – 2 trips", 6
        ),
        GmDtcMonitorEvidence(
            "P0021", "Intake camshaft system performance – Bank 2",
            "Performance du déphasage d'admission – banc 2",
            "The ECM compares desired and actual camshaft positions while VVT is active.",
            "Le calculateur compare les positions d'arbre à cames demandée et réelle lorsque le VVT est actif.",
            "Absolute desired-to-actual intake camshaft position error exceeds 5.00 cam degrees.",
            "L'écart absolu entre la position d'admission demandée et réelle dépasse 5,00 degrés de came.",
            "System voltage above 11 V; engine running; VVT enabled; desired position above 0; PTO inactive; position values within the calibrated travel window; desired position stable within 4.50 cam degrees for at least 1.00 s.",
            "Tension supérieure à 11 V ; moteur en marche ; VVT activé ; position demandée supérieure à 0 ; PTO inactif ; positions dans la plage de déplacement calibrée ; position demandée stable à 4,50 degrés de came près pendant au moins 1,00 s.",
            "50 failures in 150 samples; 100 ms per sample; Type B, 2 trips.",
            "50 échecs sur 150 échantillons ; 100 ms par échantillon ; Type B, 2 trajets.",
            "Type B – 2 trips", 7
        ),
        GmDtcMonitorEvidence(
            "P0023", "Exhaust camshaft actuator solenoid circuit – Bank 2",
            "Circuit de l'électrovanne d'actionnement d'échappement – banc 2",
            "The ECM diagnoses the VVT high-side driver circuit for electrical faults.",
            "Le calculateur diagnostique les défauts électriques du circuit driver haut côté du VVT.",
            "Voltage is high while the driver is OFF, indicating a short to power or an open circuit. GM's table specifies ≤0.5 Ω for a short-to-power path and ≥200 kΩ for an open path.",
            "La tension est élevée lorsque le driver est désactivé, ce qui indique un court-circuit au positif ou un circuit ouvert. La table GM indique ≤0,5 Ω pour un chemin en court-circuit au positif et ≥200 kΩ pour un circuit ouvert.",
            "System supply within limits; output driver commanded ON; ignition in CRANK or RUN; system voltage above 11.00 V.",
            "Alimentation système dans les limites ; driver commandé actif ; contact en CRANK ou RUN ; tension système supérieure à 11,00 V.",
            "20 failures in 25 samples; 250 ms per sample; continuous monitor; Type B, 2 trips.",
            "20 échecs sur 25 échantillons ; 250 ms par échantillon ; surveillance continue ; Type B, 2 trajets.",
            "Type B – 2 trips", 8
        ),
        GmDtcMonitorEvidence(
            "P0024", "Exhaust camshaft system performance – Bank 2",
            "Performance du déphasage d'échappement – banc 2",
            "The ECM compares desired and actual exhaust camshaft positions while VVT is active.",
            "Le calculateur compare les positions d'arbre à cames d'échappement demandée et réelle lorsque le VVT est actif.",
            "Absolute desired-to-actual exhaust camshaft position error exceeds 5.00 cam degrees.",
            "L'écart absolu entre la position d'échappement demandée et réelle dépasse 5,00 degrés de came.",
            "System voltage above 11 V; engine running; VVT enabled; desired position above 0; PTO inactive; position values within the calibrated travel window; desired position stable within 4.50 cam degrees for at least 1.00 s.",
            "Tension supérieure à 11 V ; moteur en marche ; VVT activé ; position demandée supérieure à 0 ; PTO inactif ; positions dans la plage de déplacement calibrée ; position demandée stable à 4,50 degrés de came près pendant au moins 1,00 s.",
            "100 failures in 300 samples; 100 ms per sample; Type B, 2 trips.",
            "100 échecs sur 300 échantillons ; 100 ms par échantillon ; Type B, 2 trajets.",
            "Type B – 2 trips", 9
        )
    ).associateBy { it.code }

    fun find(code: String, vehicle: VehicleProfile): GmDtcMonitorEvidence? {
        if (vehicle.engine != CamaroEngine.LFX || vehicle.modelYear !in 2013..2014) return null
        return evidence[code.trim().uppercase(java.util.Locale.ROOT)]
    }

    fun supportedCodes(): Set<String> = evidence.keys
}

object GmLfxMonitorCatalog {
    fun find(code: String, vehicle: VehicleProfile): GmDtcMonitorEvidence? =
        when (vehicle.modelYear) {
            2012 -> Gm2012LfxMonitorCatalog.find(code, vehicle)
            2013, 2014 -> Gm2013And2014LfxMonitorCatalog.find(code, vehicle)
            else -> null
        }

    fun sourceLabel(vehicle: VehicleProfile): String? =
        when (vehicle.modelYear) {
            2012 -> "12OBDG05B"
            2013 -> "13OBDG05A"
            2014 -> "14OBDG06A"
            2015 -> "15OBDG06"
            else -> null
        }
}
