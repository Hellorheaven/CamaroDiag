package com.camarodiag.gen5.ui

import com.camarodiag.gen5.domain.model.EcuModule
import com.camarodiag.gen5.domain.model.ModuleStatus

enum class FunctionalTestOutcome { PASSED, FAILED, NOT_TESTED }

enum class FunctionalProtocol(val id: String, val title: String, val implemented: Boolean = false,
    val expectedLegacyElmProtocol: Int? = null) {
    J1979("SAE-J1979", "SAE J1979 — OBD-II", true),
    J1979_PWM("SAE-J1979-J1850-PWM", "SAE J1979 — J1850 PWM", true, 1),
    J1979_VPW("SAE-J1979-J1850-VPW", "SAE J1979 — J1850 VPW", true, 2),
    J1979_ISO9141("SAE-J1979-ISO9141-2", "SAE J1979 — ISO 9141-2", true, 3),
    J1979_KWP_SLOW("SAE-J1979-KWP-SLOW", "SAE J1979 — KWP slow init", true, 4),
    J1979_KWP_FAST("SAE-J1979-KWP-FAST", "SAE J1979 — KWP fast init", true, 5),
    DOIP("ISO-13400", "ISO 13400 — DoIP", true),
    J1979_2("SAE-J1979-2", "SAE J1979-2 — OBDonUDS"),
    UDS("ISO-14229", "ISO 14229 — UDS"),
    CAN("CAN-CLASSIC", "CAN 11/29 bit"),
    CAN_FD("CAN-FD", "CAN-FD"),
    ISOTP("ISO-15765-2-CAN", "ISO 15765-2 — CAN"),
    ISOTP_FD("ISO-15765-2-FD", "ISO 15765-2 — CAN-FD"),
    ISO9141("ISO-9141-2", "ISO 9141-2"),
    KWP("ISO-14230", "ISO 14230 — KWP2000"),
    VPW("SAE-J1850-VPW", "SAE J1850 VPW"),
    PWM("SAE-J1850-PWM", "SAE J1850 PWM"),
    SWCAN("SAE-J2411", "SAE J2411 — SW-CAN"),
    GMLAN("GM-GMLAN", "GM GMLAN"),
    CLASS2("GM-CLASS-2", "GM Class 2"),
    ALDL("GM-ALDL", "GM ALDL"),
    LIN("ISO-17987", "ISO 17987 — LIN"),
    J1939_21("SAE-J1939-21", "SAE J1939-21"),
    J1939_22("SAE-J1939-22", "SAE J1939-22"),
    J1939_81("SAE-J1939-81", "SAE J1939-81"),
    J1708("SAE-J1708", "SAE J1708"),
    J1587("SAE-J1587", "SAE J1587"),
    KWP1281("VAG-KWP1281", "VAG KWP1281"),
    TP20("VAG-TP2-0", "VAG TP2.0"),
    J2534_1("SAE-J2534-1", "SAE J2534-1"),
    J2534_2("SAE-J2534-2", "SAE J2534-2"),
    RP1210("SAE-RP1210", "SAE RP1210"),
    D_PDU("ISO-22900-2", "ISO 22900-2 — D-PDU API");

    val isJ1979Recipe: Boolean get() = this == J1979 || expectedLegacyElmProtocol != null
}

data class DoIpFunctionalTarget(
    val host: String, val tester: String, val ecu: String, val vin: String,
    val tls: Boolean, val physicalVehicleConfirmed: Boolean
) {
    val valid: Boolean get() = physicalVehicleConfirmed && host.isNotBlank() &&
        tester.matches(Regex("[0-9A-Fa-f]{4}")) && ecu.matches(Regex("[0-9A-Fa-f]{4}")) &&
        !tester.equals(ecu, true) && vin.matches(Regex("[A-HJ-NPR-Z0-9]{17}"))
}

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
    val context: FunctionalTestContext? = null,
    val rawObdExchanges: List<String> = emptyList(),
    val protocol: FunctionalProtocol? = null,
    val obdScan: com.camarodiag.gen5.data.obd.ObdScanReport? = null,
    val doIpTarget: DoIpFunctionalTarget? = null,
    val evapCampaign: Boolean = false,
    val evapResult: com.camarodiag.gen5.data.obd.ObdEvapResult? = null,
    val clearCampaign: Boolean = false,
    val clearResult: com.camarodiag.gen5.data.obd.ObdClearRecipeResult? = null,
    val legacyTarget: com.camarodiag.gen5.data.obd.LegacyMutationTarget? = null,
    val legacyBaseline: com.camarodiag.gen5.data.obd.LegacyMutationResult? = null,
    val legacyResult: com.camarodiag.gen5.data.obd.LegacyMutationResult? = null,
    val legacyDiscovery: com.camarodiag.gen5.data.obd.LegacySourceDiscovery? = null,
    val legacyRead: com.camarodiag.gen5.data.obd.LegacyReadReport? = null,
    val livePlan: com.camarodiag.gen5.data.obd.J1979LivePlan? = null,
    val liveReport: com.camarodiag.gen5.data.obd.J1979LiveReport? = null,
    val liveRecent: List<com.camarodiag.gen5.data.obd.J1979LiveObservation> = emptyList(),
    val nativeUsbEvidence: String? = null
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

    fun doIpChecks(): List<FunctionalTestCheck> = listOf(
        Triple("discovery", "UDP gateway identification", "Identification passerelle UDP"),
        Triple("status", "Gateway status", "État de la passerelle"),
        Triple("power", "Diagnostic power mode", "Mode alimentation diagnostic"),
        Triple("vin", "TCP / TLS routing and ECU VIN", "Routage TCP / TLS et VIN ECU"),
        Triple("dtc_count", "UDS DTC count", "Nombre de défauts UDS"),
        Triple("dtcs", "UDS DTC read", "Lecture des défauts UDS"),
        Triple("write", "Write / recovery (separate test)", "Écriture / restauration (essai distinct)")
    ).map { (id, en, fr) ->
        FunctionalTestCheck("protocol:$id", "protocol", en, fr, FunctionalTestOutcome.NOT_TESTED,
            "No verified response in this campaign.", "Aucune réponse vérifiée dans cette campagne.")
    }

    fun legacyMutationChecks(): List<FunctionalTestCheck> = listOf(
        Triple("scope","Documented response scope and VIN","Sources documentées et VIN"),
        Triple("baseline","Durable initial evidence","Preuves initiales sauvegardées"),
        Triple("ack","Operation acknowledgment","Acquittement de la demande"),
        Triple("readback","Confirmed and pending DTC readback","Relecture défauts confirmés et en attente"),
        Triple("remaining","Absence of confirmed and pending DTC on documented sources","Absence de défauts confirmés et en attente sur les sources documentées"),
        Triple("evap","EVAP leak test completion","Fin du test de fuite EVAP")
    ).map { (key,en,fr) -> FunctionalTestCheck("legacy:$key","protocol",en,fr,
        FunctionalTestOutcome.NOT_TESTED,"No verified evidence.","Aucune preuve vérifiée.") }

    fun legacyMutationResults(state: FunctionalTestState,
        result: com.camarodiag.gen5.data.obd.LegacyMutationResult,
        baselineSaved: Boolean): FunctionalTestState {
        var checks = state.checks
        fun mark(key: String, status: FunctionalTestOutcome, en: String, fr: String) {
            checks = updated(checks,"legacy:$key",status,en,fr)
        }
        if (baselineSaved && result.observedVin != null)
            mark("scope",FunctionalTestOutcome.PASSED,"Documented scope and VIN checked; not exhaustive.",
                "Sources documentées et VIN contrôlés ; couverture non exhaustive.")
        if (baselineSaved) mark("baseline",FunctionalTestOutcome.PASSED,"Initial report stored locally.","Rapport initial enregistré localement.")
        if (result.outcome in setOf("ACKNOWLEDGED","ACKNOWLEDGED_READBACK","ACKNOWLEDGED_PARTIAL"))
            mark("ack",FunctionalTestOutcome.PASSED,"Expected acknowledgments observed.","Acquittements attendus observés.")
        else if (result.attempted) mark("ack",FunctionalTestOutcome.FAILED,
            "Missing or uncertain acknowledgment; no retry.","Acquittement manquant ou incertain ; aucune répétition.")
        val allRead = result.target.clear && result.after.isNotEmpty() &&
            result.after.values.all { it["03"] != null && it["07"] != null }
        if (allRead) {
            mark("readback",FunctionalTestOutcome.PASSED,"Both modes observed on documented sources.",
                "Les deux modes relus sur les sources documentées.")
            val empty = result.after.values.all { it["03"].orEmpty().isEmpty() && it["07"].orEmpty().isEmpty() }
            mark("remaining",if (empty) FunctionalTestOutcome.PASSED else FunctionalTestOutcome.FAILED,
                if (empty) "No confirmed or pending DTC observed on documented sources." else "DTCs remain.",
                if (empty) "Aucun défaut confirmé ou en attente observé sur les sources documentées." else "Des défauts subsistent.")
        }
        return state.copy(checks=checks,legacyResult=result,
            context=state.context?.copy(observedVin=result.observedVin),
            rawObdExchanges=result.observations.map { "${it.stage} > ${it.request} -> ${it.response ?: "?"}" })
    }

    fun clearChecks(): List<FunctionalTestCheck> = listOf(
        Triple("baseline", "Confirmed DTC baseline", "Défauts confirmés avant effacement"),
        Triple("ack", "Mode 04 acknowledgment", "Acquittement Mode 04"),
        Triple("readback", "Confirmed and pending readback", "Relecture défauts confirmés et en attente"),
        Triple("remaining", "Confirmed and pending DTCs absent", "Absence de défauts confirmés et en attente"),
        Triple("permanent", "Permanent DTC readback", "Relecture des défauts permanents")
    ).map { (id, en, fr) -> FunctionalTestCheck("protocol:04-$id", "protocol", en, fr,
        FunctionalTestOutcome.NOT_TESTED, "No verified evidence.", "Aucune preuve vérifiée.") }

    fun clearResults(state: FunctionalTestState,
        result: com.camarodiag.gen5.data.obd.ObdClearRecipeResult): FunctionalTestState {
        var rows = state.checks
        fun mark(id: String, status: FunctionalTestOutcome, en: String, fr: String) {
            rows = updated(rows, "protocol:04-$id", status, en, fr)
        }
        fun observed(id: String, records: List<String>?) {
            if (records != null) mark(id, FunctionalTestOutcome.PASSED,
                "Verified records: ${records.size}.", "Défauts relus : ${records.size}.")
        }
        observed("baseline", result.beforeDtcs["03"])
        if (result.acknowledged) mark("ack", FunctionalTestOutcome.PASSED,
            "Exact Mode 04 acknowledgment.", "Acquittement exact Mode 04.")
        else if (result.attempted) mark("ack", FunctionalTestOutcome.FAILED,
            "Refused or uncertain; no automatic retry.", "Refus ou état incertain ; aucune répétition automatique.")
        val confirmed = result.afterDtcs["03"]
        val pending = result.afterDtcs["07"]
        if (result.acknowledged && confirmed != null && pending != null) {
            mark("readback", FunctionalTestOutcome.PASSED,
                "Both modes positively reread.", "Les deux modes ont été relus positivement.")
            val empty = confirmed.isEmpty() && pending.isEmpty()
            mark("remaining", if (empty) FunctionalTestOutcome.PASSED else FunctionalTestOutcome.FAILED,
                if (empty) "No confirmed or pending DTC observed on selected ECU." else "DTCs remain present.",
                if (empty) "Aucun défaut confirmé ou en attente sur le calculateur sélectionné." else "Des défauts subsistent.")
        }
        observed("permanent", result.afterDtcs["0A"])
        return state.copy(clearResult = result, checks = rows,
            context = state.context?.copy(observedVin = result.observedVin),
            rawObdExchanges = result.observations.map {
                "${it.stage} > ${it.requestHex} -> ${it.responseHex ?: "?"}"
            })
    }

    fun evapChecks(): List<FunctionalTestCheck> = listOf(
        FunctionalTestCheck("protocol:08-evap-ack", "protocol",
            "EVAP request acknowledgment", "Acquittement de la demande EVAP",
            FunctionalTestOutcome.NOT_TESTED, "No verified acknowledgment.", "Aucun acquittement vérifié."),
        FunctionalTestCheck("protocol:08-evap-completion", "protocol",
            "EVAP monitor completion", "Fin du moniteur EVAP",
            FunctionalTestOutcome.NOT_TESTED, "Separate monitor evidence required.", "Preuve distincte du moniteur requise.")
    )

    fun evapResults(state: FunctionalTestState,
        result: com.camarodiag.gen5.data.obd.ObdEvapResult): FunctionalTestState {
        val status = when (result.outcome) {
            "ACKNOWLEDGED" -> FunctionalTestOutcome.PASSED
            "REFUSED", "UNCERTAIN" -> FunctionalTestOutcome.FAILED
            else -> FunctionalTestOutcome.NOT_TESTED
        }
        val (en, fr) = when (result.outcome) {
            "ACKNOWLEDGED" -> "ECU accepted the request; monitor completion not verified." to
                "Demande acquittée ; fin du moniteur non vérifiée."
            "REFUSED" -> "ECU refused the test request." to "Demande refusée par le calculateur."
            "UNCERTAIN" -> "Transmission or response uncertain; do not retry automatically." to
                "Transmission ou réponse incertaine ; ne pas relancer automatiquement."
            else -> "Preconditions not verified; no test command attempted." to
                "Conditions non vérifiées ; aucune commande de test tentée."
        }
        return state.copy(evapResult = result,
            context = state.context?.copy(observedVin = result.observedVin),
            checks = updated(state.checks, "protocol:08-evap-ack", status, en, fr),
            rawObdExchanges = result.exchanges.map { "> ${it.requestHex} -> ${it.responseHex ?: "?"}" })
    }

    fun j1979Checks(): List<FunctionalTestCheck> = listOf(
        Triple("transport", "Negotiated diagnostic transport", "Transport diagnostique négocié"),
        Triple("vin", "Mode 09 VIN", "Mode 09 VIN"),
        Triple("info", "Mode 09 supported information types", "Types d’informations mode 09"),
        Triple("pids", "Mode 01 PID inventory", "Inventaire PID mode 01"),
        Triple("live", "Mode 01 measurements", "Mesures mode 01"),
        Triple("readiness", "Mode 01 readiness", "Moniteurs mode 01"),
        Triple("freeze", "Mode 02 freeze frame", "Trame figée mode 02"),
        Triple("monitor-inventory", "Mode 06 monitor inventory", "Inventaire moniteurs mode 06"),
        Triple("monitor", "Mode 06 monitor results", "Résultats moniteurs mode 06"),
        Triple("05", "Mode 05 oxygen sensor tests", "Tests sondes mode 05"),
        Triple("08-inventory", "Mode 08 supported control tests", "Tests de commande annoncés mode 08"),
        Triple("08", "Mode 08 system control (separate test)", "Commande système mode 08 (essai distinct)"),
        Triple("04", "Mode 04 clear DTCs (separate test)", "Effacement DTC mode 04 (essai distinct)")
    ).map { (id, en, fr) ->
        FunctionalTestCheck("protocol:$id", "protocol", en, fr, FunctionalTestOutcome.NOT_TESTED,
            "No verified response in this campaign.", "Aucune réponse vérifiée dans cette campagne.")
    }

    internal fun monitorEvidence(raw: String?): Pair<Boolean, Boolean> {
        val monitor = raw.orEmpty()
        val complete = monitor.isNotBlank() && "PARTIAL" !in monitor && "N/A" !in monitor
        val hasResults = monitor.lineSequence().any {
            (it.startsWith("MID=") || it.startsWith("TID=")) && " RAW=" in it
        }
        val emptyInventory = monitor == "DISCOVERED_MIDS=0" || monitor == "DISCOVERED_TIDS=0"
        return (complete && (emptyInventory || hasResults)) to (complete && hasResults)
    }

    fun j1979Results(state: FunctionalTestState, report: com.camarodiag.gen5.data.obd.ObdScanReport): FunctionalTestState {
        var rows = state.checks
        fun mark(key: String, ok: Boolean) {
            if (ok) rows = updated(rows, "protocol:$key", FunctionalTestOutcome.PASSED,
                "Verified positive diagnostic response.", "Réponse diagnostique positive vérifiée.")
        }
        val expected = state.protocol?.expectedLegacyElmProtocol
        val actual = report.negotiatedElmProtocol
        if (expected != null && actual != expected) {
            rows = updated(rows, "protocol:transport", FunctionalTestOutcome.FAILED,
                "Selected legacy protocol not verified.", "Protocole legacy sélectionné non vérifié.")
            return state.copy(checks = rows, obdScan = report, rawObdExchanges = report.rawLog)
        }
        mark("transport", report.pidInventoryComplete != null && report.firstUnresolvedPid != 1 &&
            (actual?.let { it in 1..9 } == true || report.physicalCanResponder != null))
        mark("vin", com.camaradiag.gen5.domain.model.VehicleVinMatcher.normalize(report.vin) != null)
        mark("info", report.vehicleInfoSupportComplete == true)
        if (report.pidInventoryComplete == true && report.firstUnresolvedPid == null) {
            rows = updated(rows, "protocol:pids", FunctionalTestOutcome.PASSED,
                if (report.pidAttributionIncomplete) "Verified advertised PID union; ECU attribution incomplete."
                else "Verified PID inventory.",
                if (report.pidAttributionIncomplete) "Union des PID annoncés vérifiée ; attribution ECU incomplète."
                else "Inventaire PID vérifié.")
        }
        mark("live", report.liveData.isNotEmpty())
        mark("readiness", !report.readiness.isNullOrBlank())
        mark("freeze", report.freezeFrameDtc != null && report.freezeFrameSupportComplete == true &&
            report.freezeFrameReads.isNotEmpty() && report.freezeFrameReads.all { it.verifiedBytes != null })
        val (inventoryProved, resultsProved) = monitorEvidence(report.monitorTestRaw)
        mark("monitor-inventory", inventoryProved)
        mark("monitor", resultsProved)
        mark("05", report.mode05?.let { it.complete && it.hasTestResults } == true)
        mark("08-inventory", report.mode08?.complete == true)
        val counts = mapOf(3 to report.confirmedDtcs.size, 7 to report.pendingDtcs.size, 10 to report.permanentDtcs.size)
            .mapValues { (mode, count) -> count.takeIf {
                mode !in report.unverifiedDtcModes
            } }
        rows = withDtcModeEvidence(rows, counts)
        return withObservedIdentity(state.copy(checks = rows, obdScan = report, rawObdExchanges = report.rawLog), report.vin)
    }

    fun withLegacyDiscovery(state: FunctionalTestState,
        discovery: com.camarodiag.gen5.data.obd.LegacySourceDiscovery): FunctionalTestState {
        val rows = state.checks.filterNot { it.group == "legacy_responder" }
        val observations = discovery.sources.map { source ->
            val outcome = when (source.status) {
                "PASSED" -> FunctionalTestOutcome.PASSED
                "FAILED" -> FunctionalTestOutcome.FAILED
                else -> FunctionalTestOutcome.NOT_TESTED
            }
            FunctionalTestCheck("legacy-responder:${source.source}","legacy_responder",
                "Legacy emissions source ${source.source}","Source antipollution legacy ${source.source}",
                outcome,
                when (outcome) {
                    FunctionalTestOutcome.PASSED -> "Verified PID 00 response; OEM identity and whole-vehicle coverage not established."
                    FunctionalTestOutcome.FAILED -> "Conflicting or invalid application records; source not verified."
                    FunctionalTestOutcome.NOT_TESTED -> "Negative response; PID 00 support not verified."
                },
                when (outcome) {
                    FunctionalTestOutcome.PASSED -> "Réponse PID 00 vérifiée ; identité constructeur et couverture globale non établies."
                    FunctionalTestOutcome.FAILED -> "Réponses contradictoires ou invalides ; source non vérifiée."
                    FunctionalTestOutcome.NOT_TESTED -> "Réponse négative ; support PID 00 non vérifié."
                })
        }
        val discoveryCheck = FunctionalTestCheck("legacy-responder:discovery","legacy_responder",
            "Legacy responder discovery","Découverte des répondants legacy",
            if (discovery.error != null) FunctionalTestOutcome.FAILED
            else if (discovery.verifiedSources.isNotEmpty()) FunctionalTestOutcome.PASSED
            else FunctionalTestOutcome.NOT_TESTED,
            "Observed sources only; no automatic engine selection.",
            "Sources observées uniquement ; aucune sélection automatique du moteur.")
        return state.copy(legacyDiscovery=discovery,checks=rows+discoveryCheck+observations,
            rawObdExchanges=state.rawObdExchanges+listOf("> 0100 -> ${discovery.rawResponse}",
                "> ATDPN -> ${discovery.rawProtocolResponse}"))
    }

    /** Bound owner-only diagnostic evidence, without creating synthetic replies. */
    fun appendRawObdExchanges(state: FunctionalTestState, actual: List<String>): FunctionalTestState =
        state.copy(rawObdExchanges = (state.rawObdExchanges + actual.map { it.take(8192) }).take(128))

    /** A physical VIN read and matching the selected profile are separate evidence. */
    fun withObservedIdentity(state: FunctionalTestState, observedVin: String?): FunctionalTestState {
        val matcher = com.camarodiag.gen5.domain.model.VehicleVinMatcher
        val context = state.context ?: return state
        val comparison = matcher.compare(context.selectedVin, observedVin)
        val compatible = comparison?.matches == true && matcher.isCamaroCandidate(comparison.ecuVin)
        val rows = state.checks.filterNot {
            it.key == "service:vin-profile-match" || (!compatible && it.group == "ecu")
        }
        val match = comparison?.matches
        val identity = if (matcher.normalize(context.selectedVin) != null) listOf(
            FunctionalTestCheck(
                "service:vin-profile-match", "service",
                "Selected vehicle matches physical VIN", "Véhicule sélectionné conforme au VIN physique",
                when (match) {
                    true -> FunctionalTestOutcome.PASSED
                    false -> FunctionalTestOutcome.FAILED
                    null -> FunctionalTestOutcome.NOT_TESTED
                },
                when (match) {
                    true -> "Physical VIN matches the selected profile."
                    false -> "Physical VIN differs from the selected profile; OEM topology omitted."
                    null -> "Physical VIN not verified; OEM topology omitted."
                },
                when (match) {
                    true -> "Le VIN physique correspond au profil sélectionné."
                    false -> "VIN physique différent du profil sélectionné ; topologie constructeur omise."
                    null -> "VIN physique non vérifié ; topologie constructeur omise."
                }
            )
        ) else emptyList()
        return state.copy(context = context.copy(observedVin = comparison?.ecuVin), checks = rows + identity)
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

    /** Reuse physical test evidence only for the current vehicle/connection, never a previous adapter. */
    fun currentResponders(state: FunctionalTestState, connectionGeneration: Long, selectedVin: String): List<String> {
        val context = state.context ?: return emptyList()
        if (state.running || state.finishedAtMillis == null || state.error != null ||
            context.connectionGeneration != connectionGeneration || context.selectedVin != selectedVin) return emptyList()
        return state.checks.asSequence()
            .filter { it.group == "obd_responder" && it.key.startsWith("obd_responder:") &&
                it.outcome == FunctionalTestOutcome.PASSED }
            .map { it.key.substringAfter(':') }
            .filter { it.isNotBlank() }
            .distinct().sorted().toList()
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
