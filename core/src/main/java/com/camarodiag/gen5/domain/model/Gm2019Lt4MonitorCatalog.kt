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
