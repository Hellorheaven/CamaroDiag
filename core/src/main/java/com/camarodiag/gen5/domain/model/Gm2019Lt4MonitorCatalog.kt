package com.camarodiag.gen5.domain.model

/**
 * LT4 Camaro-only monitor evidence extracted from GM 19OBDG03A Diagnostics,
 * ECM Unique (LT4 Camaro), pages 5–6 of that section (document pages 607–608).
 *
 * These are certification monitor criteria, not a substitute for VIN/CAL ID
 * validation or the GM service repair procedure.
 */
object Gm2019Lt4MonitorCatalog {
    const val SOURCE_DOCUMENT =
        "https://gsitlc.ext.gm.com/gmspo/mode6/pdf/2019/19OBDG03A%20Diagnostics.pdf"

    private val evidence = listOf(
        GmDtcMonitorEvidence(
            "P0196",
            "Engine oil temperature (EOT) sensor performance",
            "Performance du capteur de température d'huile moteur (EOT)",
            "The ECM checks whether the EOT sensor is stuck or biased in range using three independent tests: cold-start comparison against ECT/IAT, warm-up comparison against a target EOT, and continuous comparison against modeled oil temperature.",
            "Le calculateur vérifie si le capteur EOT est figé ou décalé dans sa plage au moyen de trois tests indépendants : comparaison au démarrage à froid avec ECT/IAT, comparaison pendant la montée en température avec une cible EOT et comparaison continue avec la température d'huile modélisée.",
            "The cold-start fast test uses an ECT-dependent EOT-to-ECT difference threshold from the GM supporting table; the warm-up test evaluates EOT against the 90 °C target; the continuous test evaluates measured-versus-modeled EOT difference.",
            "Le test rapide à froid utilise un seuil d'écart EOT/ECT dépendant de l'ECT et défini dans la table GM ; le test de montée en température évalue l'EOT par rapport à la cible de 90 °C ; le test continu compare l'EOT mesurée à l'EOT modélisée.",
            "EOT diagnostic enabled and engine running; cold-start test requires a valid engine-off timer above 540 seconds; warm-up and continuous tests have additional temperature, accumulated-airflow and model-equilibrium conditions; related ECT/IAT/MAF/EOT circuit faults inhibit testing.",
            "Diagnostic EOT activé et moteur en marche ; le test à froid exige un temporisateur d'arrêt valide supérieur à 540 secondes ; les tests de montée en température et continu ont des conditions supplémentaires de température, débit d'air cumulé et équilibre du modèle ; les défauts associés ECT/IAT/MAF/circuit EOT inhibent le test.",
            "Cold-start fast-fail threshold varies with power-up ECT (79.5 °C at −40 to −16 °C, 60.0 °C at −4 to 8 °C, 39.8 °C at 20 to 32 °C, then 30.0 °C from 44 °C; see supporting tables pages 1182–1183); warm-up fail below 90 °C after calibrated accumulated airflow; continuous fail when both measured/modelled EOT error criteria exceed 30.0 °C, 210 failures out of 300 samples at 1 sample/second; Type B, 2 trips.",
            "Le seuil d'échec rapide à froid varie avec l'ECT au démarrage (79,5 °C de −40 à −16 °C, 60,0 °C de −4 à 8 °C, 39,8 °C de 20 à 32 °C, puis 30,0 °C à partir de 44 °C ; voir les tables de soutien pages 1182–1183) ; échec de montée en température sous 90 °C après le débit d'air cumulé calibré ; échec continu lorsque les deux critères d'écart EOT mesurée/modélisée dépassent 30,0 °C, 210 échecs sur 300 échantillons à raison d'un échantillon/seconde ; type B, 2 trajets.",
            "Type B – 2 trips",
            603
        ),
        GmDtcMonitorEvidence(
            "P0197",
            "Engine oil temperature (EOT) sensor circuit low",
            "Circuit du capteur de température d'huile moteur (EOT) bas",
            "The ECM monitors the EOT sensor low-sided driver for a short-to-ground condition when the output is powered off.",
            "Le calculateur surveille le driver côté bas du capteur EOT afin de détecter un court-circuit à la masse lorsque la sortie est désactivée.",
            "Engine Oil Temperature Sensor (EOT) circuit resistance is below 25 Ω.",
            "La résistance du circuit du capteur de température d'huile moteur (EOT) est inférieure à 25 Ω.",
            "Diagnostic status is enabled.",
            "Le statut du diagnostic est activé.",
            "20 failures out of 50 samples, sampled once per second; Type B, 2 trips.",
            "20 échecs sur 50 échantillons, avec un échantillonnage par seconde ; type B, 2 trajets.",
            "Type B – 2 trips",
            607
        ),
        GmDtcMonitorEvidence(
            "P0198",
            "Engine oil temperature (EOT) sensor circuit high",
            "Circuit du capteur de température d'huile moteur (EOT) haut",
            "The ECM monitors the EOT sensor low-sided driver for a short-to-power condition when the output is powered on.",
            "Le calculateur surveille le driver côté bas du capteur EOT afin de détecter un court-circuit au positif lorsque la sortie est activée.",
            "Engine Oil Temperature Sensor (EOT) circuit resistance is above 450,000 Ω.",
            "La résistance du circuit du capteur de température d'huile moteur (EOT) est supérieure à 450 000 Ω.",
            "Diagnostic status is enabled; engine run time is above 20.0 seconds OR engine coolant temperature is at least −20 °C.",
            "Le statut du diagnostic est activé ; le temps de fonctionnement moteur dépasse 20,0 secondes OU la température du liquide de refroidissement est au moins égale à −20 °C.",
            "20 failures out of 50 samples, sampled once per second; Type B, 2 trips.",
            "20 échecs sur 50 échantillons, avec un échantillonnage par seconde ; type B, 2 trajets.",
            "Type B – 2 trips",
            608
        )
    ).associateBy { it.code }

    fun find(code: String, vehicle: VehicleProfile): GmDtcMonitorEvidence? {
        if (vehicle.modelYear != 2019 || vehicle.engine != CamaroEngine.LT4) return null
        return evidence[code.trim().uppercase(java.util.Locale.ROOT)]
    }

    fun supportedCodes(): Set<String> = evidence.keys
}
