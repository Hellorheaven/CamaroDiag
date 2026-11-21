package com.camarodiag.gen5.data.obd

import kotlinx.coroutines.Dispatchers
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
    @Volatile private var scopedMemoryReadEnabled = false
    @Volatile private var standardObdPhysicalReadEnabled = false
    @Volatile private var standardObdClearEnabled = false
    val isStandardObdClearEnabled: Boolean
        get() = isStandardObdPhysicalReadEnabled && standardObdClearEnabled
    val isStandardObdPhysicalReadEnabled: Boolean
        get() = isConnected && standardObdPhysicalReadEnabled

    /** True only when the authenticated PC bridge confirmed a VIN-bound memory scope. */
    val isScopedMemoryReadEnabled: Boolean
        get() = isConnected && scopedMemoryReadEnabled
    /** Close requested by the app, not a spontaneous I/O failure of the bridge. */
    @Volatile private var externallyClosed = false

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
            closeInternal()
            externallyClosed = false
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
            try {
                // TLS handshake (including SHA-256 certificate pin verification)
                // completes BEFORE the first pairing token or UDS command is sent.
                val connected = pairing.openSocket(host, port)
                socket = connected
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
                val requestLabel = requestCanId.toString(16).uppercase().padStart(if (can29Bit) 8 else 3, '0')
                val responseLabel = responseCanId.toString(16).uppercase().padStart(if (can29Bit) 8 else 3, '0')
                check(response.opt("readOnly") == true &&
                    response.optString("requestCanId") == requestLabel &&
                    response.optString("responseCanId") == responseLabel &&
                    response.optInt("protocolNumber", -1) == protocolNumber
                ) { "The PC bridge did not confirm the selected read-only CAN channel." }
                connectedResponseCanId = responseLabel
                scopedMemoryReadEnabled = response.optBoolean("scopedPhysicalMemoryRead", false)
                standardObdPhysicalReadEnabled = response.opt("standardObdPhysicalRead") == true &&
                    J2534StandardObd.supportsChannel(requestCanId, responseCanId, protocolNumber)
                standardObdClearEnabled = standardObdPhysicalReadEnabled && response.opt("standardObdClear") == true
                opened = true
                val adapter = response.optString("adapter", "J2534")
                val voltage = response.optLong("batteryMillivolts", -1L)
                val voltageLabel = if (voltage >= 0) {
                    String.format(Locale.getDefault(), " • %.2f V", voltage / 1000.0)
                } else ""
                "$adapter • ${response.optString("protocol", "ISO15765")} • ${response.optInt("baudRate")} bit/s$voltageLabel"
            } catch (error: Exception) {
                closeInternal()
                throw error
            }
        }
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
            ObdEcuIdentification(
                vin = if (data.isNull("vin")) null else data.getString("vin"),
                calibrationIds = records("calibrationIds"),
                calibrationVerificationNumbers = records("cvns"),
                ecuNames = records("ecuNames"),
                respondingEcuAddresses = listOf(data.getString("responseCanId"))
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

    suspend fun readStandardObdPid(pid: Int): String {
        check(isStandardObdPhysicalReadEnabled)
        require(pid in 1..255)
        val reply = exchange(byteArrayOf(1, pid.toByte()), 1800)
        // Validate service and PID before delivering the complete payload to
        // the existing telemetry decoder. No fabricated adapter text.
        J2534StandardObd.pidData(pid, reply)
        return reply.toHex()
    }

    suspend fun readStandardObdDtcs(): Triple<List<ObdDtc>, List<ObdDtc>, List<ObdDtc>> {
        check(isStandardObdPhysicalReadEnabled)
        // An absent/refused read is not equivalent to a confirmed empty list.
        suspend fun read(mode: Int): List<ObdDtc> =
            J2534StandardObd.dtcs(mode, exchange(byteArrayOf(mode.toByte()), 2500))
        return Triple(read(3), read(7), read(10))
    }

    suspend fun clearStandardObdDtcs() = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(isStandardObdClearEnabled) { "Local Windows operator has not enabled OBD clear." }
            val response = request(JSONObject()
                .put("op", "clearStandardObdDtcs")
                .put("token", tlsPairing.token)
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
            throw IllegalStateException(response.optString("error", "Erreur inconnue du pont J2534."))
        }
        return response
    }

    private fun ensureOpen() {
        check(isConnected) {
            "Connecte d'abord le pont PC J2534."
        }
    }

    fun close() {
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
        opened = false
        scopedMemoryReadEnabled = false
        standardObdPhysicalReadEnabled = false
        standardObdClearEnabled = false
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02X".format(Locale.ROOT, it.toInt() and 0xFF) }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

/** Bounded framing helpers for the untrusted JSON-lines PC bridge stream. */
internal object J2534BridgeProtocol {
    const val MAX_PAYLOAD_BYTES = 4091
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
            check(line.length < MAX_RESPONSE_LINE_CHARS) {
                "The J2534 bridge response exceeds the maximum allowed line size."
            }
            line.append(next.toChar())
        }
        return line.toString()
    }
}
