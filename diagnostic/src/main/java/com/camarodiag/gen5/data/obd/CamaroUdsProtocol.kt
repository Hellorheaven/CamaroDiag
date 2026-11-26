package com.camarodiag.gen5.data.obd

import java.util.Locale

import com.camarodiag.gen5.domain.model.*

object CamaroUdsProtocol {

    fun evaluateAdapterProbe(
        deviceName: String,
        macAddress: String,
        atzResp: String,
        stiResp: String,
        atalResp: String,
        stp61Resp: String,
        headersResp: String = "OK",
        protocolResp: String = "OK",
        swCanWiringVerified: Boolean = false,
        transmitProbeResp: String = "",
        hardwareResp: String = "",
        elmProtocolProbe: Map<Int, String> = emptyMap(),
        stnProtocolProbe: Map<Int, String> = emptyMap(),
        selectedElmProtocol: Int = 6,
        reportedElmProtocol: Int? = selectedElmProtocol.takeIf { it in 1..10 },
        protocolNumberResponse: String = ""
    ): AdapterCapabilities {
        require(selectedElmProtocol in 0..10)
        require(reportedElmProtocol == null || reportedElmProtocol in 1..10)
        val acceptedElm = elmProtocolProbe.filter { (number, answer) ->
            number in 1..10 && hasStandaloneOk(answer)
        }.keys.toSet() + (if (selectedElmProtocol != 0 && hasStandaloneOk(protocolResp))
            setOf(selectedElmProtocol) else emptySet())
        val verifiedCan = reportedElmProtocol != null && reportedElmProtocol in 6..9 &&
            hasPositiveMode01Pid00Response(transmitProbeResp)
        val logs = listOf(
            "> ATZ  -> $atzResp",
            "> STI  -> $stiResp",
            "> STDI -> $hardwareResp",
            "> ATAL -> $atalResp",
            "> STP 61 -> $stp61Resp",
            "> ATH1 -> $headersResp",
            "> ATSP ${selectedElmProtocol.toString(16).uppercase(Locale.ROOT)} -> $protocolResp",
            "> 0100 (physical TX verification) -> $transmitProbeResp",
            "> ATDPN -> $protocolNumberResponse"
        ) + elmProtocolProbe.toSortedMap().map { (number, answer) ->
            "> ATTP${number.toString(16).uppercase(Locale.ROOT)} -> $answer"
        } + stnProtocolProbe.toSortedMap().map { (number, answer) -> "> STP $number -> $answer" }

        // Select STN-only monitor commands only when the adapter's firmware response
        // identifies an STN chipset. A Bluetooth name or the OBDLink product banner is
        // not sufficient: some adapters expose ELM-compatible firmware instead.
        val isStn = hasStnFirmwareBanner(stiResp) ||
            hasStnFirmwareBanner(atzResp)

        // A Bluetooth device name is user-editable. Use the firmware hardware
        // identification (STDI) or separately verified physical wiring.
        val mxModel = Regex("^OBDLink\\s+(MX\\+|MX)(?:\\s+.*)?$", RegexOption.IGNORE_CASE)
        val verifiedObdLinkMxHardware = hardwareResp.lineSequence().any { mxModel.matches(it.trim()) }
        val supportsSwCan = (swCanWiringVerified || verifiedObdLinkMxHardware) && isStn &&
            hasStandaloneOk(stp61Resp)
        val supportsLong = hasStandaloneOk(atalResp)

        val tier = when {
            isStn -> AdapterTier.PRO_STN
            supportsLong && !atzResp.contains("v2.1", ignoreCase = true) -> AdapterTier.GENUINE_ELM
            else -> AdapterTier.CLONE_ELM
        }

        return AdapterCapabilities(
            deviceName = deviceName,
            macAddress = macAddress,
            tier = tier,
            firmwareId = if (stiResp.isNotBlank() && !stiResp.contains("?")) stiResp else atzResp,
            supportsLongFrames = supportsLong,
            supportsSwCan = supportsSwCan,
            supportsCanHeaders = hasStandaloneOk(headersResp) &&
                (acceptedElm.any { it in 6..9 } || verifiedCan),
            rawProbeLog = logs,
            supportsCanTransmit = verifiedCan,
            hardwareId = hardwareResp.trim(),
            reportedElmProtocol = reportedElmProtocol,
            acceptedElmProtocols = acceptedElm + (if (verifiedCan) setOfNotNull(reportedElmProtocol) else emptySet()),
            acceptedStnProtocols = if (isStn) stnProtocolProbe.filter { (number, answer) ->
                number in setOf(61, 62) && supportsSwCan && hasStandaloneOk(answer)
            }.keys.toSet() + (if (supportsSwCan) setOf(61) else emptySet()) else emptySet()
        )
    }

    /** STN status must be a real firmware banner, never an arbitrary substring. */
    private fun hasStnFirmwareBanner(response: String): Boolean {
        val banner = Regex("^STN[0-9]{4}(?:\\s+v[0-9]+(?:\\.[0-9]+){1,3})?$", RegexOption.IGNORE_CASE)
        return response.lineSequence().any { banner.matches(it.trim()) }
    }

    /** Accept an adapter's isolated OK acknowledgement, not substrings such
     * as NOT OK or NOK in its diagnostic chatter. Echoes and prompts are allowed. */
    private fun hasStandaloneOk(response: String): Boolean {
        // The final non-prompt status must be OK; a stale OK followed by
        // ERROR, NO DATA or another rejection is not a capability grant.
        val lines = response.lineSequence()
            .map { it.trim().removeSuffix(">").trim() }
            .filter(String::isNotEmpty)
            .toList()
        return lines.lastOrNull()?.equals("OK", ignoreCase = true) == true &&
            lines.none { line ->
                line.equals("ERROR", ignoreCase = true) ||
                    line.equals("NO DATA", ignoreCase = true) ||
                    line.equals("?", ignoreCase = true) ||
                    line.equals("UNABLE TO CONNECT", ignoreCase = true)
            }
    }

    /**
     * Accept only a complete positive Mode 01 PID 00 ECU response. A substring
     * match is unsafe here: adapter banners, command echoes or diagnostic chatter
     * must never enable raw CAN transmission.
     */
    private fun hasPositiveMode01Pid00Response(response: String): Boolean {
        return parsePidBytes(response, "00")?.size == 4
    }

    fun buildTopology(vehicle: VehicleProfile, adapter: AdapterCapabilities): List<EcuModule> {
        // Standard powertrain OBD access depends on a selected CAN-capable adapter.
        // BCM/TCM/IPC/EBCM/SDM remain undiscovered until a VIN-specific profile exists.
        val standardObdAvailable =
            adapter.tier != AdapterTier.DISCONNECTED && adapter.supportsCanHeaders
        val ecmStatus = if (standardObdAvailable) {
            ModuleStatus.DISCOVERY_REQUIRED
        } else {
            ModuleStatus.UNSUPPORTED_BY_ADAPTER
        }
        val ecmTxHeader = if (standardObdAvailable) "7DF" else ""
        val ecmRxHeader = ""

        if (vehicle.generation == CamaroGeneration.GEN6) {
            return listOf(
                EcuModule("ECM", "ECM", "Engine Control Module (${vehicle.engine.ecmType})", "Calculateur moteur (${vehicle.engine.ecmType})", ecmTxHeader, ecmRxHeader, CanBusType.HS_CAN, ecmStatus, notesEn = "Standard OBD-II functional request/response example (7DF/7E8 on 11-bit CAN). This does not define a GM enhanced physical diagnostic address or authorize enhanced services.", notesFr = "Exemple de requête/réponse OBD-II fonctionnelle (7DF/7E8 sur CAN 11 bits). Ceci ne définit pas une adresse physique GM étendue et n'autorise pas les services constructeur."),
                EcuModule("TCM", "TCM", "Transmission Control Module", "Calculateur de transmission", "", "", CanBusType.UNKNOWN, if (vehicle.transmission == TransmissionType.MANUAL) ModuleStatus.NOT_EQUIPPED else ModuleStatus.DISCOVERY_REQUIRED, notesEn = "Module address and supported services must be discovered on the vehicle; not assumed from Gen 5 topology.", notesFr = "Adresse et services à découvrir sur le véhicule ; aucune adresse Gen 5 n'est réutilisée par hypothèse."),
                EcuModule("BCM", "BCM", "Body Control Module", "Calculateur habitacle", "", "", CanBusType.UNKNOWN, ModuleStatus.DISCOVERY_REQUIRED, notesEn = if (vehicle.modelYear <= 2018) "GM bulletin 07-08-49-020M lists the BCM as the Camaro odometer storage module through 2018. Diagnostic address and read DID still require VIN-specific verification." else "For 2019–2024, confirm the odometer storage architecture and diagnostic identifiers for this VIN before reading.", notesFr = if (vehicle.modelYear <= 2018) "Le bulletin GM 07-08-49-020M désigne le BCM comme module de stockage du kilométrage Camaro jusqu'en 2018. L'adresse de diagnostic et le DID de lecture restent à vérifier selon le VIN." else "Pour 2019–2024, confirmer l'architecture de stockage et les identifiants de diagnostic de ce VIN avant toute lecture."),
                EcuModule("IPC", "IPC", "Instrument Panel Cluster", "Combiné d'instruments", "", "", CanBusType.UNKNOWN, ModuleStatus.DISCOVERY_REQUIRED, notesEn = "Secondary odometer storage is described in 2012 Camaro service information; verify the exact module data and VIN relationship before comparing.", notesFr = "Le stockage secondaire du kilométrage est décrit dans la documentation d'atelier Camaro 2012 ; vérifier les données du module et la relation VIN avant comparaison."),
                EcuModule("EBCM", "ABS", "Electronic Brake Control Module", "Calculateur ABS / StabiliTrak", "", "", CanBusType.UNKNOWN, ModuleStatus.DISCOVERY_REQUIRED, notesEn = "Enhanced module scan not yet confirmed for this VIN.", notesFr = "Le balayage constructeur de ce module n'est pas encore confirmé pour ce VIN."),
                EcuModule("SDM", "SDM", "Sensing and Diagnostic Module", "Calculateur airbags", "", "", CanBusType.UNKNOWN, ModuleStatus.DISCOVERY_REQUIRED, notesEn = "Safety module access is read-only and vehicle-specific.", notesFr = "L'accès au calculateur de sécurité est spécifique au véhicule et en lecture seule.")
            ) + optionalModules()
        }
        val tcmStatus = if (vehicle.transmission == TransmissionType.AUTOMATIC) ModuleStatus.DISCOVERY_REQUIRED else ModuleStatus.NOT_EQUIPPED
        return listOf(
            EcuModule("ECM", "ECM", "Engine Control Module (${vehicle.engine.ecmType})", "Calculateur moteur (${vehicle.engine.ecmType})", ecmTxHeader, ecmRxHeader, CanBusType.HS_CAN, ecmStatus, notesEn = "Standard OBD-II functional request/response example (7DF/7E8 on 11-bit CAN). This does not define a GM enhanced physical diagnostic address or authorize enhanced services.", notesFr = "Exemple de requête/réponse OBD-II fonctionnelle (7DF/7E8 sur CAN 11 bits). Ceci ne définit pas une adresse physique GM étendue et n'autorise pas les services constructeur."),
            EcuModule("TCM", "TCM", "Transmission Control Module", "Calculateur de boîte", "", "", CanBusType.UNKNOWN, tcmStatus, notesEn = "Presence, address and supported data must be discovered on the vehicle.", notesFr = "Présence, adresse et données prises en charge à identifier sur le véhicule."),
            EcuModule("EBCM", "ABS", "Electronic Brake Control Module", "Calculateur ABS / StabiliTrak", "", "", CanBusType.UNKNOWN, ModuleStatus.DISCOVERY_REQUIRED, notesEn = "GM enhanced addressing is not assumed; perform a verified module scan.", notesFr = "L'adressage GM étendu n'est pas supposé ; effectuer une identification réelle du calculateur."),
            EcuModule("BCM", "BCM", "Body Control Module", "Calculateur habitacle", "", "", CanBusType.UNKNOWN, ModuleStatus.DISCOVERY_REQUIRED, notesEn = "GM bulletin 07-08-49-020I identifies the BCM as the Camaro odometer storage module for 2010–2015. Exact diagnostic addressing and read DID are not assumed.", notesFr = "Le bulletin GM 07-08-49-020I identifie le BCM comme module de stockage du kilométrage Camaro 2010–2015. L'adressage de diagnostic et le DID de lecture ne sont pas supposés."),
            EcuModule("IPC", "IPC", "Instrument Panel Cluster", "Combiné d'instruments", "", "", CanBusType.UNKNOWN, ModuleStatus.DISCOVERY_REQUIRED, notesEn = "2012 Camaro service information describes the IPC as secondary odometer storage. Exact read data and address must be verified.", notesFr = "La documentation Camaro 2012 décrit l'IPC comme stockage kilométrique secondaire. L'adresse et les données de lecture doivent être vérifiées."),
            EcuModule("SDM", "SDM", "Sensing and Diagnostic Module", "Calculateur airbags", "", "", CanBusType.UNKNOWN, ModuleStatus.DISCOVERY_REQUIRED, notesEn = "Safety module access is vehicle-specific and must remain read-only.", notesFr = "L'accès au calculateur de sécurité est spécifique au véhicule et doit rester en lecture seule.")
        ) + optionalModules()
    }

    /**
     * Optional equipment candidates are listed for discovery only. Their presence,
     * network, physical CAN identifiers and diagnostic services are never assumed.
     */
    private fun optionalModules(): List<EcuModule> {
        val discoveryNoteEn = "Optional equipment candidate only. Presence, network, physical address and supported services must be confirmed from a real vehicle scan and VIN-specific service information."
        val discoveryNoteFr = "Candidat d'équipement optionnel uniquement. Présence, réseau, adresse physique et services doivent être confirmés par un scan réel et la documentation correspondant au VIN."
        return listOf(
            EcuModule("HVAC", "HVAC", "Heating, Ventilation and Air Conditioning Control Module", "Calculateur de climatisation", "", "", CanBusType.UNKNOWN, ModuleStatus.DISCOVERY_REQUIRED, notesEn = discoveryNoteEn, notesFr = discoveryNoteFr),
            EcuModule("PSCM", "PSCM", "Power Steering Control Module", "Calculateur de direction assistée", "", "", CanBusType.UNKNOWN, ModuleStatus.DISCOVERY_REQUIRED, notesEn = discoveryNoteEn, notesFr = discoveryNoteFr),
            EcuModule("RCDLR", "RCDLR", "Remote Control Door Lock Receiver", "Récepteur de télécommande / accès mains libres", "", "", CanBusType.UNKNOWN, ModuleStatus.DISCOVERY_REQUIRED, notesEn = discoveryNoteEn, notesFr = discoveryNoteFr),
            EcuModule("RADIO", "RADIO", "Radio / Infotainment Control Module", "Calculateur radio / multimédia", "", "", CanBusType.UNKNOWN, ModuleStatus.DISCOVERY_REQUIRED, notesEn = discoveryNoteEn, notesFr = discoveryNoteFr),
            EcuModule("AMP", "AMP", "Audio Amplifier Control Module", "Calculateur amplificateur audio", "", "", CanBusType.UNKNOWN, ModuleStatus.DISCOVERY_REQUIRED, notesEn = discoveryNoteEn, notesFr = discoveryNoteFr),
            EcuModule("DDM", "DDM", "Driver Door Module", "Calculateur de porte conducteur", "", "", CanBusType.UNKNOWN, ModuleStatus.DISCOVERY_REQUIRED, notesEn = discoveryNoteEn, notesFr = discoveryNoteFr),
            EcuModule("PDM", "PDM", "Passenger Door Module", "Calculateur de porte passager", "", "", CanBusType.UNKNOWN, ModuleStatus.DISCOVERY_REQUIRED, notesEn = discoveryNoteEn, notesFr = discoveryNoteFr),
            EcuModule("FPCM", "FPCM", "Fuel Pump Control Module", "Calculateur de commande de pompe à carburant", "", "", CanBusType.UNKNOWN, ModuleStatus.DISCOVERY_REQUIRED, notesEn = discoveryNoteEn, notesFr = discoveryNoteFr),
            EcuModule("PARK_ASSIST", "PARK", "Park Assist Control Module", "Calculateur d'aide au stationnement", "", "", CanBusType.UNKNOWN, ModuleStatus.DISCOVERY_REQUIRED, notesEn = discoveryNoteEn, notesFr = discoveryNoteFr),
            EcuModule("HMI", "HMI", "Human Machine Interface Module", "Calculateur d'interface multimédia", "", "", CanBusType.UNKNOWN, ModuleStatus.DISCOVERY_REQUIRED, notesEn = discoveryNoteEn, notesFr = discoveryNoteFr)
        )
    }

    /**
     * A connected adapter is not proof that an ECU responded. Only mark the standard
     * OBD powertrain responder as accessible after observing 7E8 in a real 0100 scan.
     * This does not identify any GM enhanced physical address or other module.
     */
    fun applyStandardObdDiscovery(
        topology: List<EcuModule>,
        respondingAddresses: Collection<String>
    ): List<EcuModule> {
        val observedEcmResponse = respondingAddresses.any { it.trim().equals("7E8", ignoreCase = true) }
        return topology.map { module ->
            if (module.id != "ECM") return@map module
            if (observedEcmResponse) {
                module.copy(
                    status = ModuleStatus.ACCESSIBLE,
                    rxHeader = "7E8",
                    notesEn = "Observed 7E8 response to standard OBD functional request 0100. This confirms standard powertrain OBD access only; it does not authorize GM enhanced services.",
                    notesFr = "Réponse 7E8 observée à la requête OBD fonctionnelle standard 0100. Cela confirme uniquement l'accès OBD groupe motopropulseur ; aucun service GM étendu n'est autorisé."
                )
            } else {
                module.copy(
                    status = ModuleStatus.DISCOVERY_REQUIRED,
                    rxHeader = "",
                    notesEn = "No standard OBD 7E8 response was observed. Keep the ECM unverified until a real functional scan confirms it.",
                    notesFr = "Aucune réponse OBD standard 7E8 observée. Le calculateur moteur reste non confirmé jusqu'à une réponse réelle au scan fonctionnel."
                )
            }
        }
    }

    /**
     * Only interpret a complete, single-line positive Mode 01 response.
     * Adapter banners, echoed requests and neighbouring ECU replies cannot
     * contribute bytes to a sensor or an odometer calculation.
     */
    fun parsePidBytes(
        response: String,
        pid: String,
        primaryPowertrainResponderOnly: Boolean = false
    ): IntArray? = parsePidObservations(response, pid, primaryPowertrainResponderOnly)
        .firstOrNull()?.bytes?.toIntArray()

    data class PidObservation(val responder: String?, val bytes: List<Int>)

    /** Keep each validated Mode 01 response attached to its actual responder. */
    fun parsePidObservations(response: String, pid: String,
        primaryPowertrainResponderOnly: Boolean = false): List<PidObservation> =
        parseStandardPidRecords(response, pid, 0x41, null, primaryPowertrainResponderOnly)

    /** Mode 02 echoes a frame number after the PID, before the data. */
    fun parseFreezeFrameObservations(response: String, pid: Int, frame: Int = 0): List<PidObservation> {
        if (pid !in 0..255 || frame !in 0..255) return emptyList()
        return parseStandardPidRecords(response, "%02X".format(Locale.ROOT, pid), 0x42, frame, false)
    }

    private fun parseStandardPidRecords(response: String, pid: String, service: Int,
        frame: Int?, primaryPowertrainResponderOnly: Boolean): List<PidObservation> {
        val pidHex = pid.trim().uppercase(Locale.ROOT).padStart(2, '0')
        if (!Regex("[0-9A-F]{2}").matches(pidHex)) return emptyList()
        val observations = mutableListOf<PidObservation>()
        val expectedPid = pidHex.toInt(16)
        val prefix = if (frame == null) 2 else 3
        val expectedLength = if (frame != null && expectedPid in setOf(0x02, 0x03)) 2
            else SafeObdRequestPolicy.expectedMode01DataBytes(expectedPid)
        fun observation(responder: String?, bytes: List<Int>): PidObservation? {
            if (primaryPowertrainResponderOnly && responder != null &&
                responder !in setOf("7E8", "18DAF110")) return null
            if (bytes.size <= prefix || bytes[0] != service || bytes[1] != expectedPid ||
                (frame != null && bytes[2] != frame)) return null
            if (frame != null && expectedLength == null) return null
            if (expectedLength != null && bytes.size != expectedLength + prefix) return null
            return PidObservation(responder, bytes.drop(prefix))
        }
        val request = "%02X%02X".format(Locale.ROOT, service - 0x40, expectedPid) +
            (frame?.let { "%02X".format(Locale.ROOT, it) } ?: "")
        val cleaned = response.lineSequence().filter {
            it.filterNot(Char::isWhitespace).uppercase(Locale.ROOT) != request
        }.joinToString("\n")
        if (ObdNumberedIdentityFrames.hasSegmentedPhysical(cleaned)) {
            return ObdNumberedIdentityFrames.parseAddressedPhysical(cleaned).orEmpty()
                .mapNotNull { observation(it.responder, it.bytes) }
        }
        // Numbered ELM blocks have no reliable source address; never infer one.
        if (Regex("(?i)(?:^|\\s)[0-9A-F]:").containsMatchIn(cleaned)) {
            return ObdNumberedIdentityFrames.parseAll(cleaned).orEmpty()
                .mapNotNull { observation(null, it) }
        }
        // Already assembled payloads from physical providers may exceed one CAN frame.
        val hexPairData = Regex("(?:[0-9A-F]{2}){3,255}")
        // A Mode 01 reply must use a documented OBD responder header, not
        // arbitrary three hex characters that could be adapter chatter.
        val canHeader = Regex("(?:7E[8-F]|18DAF1[0-9A-F]{2})")
        val lines = response.lineSequence().flatMap { line ->
            // A compact journal/adapter can flatten several complete records.
            // Split only explicit header boundaries or compact positive records,
            // never individual hexadecimal bytes of a spaced payload.
            val headers = line.split(Regex("(?i)\\s+(?=(?:7E[8-F]|18DAF1[0-9A-F]{2})(?:\\s|[0-9A-F]{6,}))"))
            headers.asSequence().flatMap { row ->
                val tokens = row.trim().split(Regex("\\s+"))
                if (tokens.size > 1 && tokens.all {
                    it.matches(Regex("(?i)(?:[0-9A-F]{2}){3,10}")) &&
                        it.startsWith("%02X%02X".format(Locale.ROOT, service, expectedPid), ignoreCase = true)
                }) tokens.asSequence() else sequenceOf(row)
            }
        }
        for (sourceLine in lines) {
            val line = sourceLine.trim().removeSuffix(">").trimEnd().uppercase(Locale.ROOT)
            if (line.isEmpty()) continue
            val parts = line.split(Regex("\\s+"))
            val responder = when {
                parts.size > 1 && canHeader.matches(parts[0]) -> parts[0]
                parts.size == 1 && line.length % 2 == 1 &&
                    canHeader.matches(line.take(3)) -> line.take(3)
                parts.size == 1 && line.length >= 14 && line.length % 2 == 0 &&
                    canHeader.matches(line.take(8)) -> line.take(8)
                else -> null
            }
            // When physical CAN headers are available, never attribute another
            // ECU's response to primary powertrain telemetry. Headerless ELM
            // output still depends on the adapter's selected OBD channel.
            if (primaryPowertrainResponderOnly && responder != null &&
                responder !in setOf("7E8", "18DAF110")) continue
            val data = when {
                parts.size > 1 && canHeader.matches(parts[0]) -> {
                    val body = parts.drop(1)
                    if (body.first().length == 1 && body.first().all { it in '0'..'9' })
                        "0" + body.joinToString("")
                    else body.joinToString("")
                }
                // 11-bit (3 hex chars) or 29-bit (8 hex chars) CAN
                // responder header without spacing, never an arbitrary prefix.
                parts.size == 1 && line.length % 2 == 1 &&
                    canHeader.matches(line.take(3)) -> line.drop(3)
                parts.size == 1 && line.length >= 14 &&
                    line.length % 2 == 0 && canHeader.matches(line.take(8)) ->
                    line.drop(8)
                else -> parts.joinToString("")
            }
            if (!hexPairData.matches(data)) continue
            var bytes = data.chunked(2).map { it.toInt(16) }
            // Explicit DLC before a PCI byte must exactly describe the frame.
            if (responder != null && bytes.size in 4..9 &&
                bytes.first() == bytes.size - 1 && bytes[1] in 1..7 &&
                bytes[2] == service) bytes = bytes.drop(1)
            if (bytes.first() != service) {
                val declared = bytes.first()
                if (declared !in 1..8) continue
                bytes = when {
                    declared == bytes.size - 1 -> bytes.drop(1)
                    // Only an entire physical classic CAN frame can carry
                    // padding beyond its explicit ISO-TP single-frame length.
                    responder != null && bytes.size == 8 && declared in 1..7 &&
                        bytes[1] == service -> bytes.drop(1).take(declared)
                    else -> continue
                }
            }
            // A physical single-frame response cannot carry an unbounded payload.
            if (responder != null && bytes.size > 7) continue
            observation(responder, bytes)?.let(observations::add)
        }
        return observations
    }

    /**
     * SAE J1979 PID 0x43 absolute load is not limited to 100%.
     * Formula: (256*A + B) * 100/255; the maximum encoded value is 25700%.
     */
    fun decodeAbsoluteLoadPercent(bytes: IntArray?): Double? {
        if (bytes?.size != 2 || bytes.any { it !in 0..255 }) return null
        return ((bytes[0] shl 8) or bytes[1]) * 100.0 / 255.0
    }

    /** Commanded equivalence ratio PID 0x44 is (256*A + B) / 32768. */
    fun decodeCommandedEquivalenceRatio(bytes: IntArray?): Double? {
        if (bytes?.size != 2 || bytes.any { it !in 0..255 }) return null
        return ((bytes[0] shl 8) or bytes[1]) / 32768.0
    }

    /**
     * SAE wideband O2 PID 0x24..0x2B or 0x34..0x3B.
     * A/B encode lambda (2/65535); C/D encode either voltage (8/65535 V)
     * or signed current (1/256 mA offset by -128), depending on PID family.
     * Scaling overrides advertised through PID 0x4F require separate handling.
     */
    fun decodeWidebandLambda(bytes: IntArray?): Double? {
        if (bytes?.size != 4 || bytes.any { it !in 0..255 }) return null
        return ((bytes[0] shl 8) or bytes[1]) * 2.0 / 65535.0
    }

    fun decodeWidebandVoltage(bytes: IntArray?): Double? {
        if (bytes?.size != 4 || bytes.any { it !in 0..255 }) return null
        return ((bytes[2] shl 8) or bytes[3]) * 8.0 / 65535.0
    }

    fun decodeWidebandCurrent(bytes: IntArray?): Double? {
        if (bytes?.size != 4 || bytes.any { it !in 0..255 }) return null
        return ((bytes[2] shl 8) or bytes[3]) / 256.0 - 128.0
    }

    /** SAE J1979 Mode 01 PID 0x52 returns exactly one unsigned ethanol byte. */
    fun decodeEthanolPercent(bytes: IntArray?): Double? {
        val raw = bytes?.singleOrNull() ?: return null
        if (raw !in 0..255) return null
        return raw * 100.0 / 255.0
    }

    fun parseOdometerPidA6(response: String): Double? = parseOdometerPid(response, "A6")

    fun parseOdometerPidD3(response: String): Double? = parseOdometerPid(response, "D3")

    private fun parseOdometerPid(response: String, pid: String): Double? {
        val observations = parsePidObservations(response, pid)
        val bytes = observations.map { it.bytes }.distinct().singleOrNull() ?: return null
        // The four-byte definition is required; a truncated or concatenated
        // reply must never become a plausible vehicle mileage reading.
        if (bytes.size != 4) return null
        val value = bytes.fold(0L) { acc, byte -> (acc shl 8) or byte.toLong() }
        return value / 10.0
    }

}
