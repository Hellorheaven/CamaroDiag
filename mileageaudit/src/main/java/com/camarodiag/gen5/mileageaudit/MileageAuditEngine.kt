
package com.camarodiag.gen5.mileageaudit

import com.camarodiag.gen5.domain.model.ConfidenceLevel
import com.camarodiag.gen5.domain.model.MileageAuditReport
import com.camarodiag.gen5.domain.model.MileageSourceReading

/**
 * Analyses only values explicitly returned by a vehicle ECU. It never invents an odometer value.
 * BCM and IPC readings count as independent only when their ECU identity and raw response have
 * actually been verified by a vehicle-specific diagnostic profile.
 */
object MileageAuditEngine {
    private val independentModuleIds = setOf("BCM", "IPC", "ECM_TOTAL_ODO")

    fun evaluate(vin: String, readings: List<MileageSourceReading>): MileageAuditReport {
        val valid = readings.filter { reading ->
            reading.kmValue?.let { it >= 0.0 } == true
        }

        val independent = valid.filter {
            it.profileVerified &&
                it.confidence == ConfidenceLevel.HIGH &&
                it.moduleId.uppercase() in independentModuleIds
        }

        val independentValues = independent.mapNotNull { it.kmValue }

        val independentDelta = if (independentValues.size >= 2)
            independentValues.maxOrNull()!! - independentValues.minOrNull()!! else 0.0

        val allValues = valid.mapNotNull { it.kmValue }

        val observedDelta = if (allValues.size >= 2)
            allValues.maxOrNull()!! - allValues.minOrNull()!! else 0.0

        val discrepancy = independentValues.size >= 2 && independentDelta > 15.0

        val fr = when {
            discrepancy -> "Écart de ${"%.1f".format(independentDelta)} km entre sources indépendantes vérifiées. Contrôler remplacement/reprogrammation des modules et historique. Indice à investiguer, pas preuve isolée de fraude."
            independentValues.size >= 2 -> "Les sources indépendantes vérifiées concordent à ${"%.1f".format(independentDelta)} km près. Cela ne garantit pas l'authenticité de l'historique."
            valid.isEmpty() -> "Aucune valeur totale fiable n'a été lue. BCM et IPC restent non vérifiés ; aucune valeur n'est simulée."
            else -> "Seulement ${valid.size} source(s) kilométrique(s) exploitable(s), dont ${independentValues.size} indépendante(s) à haute confiance. Comparaison insuffisante pour conclure."
        }

        val en = when {
            discrepancy -> "${"%.1f".format(independentDelta)} km difference between verified independent sources. Check module replacement/reprogramming and service history. This is an investigation flag, not proof of tampering."
            independentValues.size >= 2 -> "Verified independent sources agree within ${"%.1f".format(independentDelta)} km. This does not guarantee an authentic mileage history."
            valid.isEmpty() -> "No reliable total-odometer value was read. BCM and IPC remain unverified; no value is simulated."
            else -> "Only ${valid.size} usable mileage source(s), including ${independentValues.size} high-confidence independent source(s). Insufficient comparison to conclude."
        }

        return MileageAuditReport(
            timestamp = System.currentTimeMillis(),
            vin = vin,
            readings = readings,
            maxDeltaKm = if (independentValues.size >= 2) independentDelta else observedDelta,
            hasDiscrepancy = discrepancy,
            summaryEn = en,
            summaryFr = fr
        )
    }
}
