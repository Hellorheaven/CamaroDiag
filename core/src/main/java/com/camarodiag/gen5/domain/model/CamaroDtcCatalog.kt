package com.camarodiag.gen5.domain.model

/**
 * Vehicle-specific interpretation of a diagnostic trouble code.
 *
 * The OEM description states what the module detected. The interpretation,
 * possible causes and checks are guidance only; they do not identify a failed
 * component without supporting measurements.
 */
data class DtcVehicleExplanation(
    val meaningFr: String,
    val meaningEn: String,
    val causesFr: List<String>,
    val causesEn: List<String>,
    val checksFr: List<String>,
    val checksEn: List<String>,
    val severityFr: String,
    val severityEn: String,
    val applicabilityFr: String? = null,
    val applicabilityEn: String? = null
)

object CamaroDtcCatalog {
    /**
     * GM service-information context for Camaro Gen 5 LFX (2012–2015).
     * Codes not listed here retain their SAE/OEM description and are explicitly
     * marked as not yet documented for this exact vehicle configuration.
     */
    private val lfxGen5: Map<String, DtcVehicleExplanation> = mapOf(
        "P0010" to DtcVehicleExplanation(
            "Le calculateur moteur détecte un défaut électrique dans la commande de l'électrovanne de déphasage de l'arbre à cames d'admission du banc 1. Cela ne prouve pas que l'électrovanne elle-même est HS.",
            "The ECM detects an electrical fault in the Bank 1 intake camshaft actuator solenoid control circuit. This does not prove the solenoid itself has failed.",
            listOf("Connecteur ou faisceau coupé, en court-circuit ou oxydé", "Électrovanne de commande de déphasage défectueuse", "Alimentation, masse ou étage de commande ECM en défaut"),
            listOf("Open, shorted or corroded wiring/connector", "Faulty camshaft actuator solenoid", "Power, ground or ECM driver fault"),
            listOf("Contrôler le connecteur et le faisceau de l'électrovanne d'admission du banc 1", "Vérifier alimentation, masse et continuité selon le schéma GM", "Commander l'actionneur avec l'outil de diagnostic et comparer la réponse"),
            listOf("Inspect the Bank 1 intake actuator connector and harness", "Check power, ground and circuit continuity against GM wiring data", "Command the actuator with a scan tool and compare its response"),
            "Élevée si le moteur tourne irrégulièrement ; diagnostic rapide recommandé",
            "High if engine operation is rough; prompt diagnosis recommended"
        ),
        "P0011" to DtcVehicleExplanation(
            "Le calculateur constate que l'arbre à cames d'admission du banc 1 est trop avancé ou ne suit pas la consigne. Sur le V6 LFX, le déphasage est piloté par huile moteur via une électrovanne.",
            "The ECM detects that Bank 1 intake camshaft timing is over-advanced or does not follow the commanded position. On the LFX V6, cam phasing is oil-pressure controlled through a solenoid.",
            listOf("Niveau, viscosité ou état d'huile incorrect", "Électrovanne de déphasage encrassée ou bloquée", "Pression ou circulation d'huile insuffisante", "Déphaseur ou distribution mécanique usé/décalé"),
            listOf("Incorrect oil level, viscosity or condition", "Contaminated or sticking actuator solenoid", "Insufficient oil pressure or flow", "Worn/phased cam actuator or mechanical timing issue"),
            listOf("Vérifier niveau, spécification et état de l'huile", "Comparer angles d'arbre à cames demandés et réels", "Contrôler électrovanne, tamis et pression d'huile avant de déposer la distribution"),
            listOf("Verify oil level, specification and condition", "Compare commanded and actual camshaft angles", "Check solenoid, screen and oil pressure before disturbing mechanical timing"),
            "Élevée — éviter de solliciter le moteur si bruit ou perte de puissance",
            "High — avoid loading the engine if noise or power loss is present"
        ),
        "P0014" to DtcVehicleExplanation(
            "Le calculateur détecte un calage d'arbre à cames d'échappement trop avancé ou une réponse incorrecte sur le banc 1. Le code vise le système de déphasage, pas automatiquement le capteur.",
            "The ECM detects over-advanced exhaust camshaft timing or an incorrect response on Bank 1. The code concerns the cam phasing system, not automatically the sensor.",
            listOf("Huile moteur inadaptée ou pression d'huile insuffisante", "Électrovanne d'échappement grippée/encrassée", "Déphaseur d'arbre à cames défectueux", "Chaîne ou calage de distribution incorrect"),
            listOf("Incorrect oil or low oil pressure", "Sticking/contaminated exhaust actuator solenoid", "Faulty camshaft phaser", "Timing chain or mechanical timing error"),
            listOf("Contrôler huile et pression", "Comparer angle demandé/réel de l'arbre à cames d'échappement banc 1", "Tester électrovanne et faisceau avant toute réparation mécanique"),
            listOf("Check oil and oil pressure", "Compare commanded/actual Bank 1 exhaust cam angle", "Test solenoid and wiring before mechanical repairs"),
            "Élevée — contrôle rapide conseillé",
            "High — prompt inspection advised"
        ),
        "P0016" to DtcVehicleExplanation(
            "Le signal de position du vilebrequin et celui de l'arbre à cames d'admission du banc 1 ne sont plus cohérents. Le calculateur voit un décalage de synchronisation ; ce code ne désigne pas à lui seul un capteur défectueux.",
            "The crankshaft position signal and Bank 1 intake camshaft position signal are not correlated. The ECM detects a synchronization offset; this code alone does not identify a failed sensor.",
            listOf("Chaîne de distribution détendue ou décalée", "Déphaseur ou roue phonique déplacé(e)", "Capteur CKP/CMP, connectique ou entrefer perturbé", "Montage incorrect après intervention mécanique"),
            listOf("Stretched or incorrectly indexed timing chain", "Cam phaser or reluctor wheel movement", "CKP/CMP sensor, connector or air-gap issue", "Incorrect installation after engine work"),
            listOf("Lire les angles d'arbres à cames demandés/réels et les données figées", "Vérifier les signaux CKP/CMP et leurs faisceaux", "Contrôler le calage mécanique avant de remplacer un capteur"),
            listOf("Review commanded/actual cam angles and freeze-frame data", "Verify CKP/CMP signals and wiring", "Check mechanical timing before replacing a sensor"),
            "Critique si accompagné de bruit de chaîne ou de fonctionnement anormal",
            "Critical if accompanied by chain noise or abnormal engine operation"
        ),
        "P0017" to DtcVehicleExplanation(
            "Le signal du vilebrequin et celui de l'arbre à cames d'échappement du banc 1 ne sont pas cohérents. Sur le LFX, le calculateur surveille leur synchronisation ; un décalage peut provenir de la distribution ou du système de mesure.",
            "The crankshaft signal and Bank 1 exhaust camshaft signal are not correlated. On the LFX, the ECM monitors their synchronization; an offset can come from mechanical timing or the sensing system.",
            listOf("Décalage ou allongement de chaîne de distribution", "Déphaseur d'échappement ou roue phonique déplacé(e)", "Capteur vilebrequin/arbre à cames ou faisceau perturbé", "Erreur de montage de distribution"),
            listOf("Timing chain stretch or indexing error", "Exhaust phaser or reluctor wheel movement", "Crankshaft/camshaft sensor or wiring fault", "Incorrect timing installation"),
            listOf("Contrôler les angles réels et demandés des arbres à cames", "Vérifier les signaux CKP/CMP et l'historique d'intervention moteur", "Faire contrôler le calage mécanique si le défaut revient"),
            listOf("Compare actual and commanded camshaft angles", "Check CKP/CMP signals and recent engine repair history", "Have mechanical timing checked if the fault returns"),
            "Critique si bruit, ratés ou perte de puissance ; ne pas conclure au capteur seul",
            "Critical if noise, misfire or power loss is present; do not assume a sensor fault"
        ),
        "P0018" to DtcVehicleExplanation(
            "Défaut de corrélation entre vilebrequin et arbre à cames d'admission du banc 2. Le calculateur détecte un décalage de synchronisation sur le côté opposé du V6.",
            "Crankshaft-to-Bank 2 intake camshaft correlation fault. The ECM detects a synchronization offset on the opposite bank of the V6.",
            listOf("Distribution ou chaîne secondaire décalée", "Déphaseur d'admission banc 2", "Capteur ou faisceau CKP/CMP", "Roue phonique déplacée"),
            listOf("Timing chain or secondary chain indexing", "Bank 2 intake cam phaser", "CKP/CMP sensor or wiring", "Reluctor wheel movement"),
            listOf("Comparer les angles des deux bancs", "Contrôler les signaux et connecteurs", "Vérifier le calage mécanique si les mesures confirment le décalage"),
            listOf("Compare cam angles on both banks", "Inspect signals and connectors", "Verify mechanical timing if measurements confirm the offset"),
            "Critique si le moteur présente des symptômes mécaniques",
            "Critical if mechanical symptoms are present"
        ),
        "P0019" to DtcVehicleExplanation(
            "Défaut de corrélation entre vilebrequin et arbre à cames d'échappement du banc 2. Le code indique une incohérence de synchronisation, pas une pièce précise à remplacer.",
            "Crankshaft-to-Bank 2 exhaust camshaft correlation fault. The code indicates a synchronization mismatch, not a specific part to replace.",
            listOf("Chaîne de distribution ou calage mécanique", "Déphaseur d'échappement banc 2", "Capteurs CKP/CMP, faisceau ou roue phonique"),
            listOf("Timing chain or mechanical timing", "Bank 2 exhaust cam phaser", "CKP/CMP sensors, wiring or reluctor wheel"),
            listOf("Comparer les quatre angles d'arbres à cames", "Vérifier alimentation, signaux et connecteurs", "Contrôler distribution et roues phoniques selon procédure GM"),
            listOf("Compare all four camshaft angles", "Check sensor power, signals and connectors", "Inspect timing and reluctor wheels using GM procedures"),
            "Critique si accompagné de bruit ou de ratés",
            "Critical if accompanied by noise or misfire"
        ),
        "P0101" to DtcVehicleExplanation(
            "Le débit d'air mesuré par le débitmètre MAF ne correspond pas à la plage attendue par le calculateur pour les conditions moteur. Cela ne signifie pas automatiquement que le débitmètre est HS.",
            "Measured MAF airflow is outside the range expected by the ECM for the current engine conditions. This does not automatically mean the MAF sensor has failed.",
            listOf("Débitmètre contaminé ou signal instable", "Prise d'air ou fuite d'admission", "Filtre à air ou conduit d'admission obstrué/mal monté", "Fuite de dépression ou ventilation du carter"),
            listOf("Contaminated MAF or unstable signal", "Intake leak", "Restricted or incorrectly fitted air filter/intake duct", "Vacuum or crankcase ventilation leak"),
            listOf("Comparer MAF, MAP, régime et charge moteur", "Inspecter filtre, conduit et connecteurs", "Rechercher les prises d'air avant de remplacer le MAF"),
            listOf("Compare MAF, MAP, RPM and engine load", "Inspect air filter, intake duct and connectors", "Check for air leaks before replacing the MAF"),
            "Modérée — à diagnostiquer, surtout si ralenti ou puissance affectés",
            "Moderate — diagnose, especially if idle or power is affected"
        ),
        "P0102" to DtcVehicleExplanation(
            "Le calculateur reçoit un signal trop faible du circuit du débitmètre d'air MAF. Il peut s'agir d'un défaut électrique ou d'une mesure réellement anormale.",
            "The ECM receives a low signal from the MAF sensor circuit. This may be an electrical fault or an implausible airflow measurement.",
            listOf("Alimentation ou masse absente", "Fil de signal coupé/en court-circuit", "Connecteur oxydé", "Débitmètre défectueux"),
            listOf("Missing power or ground", "Open/shorted signal wire", "Corroded connector", "Faulty MAF sensor"),
            listOf("Contrôler alimentation, masse et signal au connecteur", "Inspecter faisceau et broches", "Comparer la valeur MAF moteur arrêté puis au ralenti"),
            listOf("Check power, ground and signal at the connector", "Inspect harness and pins", "Compare MAF reading with engine off and at idle"),
            "Modérée",
            "Moderate"
        ),
        "P0103" to DtcVehicleExplanation(
            "Le signal du débitmètre MAF est trop élevé par rapport à la plage électrique attendue par le calculateur.",
            "The MAF sensor signal is higher than the electrical range expected by the ECM.",
            listOf("Court-circuit du signal vers une tension", "Mauvaise masse ou connecteur", "Débitmètre défectueux"),
            listOf("Signal shorted to voltage", "Ground or connector fault", "Faulty MAF sensor"),
            listOf("Contrôler tension de référence, masse et signal", "Inspecter le faisceau près du débitmètre", "Vérifier la cohérence MAF/MAP avant remplacement"),
            listOf("Check reference voltage, ground and signal", "Inspect harness near the MAF", "Verify MAF/MAP plausibility before replacement"),
            "Modérée",
            "Moderate"
        ),
        "P0113" to DtcVehicleExplanation(
            "Le circuit de la sonde de température d'air d'admission transmet une tension trop élevée, généralement interprétée comme une température extrêmement basse ou un circuit ouvert.",
            "The intake-air temperature sensor circuit reports a high voltage, commonly interpreted as extremely cold air or an open circuit.",
            listOf("Connecteur débranché ou oxydé", "Fil de signal coupé", "Sonde IAT défectueuse"),
            listOf("Disconnected or corroded connector", "Open signal wire", "Faulty IAT sensor"),
            listOf("Comparer température IAT à la température ambiante moteur froid", "Contrôler connecteur et continuité", "Vérifier la valeur électrique selon la procédure GM"),
            listOf("Compare cold-soak IAT with ambient temperature", "Check connector and continuity", "Verify resistance/voltage using GM procedure"),
            "Faible à modérée",
            "Low to moderate"
        ),
        "P0128" to DtcVehicleExplanation(
            "Le moteur met trop longtemps à atteindre sa température normale ou reste sous la température de régulation attendue par le calculateur. Le thermostat est une cause possible, pas la seule.",
            "The engine takes too long to reach normal operating temperature or remains below the expected regulating temperature. The thermostat is a possible cause, not the only one.",
            listOf("Thermostat bloqué ouvert", "Sonde ECT ou faisceau imprécis", "Conditions de conduite très froides ou trajet court"),
            listOf("Thermostat stuck open", "Inaccurate ECT sensor or wiring", "Very cold conditions or repeated short trips"),
            listOf("Comparer ECT et température ambiante après immobilisation", "Suivre la montée en température en données live", "Vérifier thermostat et circuit de refroidissement"),
            listOf("Compare ECT with ambient after cold soak", "Monitor warm-up using live data", "Inspect thermostat and cooling system"),
            "Modérée — surveiller la température moteur",
            "Moderate — monitor engine temperature"
        ),
        "P0171" to DtcVehicleExplanation(
            "Le calculateur doit ajouter trop de carburant pour corriger un mélange trop pauvre sur le banc 1 (cylindres 1, 3 et 5 sur le LFX).",
            "The ECM must add too much fuel to correct a lean mixture on Bank 1 (cylinders 1, 3 and 5 on the LFX).",
            listOf("Prise d'air après le MAF", "Fuite d'échappement avant la sonde lambda", "Pression ou débit de carburant insuffisant", "Mesure MAF/MAP incorrecte"),
            listOf("Air leak downstream of the MAF", "Exhaust leak ahead of oxygen sensor", "Insufficient fuel pressure or delivery", "Incorrect MAF/MAP measurement"),
            listOf("Comparer STFT/LTFT des deux bancs au ralenti et à régime stabilisé", "Rechercher les fuites d'admission et d'échappement", "Vérifier pression carburant et plausibilité MAF"),
            listOf("Compare Bank 1/2 STFT and LTFT at idle and steady RPM", "Check intake and exhaust leaks", "Verify fuel pressure and MAF plausibility"),
            "Modérée — élevée si ratés ou risque de surchauffe catalyseur",
            "Moderate — higher if misfire or catalyst overheating occurs"
        ),
        "P0172" to DtcVehicleExplanation(
            "Le calculateur doit retirer trop de carburant pour corriger un mélange trop riche sur le banc 1 (cylindres 1, 3 et 5 sur le LFX).",
            "The ECM must remove too much fuel to correct a rich mixture on Bank 1 (cylinders 1, 3 and 5 on the LFX).",
            listOf("Injecteur qui fuit ou débite trop", "Pression carburant excessive", "Mesure MAF erronée", "Purge EVAP laissant passer des vapeurs"),
            listOf("Leaking or over-delivering injector", "Excessive fuel pressure", "Incorrect MAF reading", "EVAP purge flow when it should be closed"),
            listOf("Comparer corrections carburant des deux bancs", "Contrôler pression de rampe et étanchéité injecteurs", "Tester la purge EVAP et la mesure MAF"),
            listOf("Compare fuel trims on both banks", "Check rail pressure and injector leakage", "Test EVAP purge and MAF reading"),
            "Modérée — éviter de rouler si forte odeur d'essence ou ratés",
            "Moderate — avoid driving if strong fuel smell or misfire is present"
        ),
        "P0174" to DtcVehicleExplanation(
            "Le calculateur corrige un mélange trop pauvre sur le banc 2 (cylindres 2, 4 et 6 sur le LFX).",
            "The ECM is correcting a lean mixture on Bank 2 (cylinders 2, 4 and 6 on the LFX).",
            listOf("Prise d'air localisée sur le banc 2", "Fuite d'échappement avant sonde lambda", "Injecteur ou alimentation carburant", "Sonde ou mesure MAF/MAP"),
            listOf("Intake leak affecting Bank 2", "Exhaust leak ahead of oxygen sensor", "Injector or fuel delivery issue", "Oxygen sensor or MAF/MAP measurement"),
            listOf("Comparer STFT/LTFT banc 1 et banc 2", "Inspecter joints d'admission et échappement banc 2", "Vérifier injecteurs et pression carburant"),
            listOf("Compare Bank 1 and Bank 2 STFT/LTFT", "Inspect Bank 2 intake and exhaust joints", "Check injectors and fuel pressure"),
            "Modérée",
            "Moderate"
        ),
        "P0175" to DtcVehicleExplanation(
            "Le calculateur retire trop de carburant pour corriger un mélange trop riche sur le banc 2 (cylindres 2, 4 et 6 sur le LFX).",
            "The ECM is removing too much fuel to correct a rich mixture on Bank 2 (cylinders 2, 4 and 6 on the LFX).",
            listOf("Injecteur qui fuit", "Pression carburant excessive", "Purge EVAP non étanche", "Mesure MAF/MAP ou sonde lambda incohérente"),
            listOf("Leaking injector", "Excessive fuel pressure", "EVAP purge valve not sealing", "Implausible MAF/MAP or oxygen sensor reading"),
            listOf("Comparer corrections carburant des deux bancs", "Contrôler pression de rampe et injecteurs", "Vérifier purge EVAP et données des sondes lambda"),
            listOf("Compare fuel trims on both banks", "Check rail pressure and injectors", "Verify EVAP purge and oxygen sensor data"),
            "Modérée",
            "Moderate"
        ),
        "P0300" to DtcVehicleExplanation(
            "Le calculateur détecte des ratés de combustion aléatoires ou sur plusieurs cylindres du V6. Un raté signifie qu'une combustion attendue n'a pas produit l'accélération de vilebrequin prévue ; ce n'est pas forcément une bougie.",
            "The ECM detects random or multiple-cylinder misfires on the V6. A misfire means an expected combustion event did not produce the expected crankshaft acceleration; it is not necessarily a spark plug fault.",
            listOf("Bougies ou bobines d'allumage", "Injecteur ou pression de carburant", "Prise d'air ou mélange incorrect", "Compression, soupape ou distribution", "Ratés à froid documentés sur certaines conditions LFX"),
            listOf("Spark plugs or ignition coils", "Injector or fuel pressure", "Air leak or incorrect mixture", "Compression, valve or timing issue", "Cold-start misfire conditions documented on some LFX applications"),
            listOf("Identifier les compteurs de ratés par cylindre dans les données live", "Comparer les ratés à froid et à chaud", "Contrôler allumage, injection et compression selon le cylindre concerné"),
            listOf("Identify per-cylinder misfire counters in live data", "Compare cold and warm misfire behavior", "Check ignition, injection and compression for the affected cylinder"),
            "Élevée ; arrêter le moteur si voyant moteur clignotant",
            "High; stop the engine if the MIL is flashing"
        ),
        "P0301" to DtcVehicleExplanation(
            "Ratés de combustion détectés sur le cylindre 1 du LFX. Le cylindre 1 se trouve sur le banc 1, avec les cylindres 3 et 5.",
            "Misfires detected on LFX cylinder 1. Cylinder 1 is on Bank 1, together with cylinders 3 and 5.",
            listOf("Bougie ou bobine du cylindre 1", "Injecteur 1 ou faisceau", "Prise d'air locale", "Compression ou soupape"),
            listOf("Cylinder 1 spark plug or coil", "Injector 1 or wiring", "Local air leak", "Compression or valve issue"),
            listOf("Contrôler compteur de ratés cylindre 1", "Inspecter bougie/bobine et connecteurs", "Tester injecteur puis compression si nécessaire"),
            listOf("Check cylinder 1 misfire counter", "Inspect plug/coil and connectors", "Test injector, then compression if needed"),
            "Élevée ; voyant clignotant = arrêter",
            "High; flashing MIL = stop"
        ),
        "P0302" to DtcVehicleExplanation(
            "Ratés de combustion détectés sur le cylindre 2 du LFX. Le cylindre 2 se trouve sur le banc 2, avec les cylindres 4 et 6.",
            "Misfires detected on LFX cylinder 2. Cylinder 2 is on Bank 2, together with cylinders 4 and 6.",
            listOf("Bougie ou bobine du cylindre 2", "Injecteur 2 ou faisceau", "Prise d'air locale", "Compression ou soupape"),
            listOf("Cylinder 2 spark plug or coil", "Injector 2 or wiring", "Local air leak", "Compression or valve issue"),
            listOf("Contrôler compteur de ratés cylindre 2", "Inspecter bougie/bobine et connecteurs", "Tester injecteur puis compression si nécessaire"),
            listOf("Check cylinder 2 misfire counter", "Inspect plug/coil and connectors", "Test injector, then compression if needed"),
            "Élevée ; voyant clignotant = arrêter",
            "High; flashing MIL = stop"
        ),
        "P0303" to DtcVehicleExplanation(
            "Ratés de combustion détectés sur le cylindre 3 du LFX. Le cylindre 3 se trouve sur le banc 1, avec les cylindres 1 et 5.",
            "Misfires detected on LFX cylinder 3. Cylinder 3 is on Bank 1, together with cylinders 1 and 5.",
            listOf("Bougie ou bobine du cylindre 3", "Injecteur 3 ou faisceau", "Prise d'air locale", "Compression ou soupape"),
            listOf("Cylinder 3 spark plug or coil", "Injector 3 or wiring", "Local air leak", "Compression or valve issue"),
            listOf("Contrôler compteur de ratés cylindre 3", "Inspecter bougie/bobine et connecteurs", "Tester injecteur puis compression si nécessaire"),
            listOf("Check cylinder 3 misfire counter", "Inspect plug/coil and connectors", "Test injector, then compression if needed"),
            "Élevée ; voyant clignotant = arrêter",
            "High; flashing MIL = stop"
        ),
        "P0304" to DtcVehicleExplanation(
            "Ratés de combustion détectés sur le cylindre 4 du LFX. Le cylindre 4 se trouve sur le banc 2, avec les cylindres 2 et 6.",
            "Misfires detected on LFX cylinder 4. Cylinder 4 is on Bank 2, together with cylinders 2 and 6.",
            listOf("Bougie ou bobine du cylindre 4", "Injecteur 4 ou faisceau", "Prise d'air locale", "Compression ou soupape"),
            listOf("Cylinder 4 spark plug or coil", "Injector 4 or wiring", "Local air leak", "Compression or valve issue"),
            listOf("Contrôler compteur de ratés cylindre 4", "Inspecter bougie/bobine et connecteurs", "Tester injecteur puis compression si nécessaire"),
            listOf("Check cylinder 4 misfire counter", "Inspect plug/coil and connectors", "Test injector, then compression if needed"),
            "Élevée ; voyant clignotant = arrêter",
            "High; flashing MIL = stop"
        ),
        "P0305" to DtcVehicleExplanation(
            "Ratés de combustion détectés sur le cylindre 5 du LFX. Le cylindre 5 se trouve sur le banc 1, avec les cylindres 1 et 3.",
            "Misfires detected on LFX cylinder 5. Cylinder 5 is on Bank 1, together with cylinders 1 and 3.",
            listOf("Bougie ou bobine du cylindre 5", "Injecteur 5 ou faisceau", "Prise d'air locale", "Compression ou soupape"),
            listOf("Cylinder 5 spark plug or coil", "Injector 5 or wiring", "Local air leak", "Compression or valve issue"),
            listOf("Contrôler compteur de ratés cylindre 5", "Inspecter bougie/bobine et connecteurs", "Tester injecteur puis compression si nécessaire"),
            listOf("Check cylinder 5 misfire counter", "Inspect plug/coil and connectors", "Test injector, then compression if needed"),
            "Élevée ; voyant clignotant = arrêter",
            "High; flashing MIL = stop"
        ),
        "P0306" to DtcVehicleExplanation(
            "Ratés de combustion détectés sur le cylindre 6 du LFX. Le cylindre 6 se trouve sur le banc 2, avec les cylindres 2 et 4.",
            "Misfires detected on LFX cylinder 6. Cylinder 6 is on Bank 2, together with cylinders 2 and 4.",
            listOf("Bougie ou bobine du cylindre 6", "Injecteur 6 ou faisceau", "Prise d'air locale", "Compression ou soupape"),
            listOf("Cylinder 6 spark plug or coil", "Injector 6 or wiring", "Local air leak", "Compression or valve issue"),
            listOf("Contrôler compteur de ratés cylindre 6", "Inspecter bougie/bobine et connecteurs", "Tester injecteur puis compression si nécessaire"),
            listOf("Check cylinder 6 misfire counter", "Inspect plug/coil and connectors", "Test injector, then compression if needed"),
            "Élevée ; voyant clignotant = arrêter",
            "High; flashing MIL = stop"
        ),
        "P0420" to DtcVehicleExplanation(
            "Le calculateur estime que l'efficacité du catalyseur du banc 1 est inférieure au seuil surveillé par les sondes d'oxygène. Cela ne prouve pas à lui seul que le catalyseur est à remplacer.",
            "The ECM estimates that Bank 1 catalyst efficiency is below the threshold monitored using oxygen sensors. This alone does not prove the catalytic converter must be replaced.",
            listOf("Catalyseur dégradé", "Fuite d'échappement", "Sonde lambda amont/aval ou faisceau", "Ratés ou mélange incorrect ayant endommagé le catalyseur"),
            listOf("Degraded catalytic converter", "Exhaust leak", "Upstream/downstream oxygen sensor or wiring", "Misfire or mixture fault damaging the catalyst"),
            listOf("Résoudre d'abord tout défaut de raté ou de richesse", "Vérifier absence de fuite d'échappement", "Comparer les signaux des sondes amont et aval"),
            listOf("Resolve misfire or fuel-trim faults first", "Check for exhaust leaks", "Compare upstream and downstream oxygen sensor signals"),
            "Modérée ; élevée si ratés actifs ou surchauffe",
            "Moderate; high if active misfire or overheating is present"
        ),
        "P0430" to DtcVehicleExplanation(
            "Même principe que P0420, mais pour le catalyseur du banc 2 (cylindres 2, 4 et 6).",
            "Same principle as P0420, but for the Bank 2 catalytic converter (cylinders 2, 4 and 6).",
            listOf("Catalyseur banc 2 dégradé", "Fuite d'échappement ou sonde lambda", "Ratés/mélange incorrect sur banc 2"),
            listOf("Degraded Bank 2 catalyst", "Exhaust leak or oxygen sensor fault", "Misfire/mixture issue on Bank 2"),
            listOf("Contrôler d'abord ratés et corrections carburant banc 2", "Inspecter les fuites d'échappement", "Comparer sondes lambda amont/aval du banc 2"),
            listOf("Check Bank 2 misfire and fuel trims first", "Inspect exhaust leaks", "Compare Bank 2 upstream/downstream oxygen sensors"),
            "Modérée",
            "Moderate"
        ),
        "P0442" to DtcVehicleExplanation(
            "Le système de récupération des vapeurs d'essence EVAP présente une petite fuite détectée lors de son test d'étanchéité.",
            "The evaporative-emissions (EVAP) system has a small leak detected during its sealing test.",
            listOf("Bouchon de réservoir mal fermé ou joint abîmé", "Durite EVAP fissurée", "Raccord ou joint du circuit de vapeurs", "Électrovanne de ventilation/purge non étanche"),
            listOf("Loose fuel cap or damaged seal", "Cracked EVAP hose", "Vapor-line fitting or seal", "Vent/purge valve not sealing"),
            listOf("Vérifier bouchon et joint", "Inspecter conduites EVAP et raccords", "Effectuer un test fumigène avant remplacement de pièces"),
            listOf("Check fuel cap and seal", "Inspect EVAP hoses and fittings", "Perform a smoke test before replacing parts"),
            "Faible — généralement sans danger immédiat",
            "Low — generally no immediate drivability risk"
        ),
        "P0455" to DtcVehicleExplanation(
            "Le système EVAP détecte une fuite importante ou une absence d'étanchéité. Le calculateur ne peut pas maintenir la dépression attendue dans le circuit de vapeurs.",
            "The EVAP system detects a large leak or loss of sealing. The ECM cannot maintain the expected vacuum in the vapor system.",
            listOf("Bouchon de carburant absent/mal fermé", "Durite EVAP débranchée ou rompue", "Canister ou électrovanne de ventilation", "Fuite sur réservoir ou goulotte"),
            listOf("Missing/loose fuel cap", "Disconnected or broken EVAP hose", "Canister or vent solenoid", "Tank or filler-neck leak"),
            listOf("Contrôler bouchon et goulotte", "Inspecter toutes les conduites visibles", "Localiser la fuite par test fumigène"),
            listOf("Check fuel cap and filler neck", "Inspect all accessible vapor lines", "Locate the leak with a smoke test"),
            "Faible à modérée ; attention à toute odeur de carburant",
            "Low to moderate; investigate any fuel odor"
        ),
        "P0496" to DtcVehicleExplanation(
            "Le calculateur détecte un passage de vapeurs/carburant dans le circuit EVAP alors que la purge devrait être inactive.",
            "The ECM detects vapor/fuel flow in the EVAP system when purge should be inactive.",
            listOf("Électrovanne de purge bloquée ouverte", "Durite de purge mal raccordée", "Commande électrique ou faisceau"),
            listOf("Purge solenoid stuck open", "Incorrectly routed purge hose", "Electrical control or wiring fault"),
            listOf("Vérifier le débit de purge à commande fermée", "Contrôler l'étanchéité de l'électrovanne", "Inspecter durites et connecteurs"),
            listOf("Check purge flow with command closed", "Test purge valve sealing", "Inspect hoses and connectors"),
            "Modérée — peut provoquer démarrage difficile après ravitaillement",
            "Moderate — may cause hard starting after refueling"
        ),
        "P0507" to DtcVehicleExplanation(
            "Le régime de ralenti mesuré est supérieur à la valeur que le calculateur demande. Le calculateur ne parvient pas à ramener le moteur au ralenti cible.",
            "Measured idle speed is higher than the ECM target. The controller cannot bring engine speed down to the commanded idle.",
            listOf("Prise d'air ou fuite de dépression", "Boîtier papillon encrassé ou qui ferme mal", "Purge EVAP qui laisse passer de l'air", "Adaptation papillon/ralenti"),
            listOf("Intake or vacuum leak", "Dirty or sticking throttle body", "EVAP purge valve passing air", "Throttle/idle relearn issue"),
            listOf("Comparer régime demandé et réel", "Rechercher une prise d'air", "Contrôler papillon et purge EVAP avant tout réglage"),
            listOf("Compare commanded and actual RPM", "Check for intake leaks", "Inspect throttle body and EVAP purge before adjustments"),
            "Modérée",
            "Moderate"
        ),
        "P06DD" to DtcVehicleExplanation(
            "Le calculateur détecte une performance anormale de la commande de régulation de pression d'huile moteur. Le défaut concerne la régulation hydraulique ; il ne faut pas ignorer un voyant de pression d'huile.",
            "The ECM detects abnormal performance in the engine oil-pressure control system. This concerns hydraulic pressure regulation; an oil-pressure warning must not be ignored.",
            listOf("Niveau ou viscosité d'huile incorrects", "Filtre à huile inadapté ou restriction", "Électrovanne de régulation de pression", "Pression réelle insuffisante ou capteur/circuit"),
            listOf("Incorrect oil level or viscosity", "Incorrect/restricted oil filter", "Oil pressure control solenoid", "Actual low pressure or sensor/circuit issue"),
            listOf("Vérifier immédiatement niveau et spécification d'huile", "Si voyant pression allumé ou bruit : couper le moteur", "Mesurer la pression avec un manomètre selon procédure GM"),
            listOf("Immediately verify oil level and specification", "If oil-pressure warning or noise is present, shut the engine off", "Measure pressure with a mechanical gauge using GM procedure"),
            "Critique si pression d'huile basse ou voyant allumé",
            "Critical if oil pressure is low or warning lamp is on"
        ),
        "P0087" to DtcVehicleExplanation(
            "La pression de carburant mesurée dans la rampe est inférieure à la pression attendue. Le LFX utilise l'injection directe : la pression haute pression doit être contrôlée avec les valeurs et procédures GM.",
            "Fuel-rail pressure is below the expected value. The LFX uses direct injection; high-pressure fuel must be checked using GM specifications and procedures.",
            listOf("Alimentation basse pression insuffisante", "Pompe haute pression ou commande", "Filtre/conduite ou alimentation électrique", "Capteur de pression ou faisceau"),
            listOf("Insufficient low-side fuel supply", "High-pressure pump or control", "Filter/line or electrical supply", "Pressure sensor or wiring"),
            listOf("Comparer pression demandée et réelle en données live", "Contrôler circuit basse pression avant la pompe haute pression", "Ne jamais desserrer une conduite haute pression moteur en marche"),
            listOf("Compare commanded and actual rail pressure in live data", "Check low-pressure supply before condemning the high-pressure pump", "Never loosen a high-pressure line with the engine running"),
            "Élevée — risque de calage ou perte de puissance",
            "High — stalling or power loss may occur"
        ),
        "P0089" to DtcVehicleExplanation(
            "La régulation de pression de carburant ne suit pas correctement la consigne du calculateur. Le code ne permet pas de distinguer seul pompe, capteur, alimentation ou régulation.",
            "Fuel-pressure regulation does not follow the ECM command correctly. This code alone cannot distinguish pump, sensor, supply or control faults.",
            listOf("Pompe haute pression ou alimentation basse pression", "Capteur de pression de rampe", "Commande électrique/régulation", "Conduite ou filtre restreint"),
            listOf("High-pressure pump or low-pressure supply", "Fuel-rail pressure sensor", "Electrical/control fault", "Restricted line or filter"),
            listOf("Enregistrer pression demandée/réelle et régime au défaut", "Contrôler basse pression et alimentation électrique", "Suivre la procédure de test haute pression GM"),
            listOf("Record commanded/actual pressure and RPM at fault time", "Check low-side pressure and electrical supply", "Follow GM high-pressure system test procedure"),
            "Élevée",
            "High"
        ),
        "P0191" to DtcVehicleExplanation(
            "Le signal du capteur de pression de rampe est incohérent ou hors plage attendue. Il peut s'agir du capteur, de son circuit ou d'une pression réellement instable.",
            "The fuel-rail pressure sensor signal is implausible or outside the expected range. The sensor, its circuit or genuinely unstable pressure may be responsible.",
            listOf("Capteur de pression de rampe", "Connecteur ou faisceau", "Pression carburant réellement instable", "Problème d'alimentation/régulation"),
            listOf("Fuel-rail pressure sensor", "Connector or wiring", "Genuinely unstable fuel pressure", "Supply/regulation issue"),
            listOf("Comparer pression demandée, mesurée et moteur arrêté", "Contrôler référence, masse et signal du capteur", "Vérifier la pression réelle selon procédure GM"),
            listOf("Compare commanded/measured pressure and key-on engine-off value", "Check sensor reference, ground and signal", "Verify actual pressure using GM procedure"),
            "Élevée",
            "High"
        ),
        "P1101" to DtcVehicleExplanation(
            "Le calculateur détecte une incohérence de performance du système de mesure/admission d'air. Sur le LFX, rechercher en priorité une incohérence entre débit MAF, pression MAP et air réellement admis.",
            "The ECM detects an airflow/induction system performance mismatch. On the LFX, first look for disagreement between MAF, MAP and actual inducted air.",
            listOf("Prise d'air ou fuite d'admission", "Débitmètre MAF contaminé", "Conduit d'air ou boîtier papillon", "Fuite de ventilation du carter"),
            listOf("Intake air leak", "Contaminated MAF sensor", "Air duct or throttle body issue", "Crankcase ventilation leak"),
            listOf("Comparer MAF/MAP et angle papillon", "Inspecter conduits et joints d'admission", "Vérifier les corrections carburant avant remplacement du débitmètre"),
            listOf("Compare MAF/MAP and throttle angle", "Inspect intake ducts and seals", "Review fuel trims before replacing the MAF"),
            "Modérée",
            "Moderate"
        ),
        "P0299" to DtcVehicleExplanation(
            "Le code P0299 concerne une sous-pression de suralimentation. Le moteur LFX 3.6L de cette Camaro est atmosphérique et ne possède pas de turbocompresseur ni de compresseur d'origine : ce code est donc incohérent avec la configuration LFX d'origine. Vérifier le code brut, le calculateur émetteur et toute modification moteur.",
            "P0299 means underboost. The factory LFX 3.6L Camaro engine is naturally aspirated and has no factory turbocharger or supercharger, so this code is inconsistent with the stock LFX configuration. Verify the raw code, reporting module and any engine modifications.",
            listOf("Mauvaise identification du véhicule ou du moteur", "Code décodé depuis une réponse incorrecte", "Suralimentation ajoutée après fabrication"),
            listOf("Incorrect vehicle or engine identification", "Code decoded from an incorrect response", "Aftermarket forced-induction conversion"),
            listOf("Relire le DTC avec son calculateur et les octets bruts", "Vérifier VIN, RPO moteur et calibration", "Ne pas remplacer de pièce de suralimentation sur la seule base de ce code"),
            listOf("Re-read the DTC with its reporting ECU and raw bytes", "Verify VIN, engine RPO and calibration", "Do not replace boost-system parts based on this code alone"),
            "À confirmer — incohérent avec un LFX d'origine",
            "Unverified — inconsistent with a stock LFX",
            "Code non applicable à un LFX atmosphérique d'origine.",
            "Not applicable to a stock naturally aspirated LFX."
        ),
        "P0307" to DtcVehicleExplanation(
            "P0307 désigne normalement des ratés sur le cylindre 7. Le V6 LFX ne possède que six cylindres : ce code n'est pas applicable à ce moteur d'origine. Vérifier le code brut et l'identification du calculateur.",
            "P0307 normally indicates cylinder 7 misfire. The LFX V6 has only six cylinders, so this code does not apply to the stock engine. Verify the raw code and ECU identification.",
            listOf("Mauvaise identification du moteur ou du calculateur", "Réponse de diagnostic mal décodée"),
            listOf("Incorrect engine or ECU identification", "Incorrectly decoded diagnostic response"),
            listOf("Relire les octets DTC bruts", "Vérifier le profil moteur sélectionné et le calculateur répondant"),
            listOf("Re-read raw DTC bytes", "Verify selected engine profile and responding ECU"),
            "À confirmer — code incompatible avec un V6 six cylindres",
            "Unverified — code incompatible with a six-cylinder V6",
            "Un cylindre 7 n'existe pas sur le LFX.",
            "Cylinder 7 does not exist on the LFX."
        ),
        "P0308" to DtcVehicleExplanation(
            "P0308 désigne normalement des ratés sur le cylindre 8. Le V6 LFX ne possède que six cylindres : ce code n'est pas applicable à ce moteur d'origine. Vérifier le code brut et l'identification du calculateur.",
            "P0308 normally indicates cylinder 8 misfire. The LFX V6 has only six cylinders, so this code does not apply to the stock engine. Verify the raw code and ECU identification.",
            listOf("Mauvaise identification du moteur ou du calculateur", "Réponse de diagnostic mal décodée"),
            listOf("Incorrect engine or ECU identification", "Incorrectly decoded diagnostic response"),
            listOf("Relire les octets DTC bruts", "Vérifier le profil moteur sélectionné et le calculateur répondant"),
            listOf("Re-read raw DTC bytes", "Verify selected engine profile and responding ECU"),
            "À confirmer — code incompatible avec un V6 six cylindres",
            "Unverified — code incompatible with a six-cylinder V6",
            "Un cylindre 8 n'existe pas sur le LFX.",
            "Cylinder 8 does not exist on the LFX."
        )
        )
    )

    fun find(code: String, vehicle: VehicleProfile): DtcVehicleExplanation? {
        if (vehicle.engine != CamaroEngine.LFX || vehicle.modelYear !in 2012..2015) return null
        return lfxGen5[code.trim().uppercase()]
    }

    fun isKnownLfxProfile(vehicle: VehicleProfile): Boolean =
        vehicle.engine == CamaroEngine.LFX && vehicle.modelYear in 2012..2015
}
