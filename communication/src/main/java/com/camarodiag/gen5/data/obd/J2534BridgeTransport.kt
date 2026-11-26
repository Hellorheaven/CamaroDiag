package com.camarodiag.gen5.data.obd

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.util.Locale

/** Explicitly unsupported legacy GM 0x1A identity, never a malformed/denied response. */
internal class GmlanIdentityUnavailableException :
    IllegalStateException("The ECU does not expose a supported legacy GM VIN/OSID.")

/** The authenticated Windows J2534 bridge revoked its physical session. */
internal class J2534SessionRevokedException(message: String) : java.io.IOException(message)

/**
 * Certificate-pinned TLS (JSON lines) client for the Windows J2534 bridge.
 * J2534 performs ISO-TP framing on the PC; this class exchanges complete UDS payloads.
 */
class J2534BridgeTransport(
    private val host: String,
    private val port: Int,
    private val pairingToken: String
) : UdsPayloadTransport {
    private val mutex = Mutex()
    private val tlsPairing by lazy { J2534BridgeTlsPairing.parse(pairingToken) }
    @Volatile private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    @Volatile private var opened = false
    private var connectedResponseCanId: String? = null
    private var standardObdAdapterInfo: Map<String, String> = emptyMap()
    @Volatile private var scopedMemoryReadEnabled = false
    @Volatile private var approvedMemoryReadScope: J2534ApprovedMemoryScope? = null
    @Volatile private var standardObdPhysicalReadEnabled = false
    @Volatile private var standardObdClearEnabled = false
    @Volatile private var rawUdsConsoleAvailable = false

    /** Explicit raw ECU requests on the paired J2534 ISO-TP channel. */
    val isRawUdsConsoleAvailable: Boolean
        get() = isConnected && rawUdsConsoleAvailable
    val isStandardObdClearEnabled: Boolean
        get() = isStandardObdPhysicalReadEnabled && standardObdClearEnabled
    val isStandardObdPhysicalReadEnabled: Boolean
        get() = isConnected && standardObdPhysicalReadEnabled

    /** True only when the authenticated PC bridge confirmed a VIN-bound memory scope. */
    val isScopedMemoryReadEnabled: Boolean
        get() = isConnected && scopedMemoryReadEnabled
    /** Close requested by the app, not a spontaneous I/O failure of the bridge. */
    @Volatile private var externallyClosed = false
    // A Boolean alone can lose a Close arriving between a blocked TLS
    // handshake and the next connection's initial state reset.
    private val explicitCloseEpoch = java.util.concurrent.atomic.AtomicLong(0L)

    /** Reflects a live TCP session rather than just an allocated transport object. */
    val isConnected: Boolean
        get() = opened && socket?.let {
            it.isConnected && !it.isClosed && !it.isInputShutdown && !it.isOutputShutdown
        } == true

    suspend fun connect(
        requestCanId: Int,
        responseCanId: Int,
        can29Bit: Boolean,
        protocolNumber: Int = 33
    ): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            // Validate before disconnecting an existing usable bridge.
            // Invalid profile, unpinned token or non-LAN target cannot tear
            // down an established diagnostic connection.
            require(host.isNotBlank()) { "Adresse IP du PC manquante." }
            require(port in 1024..65535) { "Port du pont PC invalide." }
            // Parse before network I/O: legacy token-only pairing must fail closed.
            val pairing = tlsPairing
            require(protocolNumber in setOf(33, 34, 35, 36, 63, 64)) { "Profil CAN J2534 non pris en charge." }
            val maxId = if (can29Bit) 0x1FFFFFFF else 0x7FF
            require(requestCanId in 0..maxId && responseCanId in 0..maxId && requestCanId != responseCanId) {
                "Identifiants CAN incompatibles avec le format sélectionné."
            }
            require(can29Bit == (protocolNumber in setOf(34, 36, 64))) {
                "La largeur des identifiants CAN ne correspond pas au profil sélectionné."
            }
            pairing.localBridgeIpv4(host) // no DNS or public network target
            val closeEpoch = explicitCloseEpoch.get()
            closeInternal()
            externallyClosed = false
            try {
                // TLS handshake (including SHA-256 certificate pin verification)
                // completes BEFORE the first pairing token or UDS command is sent.
                val connected = pairing.openSocket(host, port)
                socket = connected
                // TLS handshake is synchronous: cancellation or app Close can
                // race it. No pairing bearer token or CAN "open" request may
                // be sent after the operator switched away from this bridge.
                J2534BridgeHandshakeAdmission.requireCurrent(
                    currentCoroutineContext().isActive, externallyClosed,
                    closeEpoch, explicitCloseEpoch.get()
                )
                connected.soTimeout = 12_000
                reader = BufferedReader(InputStreamReader(connected.getInputStream(), Charsets.UTF_8))
                writer = BufferedWriter(OutputStreamWriter(connected.getOutputStream(), Charsets.UTF_8))
                val response = request(
                    JSONObject()
                        .put("op", "open")
                        .put("token", tlsPairing.token)
                        .put("requestCanId", requestCanId.toString(16).uppercase().padStart(if (can29Bit) 8 else 3, '0'))
                        .put("responseCanId", responseCanId.toString(16).uppercase().padStart(if (can29Bit) 8 else 3, '0'))
                        .put("can29Bit", can29Bit)
                        .put("protocolNumber", protocolNumber),
                    10_000
                )
                // Even a valid reply can arrive after cancellation: do not
                // publish capabilities for a closed/superseded TLS session.
                J2534BridgeHandshakeAdmission.requireCurrent(
                    currentCoroutineContext().isActive, externallyClosed,
                    closeEpoch, explicitCloseEpoch.get()
                )
                val requestLabel = requestCanId.toString(16).uppercase().padStart(if (can29Bit) 8 else 3, '0')
                val responseLabel = responseCanId.toString(16).uppercase().padStart(if (can29Bit) 8 else 3, '0')
                check(response.opt("readOnly") == true &&
                    response.optString("requestCanId") == requestLabel &&
                    response.optString("responseCanId") == responseLabel &&
                    response.optInt("protocolNumber", -1) == protocolNumber
                ) { "The PC bridge did not confirm the selected read-only CAN channel." }
                connectedResponseCanId = responseLabel
                rawUdsConsoleAvailable = response.opt("rawUdsConsoleAvailable") == true
                val memoryAllowed = response.opt("scopedPhysicalMemoryRead") == true
                approvedMemoryReadScope = if (memoryAllowed) runCatching {
                    // Older or malformed PC bridges may not supply physical
                    // profile evidence. Refuse memory privileges rather than
                    // trusting a boolean and re-labelling an unrelated span.
                    fun exactNumber(key: String): Long {
                        val value = response.opt(key)
                        require(value is Number &&
                            value.toDouble() == value.toLong().toDouble()) {
                            "PC bridge supplied an invalid J2534 memory profile: $key."
                        }
                        return value.toLong()
                    }
                    fun exactSmallNumber(key: String): Int {
                        val value = exactNumber(key)
                        require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                            "PC bridge supplied an out-of-range memory profile: $key."
                        }
                        return value.toInt()
                    }
                    val vin = response.opt("memoryReadVin") as? String
                        ?: error("The PC bridge must advertise its approved VIN-bound memory profile.")
                    val region = response.opt("memoryReadRegion") as? String
                        ?: error("The PC bridge did not advertise its approved ECU memory region.")
                    J2534ApprovedMemoryScope(
                        vin = vin,
                        region = EcuBackupCoverage.Region.valueOf(region),
                        startAddress = exactNumber("memoryReadStartAddress"),
                        lengthBytes = exactNumber("memoryReadLengthBytes"),
                        maxReadBytes = exactSmallNumber("memoryReadMaxReadBytes"),
                        diagnosticSession = exactSmallNumber("memoryReadSession")
                    )
                }.getOrNull() else null
                scopedMemoryReadEnabled = approvedMemoryReadScope != null
                standardObdPhysicalReadEnabled = response.opt("standardObdPhysicalRead") == true &&
                    J2534StandardObd.supportsChannel(requestCanId, responseCanId, protocolNumber)
                standardObdClearEnabled = standardObdPhysicalReadEnabled &&
                    J2534StandardObd.supportsPrimaryChannel(requestCanId, responseCanId, protocolNumber) &&
                    response.opt("standardObdClear") == true
                opened = true
                val adapter = response.optString("adapter", "J2534")
                val voltage = response.optLong("batteryMillivolts", -1L)
                standardObdAdapterInfo = buildMap {
                    put("IDENTIFICATION", adapter)
                    put("PROTOCOL", "J2534 / ISO 15765-4 • " + response.optInt("baudRate") + " bit/s")
                    if (voltage >= 0) put("VOLTAGE", String.format(Locale.ROOT, "%.2f V", voltage / 1000.0))
                }
                val voltageLabel = if (voltage >= 0) {
                    String.format(Locale.getDefault(), " • %.2f V", voltage / 1000.0)
                } else ""
                val backupWarning = if (memoryAllowed && approvedMemoryReadScope == null)
                    " • Sauvegarde ECU désactivée : profil de lecture non confirmé par ce pont PC."
                    else ""
                "$adapter • ${response.optString("protocol", "ISO15765")} • ${response.optInt("baudRate")} bit/s$voltageLabel$backupWarning"
            } catch (error: Exception) {
                closeInternal()
                throw error
            }
        }
    }

    /** Refuse an unapproved ECU span before issuing VIN, OSID or memory commands. */
    internal fun requireApprovedMemoryReadScope(
        request: J2534LiveMemoryBackup.Request
    ): J2534LiveMemoryBackup.Request {
        val scope = approvedMemoryReadScope
        check(isScopedMemoryReadEnabled && scope != null) {
            "A current Windows J2534 bridge with an approved memory read profile is required."
        }
        require(scope.permits(request)) {
            "ECU VIN, memory range, region or session differs from the PC read profile."
        }
        return scope.constrain(request)
    }

    suspend fun readCalibrationIdentification(): ObdEcuIdentification = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureOpen()
            val data = request(JSONObject().put("op", "identify").put("token", tlsPairing.token), 15_000)
                .getJSONObject("identity")
            fun records(name: String): List<String> {
                val values = data.getJSONArray(name)
                return List(values.length()) { index -> values.getString(index) }
            }
            J2534StandardObd.validateIdentity(
                vin = if (data.isNull("vin")) null else data.getString("vin"),
                calibrationIds = records("calibrationIds"),
                calibrationVerificationNumbers = records("cvns"),
                ecuNames = records("ecuNames"),
                responseCanId = data.getString("responseCanId"),
                expectedResponseCanId = checkNotNull(connectedResponseCanId)
            )
        }
    }

    suspend fun readGmlanIdentification(): GmlanIdentityEvidence = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureOpen()
            val expected = checkNotNull(connectedResponseCanId)
            val response = request(
                JSONObject().put("op", "identifyGmlan").put("token", tlsPairing.token),
                15_000
            )
            if (response.opt("identityAvailable") == false) {
                check(response.opt("readOnly") == true &&
                    response.opt("flashQualified") == false &&
                    response.optString("responseCanId") == expected) {
                    "The PC bridge returned an untrusted optional GMLAN identity response."
                }
                throw GmlanIdentityUnavailableException()
            }
            val data = response.getJSONObject("identity")
            fun integer(name: String): Long {
                val value = data.opt(name)
                require(value is Number && value.toDouble() == value.toLong().toDouble()) {
                    "Invalid GM numeric identity field."
                }
                return value.toLong()
            }
            val did = integer("OsDid")
            require(did in 0L..255L)
            GmlanIdentityEvidence.validate(
                vin = data.getString("Vin"),
                osId = integer("OperatingSystemId"),
                osDid = did.toInt(),
                responseId = response.getString("responseCanId"),
                expectedResponseId = expected,
                physicalRead = data.opt("PhysicalRead") == true,
                readOnly = response.opt("readOnly") == true,
                flashQualified = response.opt("flashQualified") != false
            )
        }
    }

    suspend fun discoverStandardObdPids(): Set<Int> {
        check(isStandardObdPhysicalReadEnabled)
        val supported = linkedSetOf<Int>()
        for (base in 0..0xE0 step 32) {
            val response = exchange(byteArrayOf(1, base.toByte()), 2500)
            val (pids, next) = J2534StandardObd.supportPage(base, response)
            supported.addAll(pids)
            if (!next) break
        }
        return supported
    }

    suspend fun readStandardObdVin(): String? {
        check(isStandardObdPhysicalReadEnabled)
        val reply = exchange(byteArrayOf(9, 2), 3500)
        check(isStandardObdPhysicalReadEnabled)
        return J2534StandardObd.vinData(reply)
    }

    suspend fun readStandardObdPid(pid: Int): String {
        check(isStandardObdPhysicalReadEnabled)
        require(pid in 1..255)
        val reply = exchange(byteArrayOf(1, pid.toByte()), 1800)
        // Validate service and PID before delivering the complete payload to
        // the existing telemetry decoder. No fabricated adapter text.
        J2534StandardObd.pidData(pid, reply)
        return reply.toHex()
    }

    /**
     * One operator-requested, non-destructive SAE OBD command on the currently
     * selected physical J2534 responder. No ELM commands, write services, or
     * fallback to demonstration data are accepted on this path.
     */
    suspend fun readOnlyConsoleRequest(command: SafeObdRequest): String {
        check(isStandardObdPhysicalReadEnabled) {
            "J2534 physical standard OBD reading is not enabled."
        }
        val payload = readOnlyConsolePayload(command)
        val reply = exchange(payload, 4_000L)
        // An exact negative ECU acknowledgement belongs in RX history as a
        // failed request. All unrelated, corrupted, and truncated replies fail.
        if (!isMatchingNegativeConsoleReply(command, reply)) {
            validateReadOnlyConsoleReply(command, reply)
        }
        check(isStandardObdPhysicalReadEnabled) {
            "J2534 disconnected before the physical console response was accepted."
        }
        // exchange() already verified the configured physical response ID;
        // retain it alongside the assembled payload for console provenance.
        return checkNotNull(connectedResponseCanId) + " " + reply.toHex()
    }

    internal companion object {
        fun readOnlyConsolePayload(command: SafeObdRequest): ByteArray {
            require(SafeObdRequestPolicy.parse(command.elmCommand) == command) {
                "Unrecognized or modified read-only console command."
            }
            // SafeObdRequest belongs to another Gradle module: Kotlin cannot
            // smart-cast its public nullable property across module boundaries.
            val parameter = command.parameter
            return if (parameter == null) {
                byteArrayOf(command.service.toByte())
            } else {
                byteArrayOf(command.service.toByte(), parameter.toByte())
            }
        }

        fun isMatchingNegativeConsoleReply(command: SafeObdRequest, reply: ByteArray): Boolean =
            SafeObdRequestPolicy.parse(command.elmCommand) == command &&
                reply.size == 3 &&
                (reply[0].toInt() and 0xFF) == 0x7F &&
                (reply[1].toInt() and 0xFF) == command.service &&
                (reply[2].toInt() and 0xFF) !in setOf(0, 0x78)

        /** A negative, mismatched, or truncated reply is never a successful read. */
        fun validateReadOnlyConsoleReply(command: SafeObdRequest, reply: ByteArray) {
            require(SafeObdRequestPolicy.parse(command.elmCommand) == command) {
                "Unsupported console read request."
            }
            when (command.service) {
                0x01 -> {
                    val pid = requireNotNull(command.parameter)
                    if (pid % 0x20 == 0) J2534StandardObd.supportPage(pid, reply)
                    else J2534StandardObd.pidData(pid, reply)
                }
                0x03, 0x07, 0x0A -> {
                    requireNotNull(J2534StandardObd.optionalDtcs(command.service, reply)) {
                        "The ECU does not support this DTC read mode."
                    }
                }
                0x09 -> {
                    require(SafeObdRequestPolicy.hasUnambiguousPositiveResponse(
                        command,
                        reply.joinToString("") { "%02X".format(Locale.ROOT, it.toInt() and 0xFF) }
                    )) { "Physical Mode 09 identification is incomplete or invalid." }
                }
                else -> throw IllegalArgumentException("Not a read-only OBD console service.")
            }
        }
    }

    suspend fun readStandardObdDtcs(): PhysicalObdDtcSnapshot {
        check(isStandardObdPhysicalReadEnabled)
        // An absent/refused read is not equivalent to a confirmed empty list.
        suspend fun read(mode: Int): List<ObdDtc>? =
            J2534StandardObd.evidenceDtcs(mode, exchange(byteArrayOf(mode.toByte()), 2500))
        return PhysicalObdDtcSnapshot(read(3), read(7), read(10))
    }

    /** Physical scan uses only exact read payloads, never AT commands. */
    suspend fun fullStandardObdScan(): ObdScanReport {
        check(isStandardObdPhysicalReadEnabled)
        val responder = checkNotNull(connectedResponseCanId)
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, timeout ->
            require(command.matches(Regex("[0-9A-F]{2,6}")) && command.length % 2 == 0)
            val payload = command.hexToBytes()
            val mode = payload[0].toInt() and 0xFF
            require(when (mode) {
                1 -> payload.size == 2
                2 -> payload.size == 3 && payload[2] == 0.toByte()
                3,7,10 -> payload.size == 1
                6 -> payload.size == 2
                9 -> payload.size == 2
                else -> false
            }) { "Unsupported physical OBD scan command." }
            val reply = exchange(payload, timeout)
            if (J2534StandardObd.isExplicitlyUnsupported(mode, reply) &&
                !(mode == 1 && payload[1] == 0.toByte())) {
                // Keep the actual negative response in the scan log; the
                // read decoder treats the requested service as unavailable.
                reply.toHex()
            } else {
                require(reply.isNotEmpty() && (reply[0].toInt() and 0xFF) == mode + 0x40) {
                    "Negative or invalid response during physical OBD scan."
                }
                if (mode == 1) {
                    val pid = payload[1].toInt() and 0xFF
                    if (pid % 32 == 0) J2534StandardObd.supportPage(pid,reply)
                    else J2534StandardObd.pidData(pid,reply)
                }
                reply.toHex()
            }
        })
        return engine.fullPhysicalCanScan(responder, standardObdAdapterInfo) { mode, raw ->
            J2534StandardObd.evidenceDtcs(mode, raw.hexToBytes())
        }
    }

    suspend fun clearStandardObdDtcs(
        approval: EcuWriteConsentFlow.Approval,
        beforeTransmit: () -> Unit
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(approval.warningAcknowledged && approval.confirmedYes) {
                "Risk notice OK and subsequent YES required before ECU clear."
            }
            check(isStandardObdClearEnabled) { "Local Windows operator has not enabled OBD clear." }
            // A queued bridge request must not reuse operator consent after
            // logout, vehicle selection changes or an adapter transition.
            beforeTransmit()
            ensureOpen()
            val response = request(JSONObject()
                .put("op", "clearStandardObdDtcs")
                .put("token", tlsPairing.token)
                .put("warningAcknowledged", approval.warningAcknowledged)
                .put("writeConfirmed", approval.confirmedYes)
                .put("confirmed", true), 12_000)
            check(response.optString("payloadHex") == "44" &&
                response.optString("responseCanId") == connectedResponseCanId &&
                response.opt("permanentDtcsCleared") == false) {
                "Physical emissions reset was not confirmed."
            }
        }
    }

    override suspend fun exchange(payload: ByteArray, timeoutMs: Long): ByteArray = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureOpen()
            require(payload.size in 1..J2534BridgeProtocol.MAX_PAYLOAD_BYTES) { "La requête UDS doit contenir entre 1 et 4091 octets." }
            val boundedTimeout = J2534BridgeProtocol.boundedTimeoutMs(timeoutMs)
            socket?.soTimeout = boundedTimeout + 3000
            val response = request(
                JSONObject()
                    .put("op", "exchange")
                    .put("token", tlsPairing.token)
                    .put("payloadHex", payload.toHex())
                    .put("timeoutMs", boundedTimeout),
                boundedTimeout + 3000
            )
            val hex = response.optString("payloadHex")
            if (hex.isBlank() || hex.length % 2 != 0 || hex.length > J2534BridgeProtocol.MAX_PAYLOAD_BYTES * 2 ||
                !hex.all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }
            ) {
                closeInternal()
                throw IllegalStateException("Réponse UDS vide, trop longue ou mal formée du pont J2534.")
            }
            hex.hexToBytes()
        }
    }

    override suspend fun sendOnly(payload: ByteArray, timeoutMs: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureOpen()
            require(payload.size in 1..J2534BridgeProtocol.MAX_PAYLOAD_BYTES) { "La requête UDS doit contenir entre 1 et 4091 octets." }
            val boundedTimeout = timeoutMs.coerceIn(100L, 10_000L).toInt()
            request(
                JSONObject()
                    .put("op", "sendOnly")
                    .put("token", tlsPairing.token)
                    .put("payloadHex", payload.toHex())
                    .put("timeoutMs", boundedTimeout),
                boundedTimeout + 3000
            )
            Unit
        }
    }

    /** An explicitly requested ECU service, without a CamaroDiag-specific
     * service allowlist. The existing TLS pairing and J2534 channel still apply.
     */
    suspend fun exchangeOperatorUds(
        payload: ByteArray, timeoutMs: Long, approval: EcuWriteConsentFlow.Approval,
        beforeTransmit: () -> Unit
    ): ByteArray =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                ensureOpen()
                check(rawUdsConsoleAvailable) {
                    "The connected PC bridge does not implement raw UDS exchange."
                }
                require(approval.warningAcknowledged && approval.confirmedYes) {
                    "ECU risk warning OK and separate YES confirmation required."
                }
                require(payload.size in 1..J2534BridgeProtocol.MAX_PAYLOAD_BYTES)
                // Authorization must be current AFTER transport queue admission,
                // immediately before the paired TLS bridge request is sent.
                beforeTransmit()
                ensureOpen()
                val boundedTimeout = J2534BridgeProtocol.boundedTimeoutMs(timeoutMs)
                val response = request(
                    JSONObject().put("op", "exchangeRaw")
                        .put("token", tlsPairing.token)
                        .put("warningAcknowledged", approval.warningAcknowledged)
                        .put("writeConfirmed", approval.confirmedYes)
                        .put("payloadHex", payload.toHex())
                        .put("timeoutMs", boundedTimeout),
                    boundedTimeout + 3000
                )
                val hex = response.optString("payloadHex")
                if (hex.isBlank() || hex.length % 2 != 0 ||
                    hex.length > J2534BridgeProtocol.MAX_PAYLOAD_BYTES * 2 ||
                    !hex.all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }) {
                    closeInternal()
                    throw IllegalStateException("Invalid physical ECU response from J2534.")
                }
                hex.hexToBytes()
            }
        }

    suspend fun sendOperatorUdsOnly(
        payload: ByteArray, approval: EcuWriteConsentFlow.Approval,
        beforeTransmit: () -> Unit
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureOpen()
            check(rawUdsConsoleAvailable) {
                "The connected PC bridge does not implement raw UDS send."
            }
            require(approval.warningAcknowledged && approval.confirmedYes) {
                "ECU risk warning OK and separate YES confirmation required."
            }
            require(payload.size in 1..J2534BridgeProtocol.MAX_PAYLOAD_BYTES)
            beforeTransmit()
            ensureOpen()
            request(
                JSONObject().put("op", "sendRaw")
                    .put("token", tlsPairing.token)
                    .put("warningAcknowledged", approval.warningAcknowledged)
                    .put("writeConfirmed", approval.confirmedYes)
                    .put("payloadHex", payload.toHex()),
                13_000
            )
            Unit
        }
    }

    private fun request(json: JSONObject, timeoutMs: Int): JSONObject {
        val activeSocket = socket ?: error("Pont PC J2534 non connecté.")
        activeSocket.soTimeout = timeoutMs.coerceIn(1000, 15_000)
        val output = writer ?: error("Canal d'écriture du pont PC indisponible.")
        val input = reader ?: error("Canal de lecture du pont PC indisponible.")
        val line = try {
            output.write(json.toString())
            output.newLine()
            output.flush()
            J2534BridgeProtocol.readBoundedLine(input)
                ?: throw java.io.EOFException("Le pont PC a fermé la connexion.")
        } catch (error: Exception) {
            val wasClosedByApp = externallyClosed
            closeInternal()
            // Closing the old PC bridge intentionally during a reconnect can
            // break a blocked read. Do not report that error against the new bridge.
            if (wasClosedByApp) throw ObdSupersededSessionException(error)
            throw error
        }
        val response = try {
            JSONObject(line)
        } catch (error: Exception) {
            closeInternal()
            throw IllegalStateException("Le pont J2534 a retourné une réponse JSON invalide.", error)
        }
        if (!response.optBoolean("ok", false)) {
            val reason = response.optString("error", "Erreur inconnue du pont J2534.")
            if (J2534BridgeProtocol.isTerminalFailureFlag(response.opt("sessionTerminated"))) {
                closeInternal()
                throw J2534SessionRevokedException(reason)
            }
            throw IllegalStateException(reason)
        }
        return response
    }

    private fun ensureOpen() {
        check(isConnected) {
            "Connecte d'abord le pont PC J2534."
        }
    }

    fun close() {
        // Publish closure before tearing down sockets. A late TLS handshake
        // cannot reset the Boolean and resurrect this explicitly closed link.
        explicitCloseEpoch.incrementAndGet()
        externallyClosed = true
        closeInternal()
    }

    private fun closeInternal() {
        // Closing the TCP socket is the release signal; the PC server disposes the J2534
        // channel when it observes EOF. Avoid a blocking read on the Android UI thread.
        runCatching { socket?.close() }
        runCatching { reader?.close() }
        runCatching { writer?.close() }
        reader = null
        writer = null
        socket = null
        connectedResponseCanId = null
        standardObdAdapterInfo = emptyMap()
        opened = false
        scopedMemoryReadEnabled = false
        approvedMemoryReadScope = null
        standardObdPhysicalReadEnabled = false
        standardObdClearEnabled = false
        rawUdsConsoleAvailable = false
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02X".format(Locale.ROOT, it.toInt() and 0xFF) }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

/** Pure fail-closed admission used after blocking TLS I/O and before publication. */
internal object J2534BridgeHandshakeAdmission {
    fun requireCurrent(
        coroutineActive: Boolean, closedByApp: Boolean,
        capturedCloseEpoch: Long, currentCloseEpoch: Long
    ) {
        if (!coroutineActive) {
            throw kotlinx.coroutines.CancellationException(
                "J2534 TLS connection was cancelled before admission"
            )
        }
        check(!closedByApp && currentCloseEpoch == capturedCloseEpoch) {
            "J2534 bridge connection was closed during the TLS handshake"
        }
    }
}

/** Bounded framing helpers for the untrusted JSON-lines PC bridge stream. */
internal object J2534BridgeProtocol {
    const val MAX_PAYLOAD_BYTES = 4091

    fun isTerminalFailureFlag(value: Any?): Boolean = value is Boolean && value
    private const val MAX_RESPONSE_LINE_CHARS = 32 * 1024

    fun boundedTimeoutMs(timeoutMs: Long): Int = timeoutMs.coerceIn(100L, 10_000L).toInt()

    fun readBoundedLine(input: BufferedReader): String? {
        val line = StringBuilder()
        while (true) {
            val next = input.read()
            if (next == -1) {
                if (line.isEmpty()) return null
                throw java.io.EOFException("Incomplete J2534 response frame.")
            }
            if (next == '\n'.code) break
            if (next == '\r'.code) {
                if (input.read() != '\n'.code)
                    throw java.io.IOException("Invalid J2534 response frame terminator.")
                break
            }
            // JSON control bytes must be escaped inside strings. The Windows
            // bridge emits one compact UTF-8 JSON object per line; accepting
            // raw NUL/ESC/TAB would make its authenticated framing ambiguous.
            if (next < 0x20) {
                throw java.io.IOException("Invalid control character in J2534 bridge response.")
            }
            check(line.length < MAX_RESPONSE_LINE_CHARS) {
                "The J2534 bridge response exceeds the maximum allowed line size."
            }
            line.append(next.toChar())
        }
        return line.toString()
    }
}
