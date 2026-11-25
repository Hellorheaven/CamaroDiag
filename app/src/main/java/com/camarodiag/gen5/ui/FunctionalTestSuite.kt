package com.camarodiag.gen5.ui

import com.camarodiag.gen5.domain.model.EcuModule
import com.camarodiag.gen5.domain.model.ModuleStatus

enum class FunctionalTestOutcome { PASSED, FAILED, NOT_TESTED }

data class FunctionalTestCheck(
    val key: String,
    val group: String,
    val labelEn: String,
    val labelFr: String,
    val outcome: FunctionalTestOutcome,
    val detailEn: String,
    val detailFr: String
)

/** Immutable evidence context captured at the start of a physical run. */
data class FunctionalTestContext(
    val selectedVin: String,
    val adapterName: String,
    val transport: String,
    val connectionGeneration: Long,
    val observedVin: String? = null
)

data class FunctionalTestState(
    val running: Boolean = false,
    val startedAtMillis: Long? = null,
    val finishedAtMillis: Long? = null,
    val checks: List<FunctionalTestCheck> = emptyList(),
    val error: String? = null,
    val context: FunctionalTestContext? = null
) {
    val canExport: Boolean get() = !running && finishedAtMillis != null && context != null && checks.isNotEmpty()
}

/** Enumerates all supported transports and topology candidates without inventing hardware results. */
object FunctionalTestPlanner {
    private val connectorKinds = listOf(
        Triple("BLUETOOTH", "Bluetooth Classic / ELM327", "Bluetooth classique / ELM327"),
        Triple("BLE", "Bluetooth LE / OBDLink", "Bluetooth LE / OBDLink"),
        Triple("USB", "USB OTG / ELM-STN", "USB OTG / ELM-STN"),
        Triple("WIFI", "Wi-Fi / ELM327", "Wi-Fi / ELM327"),
        Triple("J2534_BRIDGE", "Windows J2534 bridge", "Pont J2534 Windows")
    )

    fun initial(
        modules: List<EcuModule>, activeTransport: String, physicalConnection: Boolean
    ): List<FunctionalTestCheck> {
        val connectorRows = connectorKinds.map { (kind, en, fr) ->
            val present = physicalConnection && activeTransport == kind
            FunctionalTestCheck(
                key = "connector:" + kind, group = "connector", labelEn = en, labelFr = fr,
                outcome = if (present) FunctionalTestOutcome.PASSED else FunctionalTestOutcome.NOT_TESTED,
                detailEn = if (present) "Physical transport connected and probed." else "Not connected; no physical test executed.",
                detailFr = if (present) "Interface physique connectée et vérifiée." else "Non connectée : aucun essai physique."
            )
        }
        val moduleRows = modules.map { ecu ->
            val absent = ecu.status == ModuleStatus.NOT_EQUIPPED
            FunctionalTestCheck(
                key = "module:" + ecu.id, group = "ecu",
                labelEn = ecu.shortName + " — " + ecu.nameEn,
                labelFr = ecu.shortName + " — " + ecu.nameFr,
                outcome = FunctionalTestOutcome.NOT_TESTED,
                detailEn = if (absent) "Not equipped on this vehicle profile." else
                    "Unverified: physical ECU address, protocol or equipment must be confirmed.",
                detailFr = if (absent) "Absent selon la configuration du véhicule." else
                    "Non vérifié : adresse physique, protocole ou présence à confirmer."
            )
        }
        val services = listOf(
            Triple("vin", "Read ECU / vehicle identification", "Lecture identification ECU / VIN"),
            Triple("pids", "OBD-II supported PIDs", "PID OBD-II pris en charge"),
            Triple("dtcs", "Read DTCs (no clearing)", "Lecture des défauts (sans effacement)"),
            Triple("readiness", "Emissions readiness", "Moniteurs antipollution")
        ).map { (kind, en, fr) ->
            FunctionalTestCheck(
                key = "service:" + kind, group = "service", labelEn = en, labelFr = fr,
                outcome = FunctionalTestOutcome.NOT_TESTED,
                detailEn = "Awaiting a compatible physical diagnostic channel.",
                detailFr = "En attente d'un canal de diagnostic physique compatible."
            )
        }
        return connectorRows + moduleRows + services
    }

    /** An OBD response ID is not proof of a particular OEM ECU. */
    fun withStandardResponders(rows: List<FunctionalTestCheck>, addresses: Collection<String>): List<FunctionalTestCheck> =
        rows.filterNot { it.group == "obd_responder" } + addresses.distinct().sorted().map { address ->
            FunctionalTestCheck("obd_responder:$address", "obd_responder",
                "Standard OBD responder $address", "Répondant OBD standard $address",
                FunctionalTestOutcome.PASSED,
                "Physical response to Mode 01 PID 00; OEM ECU identity not established.",
                "Réponse physique au mode 01 PID 00 ; identité constructeur du calculateur non établie.")
        }

    fun withDtcModeEvidence(rows: List<FunctionalTestCheck>, counts: Map<Int, Int?>): List<FunctionalTestCheck> =
        rows.filterNot { it.key.startsWith("service:dtc-mode:") } + counts.entries.sortedBy { it.key }.map { (mode, count) ->
            val hex = "%02X".format(java.util.Locale.ROOT, mode)
            FunctionalTestCheck("service:dtc-mode:$hex", "service",
                "OBD DTC mode $hex", "Mode DTC OBD $hex",
                if (count == null) FunctionalTestOutcome.NOT_TESTED else FunctionalTestOutcome.PASSED,
                if (count == null) "No valid positive response; support unconfirmed." else "Positive physical response: $count codes.",
                if (count == null) "Aucune réponse positive valide ; prise en charge non confirmée." else "Réponse physique positive : $count défauts.")
        }

    fun updated(
        rows: List<FunctionalTestCheck>, key: String, outcome: FunctionalTestOutcome,
        detailEn: String, detailFr: String
    ): List<FunctionalTestCheck> =
        rows.map { if (it.key == key) it.copy(outcome = outcome, detailEn = detailEn, detailFr = detailFr) else it }
}
