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
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale

/**
 * JSON-lines TCP client for the Windows J2534 bridge.
 * J2534 performs ISO-TP framing on the PC; this class exchanges complete UDS payloads.
 */
class J2534BridgeTransport(
    private val host: String,
    private val port: Int,
    private val pairingToken: String
) : UdsPayloadTransport {
    private val mutex = Mutex()
    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    private var opened = false

    suspend fun connect(
        requestCanId: Int,
        responseCanId: Int,
        can29Bit: Boolean,
        protocolNumber: Int = 33
    ): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            closeInternal()
            require(host.isNotBlank()) { "Adresse IP du PC manquante." }
            require(port in 1024..65535) { "Port du pont PC invalide." }
            require(pairingToken.isNotBlank()) { "Jeton d'appairage du pont PC manquant." }
            require(protocolNumber in setOf(33, 34, 35, 36, 63, 64)) { "Profil CAN J2534 non pris en charge." }
            val maxId = if (can29Bit) 0x1FFFFFFF else 0x7FF
            require(requestCanId in 0..maxId && responseCanId in 0..maxId && requestCanId != responseCanId) {
                "Identifiants CAN incompatibles avec le format sélectionné."
            }
            require(can29Bit == (protocolNumber in setOf(34, 36, 64))) {
                "La largeur des identifiants CAN ne correspond pas au profil sélectionné."
            }
            try {
                val connected = Socket()
                connected.tcpNoDelay = true
                connected.connect(InetSocketAddress(host, port), 5000)
                connected.soTimeout = 12_000
                socket = connected
                reader = BufferedReader(InputStreamReader(connected.getInputStream(), Charsets.UTF_8))
                writer = BufferedWriter(OutputStreamWriter(connected.getOutputStream(), Charsets.UTF_8))
                val response = request(
                    JSONObject()
                        .put("op", "open")
                        .put("token", pairingToken)
                        .put("requestCanId", requestCanId.toString(16).uppercase().padStart(if (can29Bit) 8 else 3, '0'))
                        .put("responseCanId", responseCanId.toString(16).uppercase().padStart(if (can29Bit) 8 else 3, '0'))
                        .put("can29Bit", can29Bit)
                        .put("protocolNumber", protocolNumber),
                    10_000
                )
                opened = true
                val adapter = response.optString("adapter", "J2534")
                "$adapter • ${response.optString("protocol", "ISO15765")} • ${response.optInt("baudRate")} bit/s"
            } catch (error: Exception) {
                closeInternal()
                throw error
            }
        }
    }

    override suspend fun exchange(payload: ByteArray, timeoutMs: Long): ByteArray = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureOpen()
            require(payload.size in 1..4095) { "La requête UDS doit contenir entre 1 et 4095 octets." }
            val boundedTimeout = timeoutMs.coerceIn(100L, 10_000L).toInt()
            socket?.soTimeout = boundedTimeout + 3000
            val response = request(
                JSONObject()
                    .put("op", "exchange")
                    .put("token", pairingToken)
                    .put("payloadHex", payload.toHex())
                    .put("timeoutMs", boundedTimeout),
                boundedTimeout + 3000
            )
            val hex = response.optString("payloadHex")
            if (hex.isBlank() || hex.length % 2 != 0 || hex.length > 8190 ||
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
            require(payload.size in 1..4095) { "La requête UDS doit contenir entre 1 et 4095 octets." }
            val boundedTimeout = timeoutMs.coerceIn(100L, 10_000L).toInt()
            request(
                JSONObject()
                    .put("op", "sendOnly")
                    .put("token", pairingToken)
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
            closeInternal()
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
        check(opened && socket?.isConnected == true && socket?.isClosed == false) {
            "Connecte d'abord le pont PC J2534."
        }
    }

    fun close() {
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
        opened = false
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02X".format(Locale.ROOT, it.toInt() and 0xFF) }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

/** Bounded framing helpers for the untrusted JSON-lines PC bridge stream. */
internal object J2534BridgeProtocol {
    private const val MAX_RESPONSE_LINE_CHARS = 32 * 1024

    fun readBoundedLine(input: BufferedReader): String? {
        val line = StringBuilder()
        while (true) {
            val next = input.read()
            if (next == -1) {
                if (line.isEmpty()) return null
                break
            }
            if (next == '\n'.code) break
            if (next != '\r'.code) {
                check(line.length < MAX_RESPONSE_LINE_CHARS) {
                    "The J2534 bridge response exceeds the maximum allowed line size."
                }
                line.append(next.toChar())
            }
        }
        return line.toString()
    }
}
