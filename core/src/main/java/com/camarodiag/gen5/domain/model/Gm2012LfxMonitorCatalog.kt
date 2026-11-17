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
            "L'écart absolu entre position d'arbre à cames demandée et mesurée dépasse 5,00 degrés de came.",
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
        ),
        GmDtcMonitorEvidence(
            "P0300", "Random / multiple-cylinder misfire monitor",
            "Surveillance des ratés aléatoires / sur plusieurs cylindres",
            "The ECM evaluates crankshaft-velocity-derived terms over operating intervals and compares the misfire rate with emissions and catalyst-damage thresholds. It also checks acceleration patterns to distinguish combustion misfire from crankshaft noise.",
            "Le calculateur analyse des paramètres dérivés de la vitesse du vilebrequin sur des intervalles de fonctionnement et compare le taux de ratés aux seuils d'émissions et de protection du catalyseur. Il examine aussi les profils d'accélération pour distinguer un raté réel d'un bruit de vilebrequin.",
            "Calibration tables combine engine speed, load and deceleration metrics; the applicable thresholds are calibration-dependent and are not represented by one universal percentage.",
            "Les tables de calibration combinent régime, charge et mesures de décélération ; les seuils applicables dépendent de la calibration et ne se résument pas à un pourcentage universel.",
            "More than two crankshaft revolutions; coolant-temperature, system-voltage and throttle-rate conditions must be satisfied; a cold-start temperature inhibit may apply.",
            "Plus de deux révolutions vilebrequin ; les conditions de température de liquide, de tension système et de variation du papillon doivent être satisfaites ; une inhibition à froid peut s'appliquer.",
            "Any five failed 200-revolution blocks in a set of 16; reporting logic includes early-termination conditions. Type B, 2 trips; MIL flashes when catalyst-damaging misfire is detected.",
            "Cinq blocs de 200 révolutions échoués dans une série de 16 ; la logique de déclaration comporte aussi des conditions d'arrêt anticipé. Type B, 2 trajets ; le voyant clignote en cas de ratés susceptibles d'endommager le catalyseur.",
            "Type B – 2 trips; MIL may flash", 145
        ),
        GmDtcMonitorEvidence(
            "P0420", "Catalyst system low efficiency – Bank 1 (idle catalyst monitor applications)",
            "Faible efficacité du catalyseur – banc 1 (applications utilisant le moniteur au ralenti)",
            "The ECM estimates catalyst oxygen-storage capacity using forced lean/rich air-fuel excursions and upstream/downstream oxygen-sensor response.",
            "Le calculateur estime la capacité de stockage d'oxygène du catalyseur à partir de variations forcées pauvre/riche et de la réponse des sondes à oxygène amont et aval.",
            "The exponentially weighted normalized oxygen-storage-capacity ratio falls below 0.34.",
            "Le rapport normalisé de capacité de stockage d'oxygène, filtré par moyenne exponentielle, passe sous 0,34.",
            "A valid idle period is required: accelerator released, idle-speed control active, vehicle speed below 1.24 mph, and the documented engine-speed / run-time conditions satisfied.",
            "Une période de ralenti valide est nécessaire : accélérateur relâché, régulation de ralenti active, vitesse véhicule inférieure à 1,24 mph, et conditions documentées de régime et de temps de fonctionnement satisfaites.",
            "One test per valid idle period; at least one and up to eight attempts per trip. Type A, 1 trip.",
            "Un test par période de ralenti valide ; au moins une et jusqu'à huit tentatives par trajet. Type A, 1 trajet.",
            "Type A – 1 trip", 182
        )
        ,
        GmDtcMonitorEvidence(
            "P0030", "Oxygen sensor heater control circuit – Bank 1 Sensor 1",
            "Circuit de commande du chauffage de sonde à oxygène – banc 1, sonde 1",
            "The ECM checks the heater output-driver circuit for electrical integrity.",
            "Le calculateur contrôle l'intégrité électrique du circuit de commande du chauffage de la sonde.",
            "Voltage is too low while the driver is open (open circuit or short to ground), or too high while the driver is closed (short to voltage).",
            "La tension est trop faible lorsque le driver est ouvert (circuit ouvert ou court-circuit à la masse), ou trop élevée lorsque le driver est fermé (court-circuit au positif).",
            "Ignition in CRANK or RUN; system voltage above 11 V; engine speed above 400 RPM.",
            "Contact en position CRANK ou RUN ; tension système supérieure à 11 V ; régime moteur supérieur à 400 tr/min.",
            "20 failures in 25 samples; 250 ms per sample; continuous monitor.",
            "20 échecs sur 25 échantillons ; 250 ms par échantillon ; surveillance continue.",
            "Type B – 2 trips", 15
        ),
        GmDtcMonitorEvidence(
            "P0036", "Oxygen sensor heater control circuit – Bank 1 Sensor 2",
            "Circuit de commande du chauffage de sonde à oxygène – banc 1, sonde 2",
            "The ECM checks the downstream Bank 1 oxygen-sensor heater output-driver circuit for electrical integrity.",
            "Le calculateur contrôle l'intégrité électrique du circuit de commande du chauffage de la sonde aval du banc 1.",
            "Voltage is too low while the driver is open (open circuit or short to ground), or too high while the driver is closed (short to voltage).",
            "La tension est trop faible lorsque le driver est ouvert (circuit ouvert ou court-circuit à la masse), ou trop élevée lorsque le driver est fermé (court-circuit au positif).",
            "Ignition in CRANK or RUN; system voltage above 11 V; engine speed above 400 RPM.",
            "Contact en position CRANK ou RUN ; tension système supérieure à 11 V ; régime moteur supérieur à 400 tr/min.",
            "20 failures in 25 samples; 250 ms per sample; continuous monitor.",
            "20 échecs sur 25 échantillons ; 250 ms par échantillon ; surveillance continue.",
            "Type B – 2 trips", 16
        ),
        GmDtcMonitorEvidence(
            "P0050", "Oxygen sensor heater control circuit – Bank 2 Sensor 1",
            "Circuit de commande du chauffage de sonde à oxygène – banc 2, sonde 1",
            "The ECM checks the Bank 2 Sensor 1 oxygen-sensor heater output-driver circuit for electrical integrity.",
            "Le calculateur contrôle l'intégrité électrique du circuit de commande du chauffage de la sonde 1 du banc 2.",
            "Voltage is too low while the driver is open (open circuit or short to ground), or too high while the driver is closed (short to voltage).",
            "La tension est trop faible lorsque le driver est ouvert (circuit ouvert ou court-circuit à la masse), ou trop élevée lorsque le driver est fermé (court-circuit au positif).",
            "Ignition in CRANK or RUN; system voltage above 11 V; engine speed above 400 RPM.",
            "Contact en position CRANK ou RUN ; tension système supérieure à 11 V ; régime moteur supérieur à 400 tr/min.",
            "20 failures in 25 samples; 250 ms per sample; continuous monitor.",
            "20 échecs sur 25 échantillons ; 250 ms par échantillon ; surveillance continue.",
            "Type B – 2 trips", 17
        ),
        GmDtcMonitorEvidence(
            "P0053", "Oxygen sensor heater resistance – Bank 1 Sensor 1",
            "Résistance du chauffage de sonde à oxygène – banc 1, sonde 1",
            "The ECM checks whether the Bank 1 Sensor 1 oxygen-sensor heater resistance is within its calibrated range.",
            "Le calculateur vérifie que la résistance du chauffage de la sonde 1 du banc 1 reste dans la plage calibrée.",
            "Heater resistance outside 6.1–13.4 ohms.",
            "Résistance du chauffage hors de la plage 6,1–13,4 ohms.",
            "Valid cold-start conditions; coolant and intake-air temperature, soak time, ignition voltage and engine run time must meet the calibration conditions.",
            "Conditions de démarrage à froid valides ; températures du liquide et de l'air, durée d'immobilisation, tension d'allumage et temps de fonctionnement doivent respecter les conditions de calibration.",
            "Once per valid cold start; Type B, 2 trips.",
            "Une fois par démarrage à froid valide ; Type B, 2 trajets.",
            "Type B – 2 trips", 18
        ),
        GmDtcMonitorEvidence(
            "P0054", "Oxygen sensor heater resistance – Bank 1 Sensor 2",
            "Résistance du chauffage de sonde à oxygène – banc 1, sonde 2",
            "The ECM checks whether the Bank 1 Sensor 2 oxygen-sensor heater resistance is within its calibrated range.",
            "Le calculateur vérifie que la résistance du chauffage de la sonde 2 du banc 1 reste dans la plage calibrée.",
            "Heater resistance outside 6.1–13.4 ohms.",
            "Résistance du chauffage hors de la plage 6,1–13,4 ohms.",
            "Valid cold-start conditions; coolant and intake-air temperature, soak time, ignition voltage and engine run time must meet the calibration conditions.",
            "Conditions de démarrage à froid valides ; températures du liquide et de l'air, durée d'immobilisation, tension d'allumage et temps de fonctionnement doivent respecter les conditions de calibration.",
            "Once per valid cold start; Type B, 2 trips.",
            "Une fois par démarrage à froid valide ; Type B, 2 trajets.",
            "Type B – 2 trips", 19
        ),
        GmDtcMonitorEvidence(
            "P0059", "Oxygen sensor heater resistance – Bank 2 Sensor 1",
            "Résistance du chauffage de sonde à oxygène – banc 2, sonde 1",
            "The ECM checks whether the Bank 2 Sensor 1 oxygen-sensor heater resistance is within its calibrated range.",
            "Le calculateur vérifie que la résistance du chauffage de la sonde 1 du banc 2 reste dans la plage calibrée.",
            "Heater resistance outside 6.1–13.4 ohms.",
            "Résistance du chauffage hors de la plage 6,1–13,4 ohms.",
            "Valid cold-start conditions; coolant and intake-air temperature, soak time, ignition voltage and engine run time must meet the calibration conditions.",
            "Conditions de démarrage à froid valides ; températures du liquide et de l'air, durée d'immobilisation, tension d'allumage et temps de fonctionnement doivent respecter les conditions de calibration.",
            "Once per valid cold start; Type B, 2 trips.",
            "Une fois par démarrage à froid valide ; Type B, 2 trajets.",
            "Type B – 2 trips", 21
        ),
        GmDtcMonitorEvidence(
            "P0060", "Oxygen sensor heater resistance – Bank 2 Sensor 2",
            "Résistance du chauffage de sonde à oxygène – banc 2, sonde 2",
            "The ECM checks whether the Bank 2 Sensor 2 oxygen-sensor heater resistance is within its calibrated range.",
            "Le calculateur vérifie que la résistance du chauffage de la sonde 2 du banc 2 reste dans la plage calibrée.",
            "Heater resistance outside 6.1–13.4 ohms.",
            "Résistance du chauffage hors de la plage 6,1–13,4 ohms.",
            "Valid cold-start conditions; coolant and intake-air temperature, soak time, ignition voltage and engine run time must meet the calibration conditions.",
            "Conditions de démarrage à froid valides ; températures du liquide et de l'air, durée d'immobilisation, tension d'allumage et temps de fonctionnement doivent respecter les conditions de calibration.",
            "Once per valid cold start; Type B, 2 trips.",
            "Une fois par démarrage à froid valide ; Type B, 2 trajets.",
            "Type B – 2 trips", 22
        )
        ,
        GmDtcMonitorEvidence(
            "P0101", "Mass airflow system performance (naturally aspirated engine)",
            "Performance du système de mesure du débit d'air (moteur atmosphérique)",
            "The ECM checks whether the MAF signal is stuck or implausible by combining filtered throttle-model error, measured-versus-modelled airflow error and measured-versus-modelled MAP error.",
            "Le calculateur vérifie si le signal MAF est figé ou incohérent en combinant l'erreur filtrée du modèle papillon, l'écart entre débit d'air mesuré et modélisé, et l'écart entre pression MAP mesurée et modélisée.",
            "The combined filtered residuals exceed the calibration's weighted decision criteria; this is not a single fixed airflow threshold.",
            "Les résidus filtrés combinés dépassent les critères de décision pondérés de la calibration ; il ne s'agit pas d'un seuil unique de débit d'air.",
            "Naturally aspirated application; engine speed 500–6,800 RPM; coolant temperature −7 to 126 °C; intake-air temperature −20 to 125 °C; relevant MAF/MAP/temperature faults must not be active.",
            "Application atmosphérique ; régime moteur de 500 à 6 800 tr/min ; liquide de refroidissement de −7 à 126 °C ; air d'admission de −20 à 125 °C ; les défauts inhibiteurs MAF/MAP/température ne doivent pas être actifs.",
            "Continuous monitor; calculations every 12.5 ms; Type B, 2 trips.",
            "Surveillance continue ; calculs toutes les 12,5 ms ; Type B, 2 trajets.",
            "Type B – 2 trips", 38
        ),
        GmDtcMonitorEvidence(
            "P0102", "Mass airflow sensor circuit low frequency",
            "Fréquence trop basse du circuit du débitmètre d'air MAF",
            "The ECM detects a sustained low-frequency MAF output, consistent with a short to low or an open in the signal circuit or sensor.",
            "Le calculateur détecte une fréquence de sortie MAF durablement trop faible, compatible avec un court-circuit à la masse ou un circuit/senseur ouvert.",
            "MAF output at or below 800 Hz (approximately 0.90 g/s in the GM monitor document).",
            "Sortie MAF inférieure ou égale à 800 Hz (environ 0,90 g/s dans le document de surveillance GM).",
            "Engine run time above 0 s; engine speed at least 300 RPM; ignition voltage at least 10 V; condition persists at least 1 s.",
            "Temps de fonctionnement moteur supérieur à 0 s ; régime d'au moins 300 tr/min ; tension d'allumage d'au moins 10 V ; condition présente pendant au moins 1 s.",
            "300 failures in 375 samples; one sample per cylinder firing event; Type B, 2 trips.",
            "300 échecs sur 375 échantillons ; un échantillon par événement d'allumage ; Type B, 2 trajets.",
            "Type B – 2 trips", 39
        ),
        GmDtcMonitorEvidence(
            "P0103", "Mass airflow sensor circuit high frequency",
            "Fréquence trop élevée du circuit du débitmètre d'air MAF",
            "The ECM detects an abnormally high-frequency MAF output.",
            "Le calculateur détecte une fréquence de sortie MAF anormalement élevée.",
            "MAF output at or above 14,500 Hz (approximately 630 g/s in the GM monitor document).",
            "Sortie MAF supérieure ou égale à 14 500 Hz (environ 630 g/s dans le document de surveillance GM).",
            "Engine run time above 0 s; engine speed at least 300 RPM; ignition voltage at least 10 V; condition persists at least 1 s.",
            "Temps de fonctionnement moteur supérieur à 0 s ; régime d'au moins 300 tr/min ; tension d'allumage d'au moins 10 V ; condition présente pendant au moins 1 s.",
            "300 failures in 375 samples; one sample per cylinder firing event; Type B, 2 trips.",
            "300 échecs sur 375 échantillons ; un échantillon par événement d'allumage ; Type B, 2 trajets.",
            "Type B – 2 trips", 40
        ),
        GmDtcMonitorEvidence(
            "P0128", "Engine coolant temperature below thermostat regulating temperature",
            "Température du liquide de refroidissement inférieure à la température de régulation du thermostat",
            "The ECM detects that coolant temperature rises too slowly, consistent with an ECT-sensor or cooling-system problem.",
            "Le calculateur détecte que la température du liquide de refroidissement augmente trop lentement, ce qui est compatible avec un défaut de sonde ECT ou du circuit de refroidissement.",
            "The calculated cooling-system energy exceeds the calibrated model before ECT reaches 71 °C for the primary start-temperature range, or 55 °C for the alternate colder range.",
            "L'énergie calculée transférée au système de refroidissement dépasse le modèle calibré avant que l'ECT atteigne 71 °C dans la plage principale de température au démarrage, ou 55 °C dans la plage alternative plus froide.",
            "No relevant active sensor/airflow DTCs; valid start-up coolant and intake-air temperature ranges; vehicle-speed and engine-operation conditions must be satisfied.",
            "Aucun défaut actif inhibiteur pertinent sur les capteurs/débit d'air ; plages valides de températures ECT/IAT au démarrage ; conditions de vitesse et de fonctionnement moteur à respecter.",
            "30 failures; one sample per second; once per ignition cycle; Type B, 2 trips.",
            "30 échecs ; un échantillon par seconde ; une fois par cycle de contact ; Type B, 2 trajets.",
            "Type B – 2 trips", 57
        ),
        GmDtcMonitorEvidence(
            "P0171", "Fuel system too lean – Bank 1",
            "Système de carburant trop pauvre – banc 1",
            "The ECM determines a lean condition from filtered long-term and short-term fuel-trim metrics.",
            "Le calculateur détermine un mélange pauvre à partir des valeurs filtrées des corrections de carburant à long et court terme.",
            "The filtered long-term fuel-trim metric is at least 1.400 and the filtered short-term metric is at least 0.650; the short-term criterion is suppressed when its metric is below 0.95.",
            "La valeur filtrée de correction à long terme atteint au moins 1,400 et celle à court terme au moins 0,650 ; le critère court terme est neutralisé lorsque sa valeur est inférieure à 0,95.",
            "400–6,600 RPM; BARO above 70 kPa; coolant and intake-air temperatures within documented ranges; MAP 5–255 kPa; MAF 0.5–510 g/s; fuel level above 10% unless its sender is faulty; at least 25 s of trim data per trip and 20 s in the active trim cell; closed-loop and long-term trim enabled.",
            "400–6 600 tr/min ; BARO supérieure à 70 kPa ; températures de liquide et d'air dans les plages documentées ; MAP de 5 à 255 kPa ; MAF de 0,5 à 510 g/s ; carburant supérieur à 10 % sauf défaut de jauge ; au moins 25 s de données de correction par trajet et 20 s dans la cellule active ; boucle fermée et correction long terme activées.",
            "100 ms continuous loop; Type B, 2 trips.",
            "Boucle continue de 100 ms ; Type B, 2 trajets.",
            "Type B – 2 trips", 111
        ),
        GmDtcMonitorEvidence(
            "P0172", "Fuel system too rich – Bank 1",
            "Système de carburant trop riche – banc 1",
            "The ECM uses passive and intrusive fuel-trim tests. When purge-related long-term trim is at or below 0.735, it ramps purge off to determine whether excess EVAP vapour is causing the rich condition.",
            "Le calculateur utilise des tests passifs et intrusifs de correction carburant. Lorsque la correction long terme liée à la purge est inférieure ou égale à 0,735, il réduit progressivement la purge pour déterminer si un excès de vapeurs EVAP provoque le mélange riche.",
            "The passive test evaluates filtered non-purge long-term and short-term trims; the intrusive test evaluates purge, non-purge and short-term trims across 3 of 5 segments using the GM calibration thresholds.",
            "Le test passif évalue les corrections filtrées long terme hors purge et court terme ; le test intrusif évalue les corrections avec purge, hors purge et court terme sur 3 segments parmi 5, selon les seuils de calibration GM.",
            "Same general engine, airflow, temperature and closed-loop enable conditions as P0171; fuel-level threshold is not used for this rich monitor.",
            "Conditions générales de régime, débit d'air, température et boucle fermée identiques à P0171 ; le seuil de niveau de carburant n'est pas utilisé pour ce moniteur riche.",
            "100 ms continuous loop; Type B, 2 trips; intrusive tests are rate-limited to protect EVAP diagnosis.",
            "Boucle continue de 100 ms ; Type B, 2 trajets ; les tests intrusifs sont limités en fréquence pour préserver le diagnostic EVAP.",
            "Type B – 2 trips", 113
        ),
        GmDtcMonitorEvidence(
            "P0174", "Fuel system too lean – Bank 2",
            "Système de carburant trop pauvre – banc 2",
            "The ECM determines a lean condition from filtered long-term and short-term fuel-trim metrics for Bank 2.",
            "Le calculateur détermine un mélange pauvre sur le banc 2 à partir des valeurs filtrées des corrections de carburant à long et court terme.",
            "The filtered long-term fuel-trim metric is at least 1.400 and the filtered short-term metric is at least 0.650; the short-term criterion is suppressed when its metric is below 0.95.",
            "La valeur filtrée de correction à long terme atteint au moins 1,400 et celle à court terme au moins 0,650 ; le critère court terme est neutralisé lorsque sa valeur est inférieure à 0,95.",
            "Same engine-speed, BARO, coolant, MAP, intake-air, MAF, fuel-level, trim-cell accumulation and closed-loop enable conditions as P0171.",
            "Mêmes conditions d'activation que P0171 pour le régime, BARO, liquide, MAP, air d'admission, MAF, niveau carburant, accumulation de cellule de correction et boucle fermée.",
            "100 ms continuous loop; Type B, 2 trips.",
            "Boucle continue de 100 ms ; Type B, 2 trajets.",
            "Type B – 2 trips", 115
        ),
        GmDtcMonitorEvidence(
            "P0175", "Fuel system too rich – Bank 2",
            "Système de carburant trop riche – banc 2",
            "The ECM uses passive and intrusive fuel-trim tests for Bank 2. When purge-related long-term trim is at or below 0.735, it ramps purge off to determine whether excess EVAP vapour is causing the rich condition.",
            "Le calculateur utilise des tests passifs et intrusifs de correction carburant sur le banc 2. Lorsque la correction long terme liée à la purge est inférieure ou égale à 0,735, il réduit progressivement la purge pour déterminer si un excès de vapeurs EVAP provoque le mélange riche.",
            "The passive test evaluates filtered non-purge long-term and short-term trims; the intrusive test evaluates purge, non-purge and short-term trims across 3 of 5 segments using the GM calibration thresholds.",
            "Le test passif évalue les corrections filtrées long terme hors purge et court terme ; le test intrusif évalue les corrections avec purge, hors purge et court terme sur 3 segments parmi 5, selon les seuils de calibration GM.",
            "Same general engine, airflow, temperature and closed-loop enable conditions as P0174; fuel-level threshold is not used for this rich monitor.",
            "Conditions générales de régime, débit d'air, température et boucle fermée identiques à P0174 ; le seuil de niveau de carburant n'est pas utilisé pour ce moniteur riche.",
            "100 ms continuous loop; Type B, 2 trips; intrusive tests are rate-limited to protect EVAP diagnosis.",
            "Boucle continue de 100 ms ; Type B, 2 trajets ; les tests intrusifs sont limités en fréquence pour préserver le diagnostic EVAP.",
            "Type B – 2 trips", 117
        )
    ).associateBy { it.code }

    fun find(code: String, vehicle: VehicleProfile): GmDtcMonitorEvidence? {
        if (vehicle.modelYear != 2012 || vehicle.engine != CamaroEngine.LFX) return null
        return evidence[code.trim().uppercase(java.util.Locale.ROOT)]
    }

    fun supportedCodes(): Set<String> = evidence.keys
}
