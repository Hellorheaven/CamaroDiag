package com.camarodiag.gen5.domain.model

/**
 * Publicly documented GM OBD monitor data. This is certification-monitor
 * evidence, not a complete service diagnostic tree or a parts-replacement rule.
 */
data class GmDtcMonitorEvidence(
    val code: String,
    val componentEn: String,
    val componentFr: String,
    val strategyEn: String,
    val strategyFr: String,
    val malfunctionCriteriaEn: String,
    val malfunctionCriteriaFr: String,
    val enableConditionsEn: String,
    val enableConditionsFr: String,
    val failureCriteriaEn: String,
    val failureCriteriaFr: String,
    val milClassification: String,
    val sourcePage: Int
)

object Gm2012LfxMonitorCatalog {
    const val SOURCE_DOCUMENT =
        "https://gsitlc.ext.gm.com/gmspo/mode6/pdf/2012/12OBDG05B%20Engine%20Diagnostics.pdf"
    const val VEHICLE_GROUP_SOURCE =
        "https://gsitlc.ext.gm.com/gmspo/mode6/pdf/2012/2012_OBD_GROUP_SUMMARY.pdf"

    /**
     * OBD group 5B is explicitly listed for 2012 3.6L LFX Camaro applications.
     * The source lists Camaro and LFX together and lists transmission RPOs MV5/MYB.
     * Calibration/VIN-level equivalence still has to be checked before applying
     * any threshold as a repair specification.
     */
    private val evidence: Map<String, GmDtcMonitorEvidence> = listOf(
        GmDtcMonitorEvidence(
            "P0010", "Intake camshaft actuator solenoid circuit – Bank 1",
            "Circuit de commande de l'électrovanne de déphasage d'admission – banc 1",
            "The ECM monitors electrical integrity by comparing the commanded driver state with the actual control-circuit state.",
            "Le calculateur surveille l'intégrité électrique en comparant l'état commandé du driver à l'état réel du circuit de commande.",
            "A mismatch between the commanded driver state and the actual circuit state is detected.",
            "Un écart est détecté entre l'état commandé du driver et l'état réel du circuit.",
            "System supply voltage above 11 V; output driver commanded on; ignition in CRANK or RUN.",
            "Tension d'alimentation supérieure à 11 V ; driver commandé actif ; contact en position CRANK ou RUN.",
            "20 failures in 25 samples; 250 ms per sample; continuous monitor.",
            "20 échecs sur 25 échantillons ; 250 ms par échantillon ; surveillance continue.",
            "Type B – 2 trips", 1
        ),
        GmDtcMonitorEvidence(
            "P0011", "Intake camshaft system performance – Bank 1",
            "Performance du système de déphasage d'admission – banc 1",
            "The ECM compares desired and measured camshaft positions while VVT is active.",
            "Le calculateur compare les positions d'arbre à cames demandée et mesurée lorsque le VVT est actif.",
            "Absolute desired-to-measured cam position error exceeds 5.00 cam degrees.",
            "L'écart absolu entre position d'arbre à cames demandée et mesurée dépasse 5,00 degrés vilebrequin/came selon l'unité de calibration (cam degrees).",
            "Engine running; VVT enabled; desired cam position above 0; PTO inactive; system voltage above 11 V; position stability and travel-window conditions must also be met.",
            "Moteur en marche ; VVT activé ; position demandée supérieure à 0 ; PTO inactif ; tension supérieure à 11 V ; les conditions de stabilité et de plage de déplacement doivent aussi être satisfaites.",
            "75 failures in 225 samples; 100 ms per sample; Type B, 2 trips.",
            "75 échecs sur 225 échantillons ; 100 ms par échantillon ; Type B, 2 trajets.",
            "Type B – 2 trips", 2
        ),
        GmDtcMonitorEvidence(
            "P0013", "Exhaust camshaft actuator solenoid circuit – Bank 1",
            "Circuit de commande de l'électrovanne de déphasage d'échappement – banc 1",
            "The ECM monitors electrical integrity of the Bank 1 exhaust camshaft actuator circuit.",
            "Le calculateur surveille l'intégrité électrique du circuit d'actionneur d'arbre à cames d'échappement du banc 1.",
            "A mismatch between the commanded driver state and the actual control-circuit state is detected.",
            "Un écart est détecté entre l'état commandé du driver et l'état réel du circuit de commande.",
            "System supply voltage above 11 V; output driver commanded on; ignition in CRANK or RUN.",
            "Tension d'alimentation supérieure à 11 V ; driver commandé actif ; contact en position CRANK ou RUN.",
            "20 failures in 25 samples; 250 ms per sample; continuous monitor.",
            "20 échecs sur 25 échantillons ; 250 ms par échantillon ; surveillance continue.",
            "Type B – 2 trips", 3
        ),
        GmDtcMonitorEvidence(
            "P0014", "Exhaust camshaft system performance – Bank 1",
            "Performance du système de déphasage d'échappement – banc 1",
            "The ECM compares desired and measured exhaust camshaft positions while VVT is active.",
            "Le calculateur compare les positions d'arbre à cames d'échappement demandée et mesurée lorsque le VVT est actif.",
            "Absolute desired-to-measured exhaust cam position error exceeds 5.00 cam degrees.",
            "L'écart absolu entre position d'échappement demandée et mesurée dépasse 5,00 degrés de came.",
            "System voltage above 11 V; engine running; VVT enabled; desired cam position above 0; PTO inactive; position stability and travel-window conditions must also be met.",
            "Tension supérieure à 11 V ; moteur en marche ; VVT activé ; position demandée supérieure à 0 ; PTO inactif ; les conditions de stabilité et de plage de déplacement doivent aussi être satisfaites.",
            "100 failures in 300 samples; 100 ms per sample; Type B, 2 trips.",
            "100 échecs sur 300 échantillons ; 100 ms par échantillon ; Type B, 2 trajets.",
            "Type B – 2 trips", 4
        ),
        GmDtcMonitorEvidence(
            "P0016", "Crankshaft/camshaft correlation – Bank 1 Sensor A",
            "Corrélation vilebrequin/arbre à cames – banc 1, capteur A",
            "The ECM detects cam-to-crank misalignment from the Bank 1 Sensor A pulse position.",
            "Le calculateur détecte un décalage de synchronisation vilebrequin/arbre à cames à partir de la position des impulsions du capteur A du banc 1.",
            "Two cam sensor pulses occur more than 9.0 crank degrees before or 12.0 crank degrees after nominal position in one cam revolution.",
            "Deux impulsions du capteur d'arbre à cames se produisent à plus de 9,0 degrés vilebrequin avant ou 12,0 degrés après la position nominale sur une révolution de came.",
            "Crank and cam signals synchronized; engine spinning; cam phaser parked; relevant crank/cam sensor and 5 V reference faults must not inhibit the monitor.",
            "Signaux vilebrequin et arbres à cames synchronisés ; moteur en rotation ; déphaseur en position de repos ; les défauts inhibiteurs des capteurs et références 5 V ne doivent pas être présents.",
            "2 failed tests out of 3; each failed test is 4 failures out of 5 samples; one sample per cam revolution.",
            "2 tests échoués sur 3 ; chaque test échoué correspond à 4 échecs sur 5 échantillons ; un échantillon par révolution de came.",
            "Type B – 2 trips", 5
        ),
        GmDtcMonitorEvidence(
            "P0017", "Crankshaft/camshaft correlation – Bank 1 Sensor B",
            "Corrélation vilebrequin/arbre à cames – banc 1, capteur B",
            "The ECM detects cam-to-crank misalignment from the Bank 1 Sensor B pulse position.",
            "Le calculateur détecte un décalage de synchronisation vilebrequin/arbre à cames à partir de la position des impulsions du capteur B du banc 1.",
            "Two cam sensor pulses occur more than 10.0 crank degrees before or 13.0 crank degrees after nominal position in one cam revolution.",
            "Deux impulsions du capteur d'arbre à cames se produisent à plus de 10,0 degrés vilebrequin avant ou 13,0 degrés après la position nominale sur une révolution de came.",
            "Crank and cam signals synchronized; engine spinning; cam phaser parked; relevant crank/cam sensor and 5 V reference faults must not inhibit the monitor.",
            "Signaux vilebrequin et arbres à cames synchronisés ; moteur en rotation ; déphaseur en position de repos ; les défauts inhibiteurs des capteurs et références 5 V ne doivent pas être présents.",
            "2 failed tests out of 3; each failed test is 4 failures out of 5 samples; one sample per cam revolution.",
            "2 tests échoués sur 3 ; chaque test échoué correspond à 4 échecs sur 5 échantillons ; un échantillon par révolution de came.",
            "Type B – 2 trips", 7
        ),
        GmDtcMonitorEvidence(
            "P0018", "Crankshaft/camshaft correlation – Bank 2 Sensor A",
            "Corrélation vilebrequin/arbre à cames – banc 2, capteur A",
            "The ECM detects cam-to-crank misalignment from the Bank 2 Sensor A pulse position.",
            "Le calculateur détecte un décalage de synchronisation vilebrequin/arbre à cames à partir de la position des impulsions du capteur A du banc 2.",
            "Two cam sensor pulses occur more than 11.0 crank degrees before or 13.0 crank degrees after nominal position in one cam revolution.",
            "Deux impulsions du capteur d'arbre à cames se produisent à plus de 11,0 degrés vilebrequin avant ou 13,0 degrés après la position nominale sur une révolution de came.",
            "Crank and cam signals synchronized; engine spinning; cam phaser parked; relevant crank/cam sensor and 5 V reference faults must not inhibit the monitor.",
            "Signaux vilebrequin et arbres à cames synchronisés ; moteur en rotation ; déphaseur en position de repos ; les défauts inhibiteurs des capteurs et références 5 V ne doivent pas être présents.",
            "2 failed tests out of 3; each failed test is 4 failures out of 5 samples; one sample per cam revolution.",
            "2 tests échoués sur 3 ; chaque test échoué correspond à 4 échecs sur 5 échantillons ; un échantillon par révolution de came.",
            "Type B – 2 trips", 8
        ),
        GmDtcMonitorEvidence(
            "P0019", "Crankshaft/camshaft correlation – Bank 2 Sensor B",
            "Corrélation vilebrequin/arbre à cames – banc 2, capteur B",
            "The ECM detects cam-to-crank misalignment from the Bank 2 Sensor B pulse position.",
            "Le calculateur détecte un décalage de synchronisation vilebrequin/arbre à cames à partir de la position des impulsions du capteur B du banc 2.",
            "Two cam sensor pulses occur more than 9.3 crank degrees before or 11.1 crank degrees after nominal position in one cam revolution.",
            "Deux impulsions du capteur d'arbre à cames se produisent à plus de 9,3 degrés vilebrequin avant ou 11,1 degrés après la position nominale sur une révolution de came.",
            "Crank and cam signals synchronized; engine spinning; cam phaser parked; relevant crank/cam sensor and 5 V reference faults must not inhibit the monitor.",
            "Signaux vilebrequin et arbres à cames synchronisés ; moteur en rotation ; déphaseur en position de repos ; les défauts inhibiteurs des capteurs et références 5 V ne doivent pas être présents.",
            "2 failed tests out of 3; each failed test is 4 failures out of 5 samples; one sample per cam revolution.",
            "2 tests échoués sur 3 ; chaque test échoué correspond à 4 échecs sur 5 échantillons ; un échantillon par révolution de came.",
            "Type B – 2 trips", 10
        ),
        GmDtcMonitorEvidence(
            "P0020", "Intake camshaft actuator solenoid circuit – Bank 2",
            "Circuit de commande de l'électrovanne de déphasage d'admission – banc 2",
            "The ECM monitors electrical integrity by comparing the commanded driver state with the actual control-circuit state.",
            "Le calculateur surveille l'intégrité électrique en comparant l'état commandé du driver à l'état réel du circuit de commande.",
            "A mismatch between the commanded driver state and the actual circuit state is detected.",
            "Un écart est détecté entre l'état commandé du driver et l'état réel du circuit.",
            "System supply voltage above 11 V; output driver commanded on; ignition in CRANK or RUN.",
            "Tension d'alimentation supérieure à 11 V ; driver commandé actif ; contact en position CRANK ou RUN.",
            "20 failures in 25 samples; 250 ms per sample; continuous monitor.",
            "20 échecs sur 25 échantillons ; 250 ms par échantillon ; surveillance continue.",
            "Type B – 2 trips", 11
        ),
        GmDtcMonitorEvidence(
            "P0021", "Intake camshaft system performance – Bank 2",
            "Performance du système de déphasage d'admission – banc 2",
            "The ECM compares desired and measured camshaft positions while VVT is active.",
            "Le calculateur compare les positions d'arbre à cames demandée et mesurée lorsque le VVT est actif.",
            "Absolute desired-to-measured cam position error exceeds 5.00 cam degrees.",
            "L'écart absolu entre position d'arbre à cames demandée et mesurée dépasse 5,00 degrés de came.",
            "System voltage above 11 V; engine running; VVT enabled; desired cam position above 0; PTO inactive; position stability and travel-window conditions must also be met.",
            "Tension supérieure à 11 V ; moteur en marche ; VVT activé ; position demandée supérieure à 0 ; PTO inactif ; les conditions de stabilité et de plage de déplacement doivent aussi être satisfaites.",
            "75 failures in 225 samples; 100 ms per sample; Type B, 2 trips.",
            "75 échecs sur 225 échantillons ; 100 ms par échantillon ; Type B, 2 trajets.",
            "Type B – 2 trips", 12
        ),
        GmDtcMonitorEvidence(
            "P0023", "Exhaust camshaft actuator solenoid circuit – Bank 2",
            "Circuit de commande de l'électrovanne de déphasage d'échappement – banc 2",
            "The ECM monitors electrical integrity by comparing the commanded driver state with the actual control-circuit state.",
            "Le calculateur surveille l'intégrité électrique en comparant l'état commandé du driver à l'état réel du circuit de commande.",
            "A mismatch between the commanded driver state and the actual circuit state is detected.",
            "Un écart est détecté entre l'état commandé du driver et l'état réel du circuit.",
            "System supply voltage above 11 V; output driver commanded on; ignition in CRANK or RUN.",
            "Tension d'alimentation supérieure à 11 V ; driver commandé actif ; contact en position CRANK ou RUN.",
            "20 failures in 25 samples; 250 ms per sample; continuous monitor.",
            "20 échecs sur 25 échantillons ; 250 ms par échantillon ; surveillance continue.",
            "Type B – 2 trips", 13
        ),
        GmDtcMonitorEvidence(
            "P0024", "Exhaust camshaft system performance – Bank 2",
            "Performance du système de déphasage d'échappement – banc 2",
            "The ECM compares desired and measured exhaust camshaft positions while VVT is active.",
            "Le calculateur compare les positions d'arbre à cames d'échappement demandée et mesurée lorsque le VVT est actif.",
            "Absolute desired-to-measured exhaust cam position error exceeds 5.00 cam degrees.",
            "L'écart absolu entre position d'échappement demandée et mesurée dépasse 5,00 degrés de came.",
            "System voltage above 11 V; engine running; VVT enabled; desired cam position above 0; PTO inactive; position stability and travel-window conditions must also be met.",
            "Tension supérieure à 11 V ; moteur en marche ; VVT activé ; position demandée supérieure à 0 ; PTO inactif ; les conditions de stabilité et de plage de déplacement doivent aussi être satisfaites.",
            "100 failures in 300 samples; 100 ms per sample; Type B, 2 trips.",
            "100 échecs sur 300 échantillons ; 100 ms par échantillon ; Type B, 2 trajets.",
            "Type B – 2 trips", 14
        )
    ).associateBy { it.code }

    fun find(code: String, vehicle: VehicleProfile): GmDtcMonitorEvidence? {
        if (vehicle.modelYear != 2012 || vehicle.engine != CamaroEngine.LFX) return null
        return evidence[code.trim().uppercase(java.util.Locale.ROOT)]
    }

    fun supportedCodes(): Set<String> = evidence.keys
}
