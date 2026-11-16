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
        baudRate: Int = 500_000
    ): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            closeInternal()
            require(host.isNotBlank()) { "Adresse IP du PC manquante." }
            require(port in 1024..65535) { "Port du pont PC invalide." }
            require(pairingToken.isNotBlank()) { "Jeton d'appairage du pont PC manquant." }
            require(baudRate == 250_000 || baudRate == 500_000) { "Débit CAN J2534 non pris en charge." }
            val maxId = if (can29Bit) 0x1FFFFFFF else 0x7FF
            require(requestCanId in 0..maxId && responseCanId in 0..maxId && requestCanId != responseCanId) {
                "Identifiants CAN incompatibles avec le format sélectionné."
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
                        .put("requestCanId", requestCanId.toString(if (can29Bit) "X8" else "X3"))
                        .put("responseCanId", responseCanId.toString(if (can29Bit) "X8" else "X3"))
                        .put("can29Bit", can29Bit)
                        .put("baudRate", baudRate),
                    10_000
                )
                opened = true
                val adapter = response.optString("adapter", "J2534")
                "$adapter • ISO15765 • $baudRate bit/s"
            } catch (error: Exception) {
                closeInternal()
                throw error
            }
        }
    }

    override suspend fun exchange(payload: ByteArray, timeoutMs: Long): ByteArray = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureOpen()
            require(payload.isNotEmpty())
            val boundedTimeout = timeoutMs.coerceIn(100L, 10_000L).toInt()
            socket?.soTimeout = boundedTimeout + 3000
            val response = request(
                JSONObject()
                    .put("op", "exchange")
                    .put("token", pairingToken)
                    .put("payloadHex", payload.toHex()),
                boundedTimeout
            )
            val hex = response.optString("payloadHex")
            require(hex.isNotBlank() && hex.length % 2 == 0) { "Réponse UDS vide ou mal formée du pont J2534." }
            try { hex.hexToBytes() } catch (error: IllegalArgumentException) {
                throw IllegalStateException("Le pont J2534 a retourné une réponse hexadécimale invalide.", error)
            }
        }
    }

    override suspend fun sendOnly(payload: ByteArray, timeoutMs: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureOpen()
            require(payload.isNotEmpty())
            socket?.soTimeout = 3000
            request(
                JSONObject()
                    .put("op", "sendOnly")
                    .put("token", pairingToken)
                    .put("payloadHex", payload.toHex()),
                2000
            )
            Unit
        }
    }

    private fun request(json: JSONObject, timeoutMs: Int): JSONObject {
        val activeSocket = socket ?: error("Pont PC J2534 non connecté.")
        activeSocket.soTimeout = timeoutMs.coerceIn(1000, 15_000)
        val output = writer ?: error("Canal d'écriture du pont PC indisponible.")
        val input = reader ?: error("Canal de lecture du pont PC indisponible.")
        output.write(json.toString())
        output.newLine()
        output.flush()
        val line = input.readLine() ?: error("Le pont PC a fermé la connexion.")
        val response = JSONObject(line)
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
        if (opened && socket?.isConnected == true && socket?.isClosed == false) {
            runCatching {
                socket?.soTimeout = 1000
                writer?.write(
                    JSONObject().put("op", "close").put("token", pairingToken).toString()
                )
                writer?.newLine()
                writer?.flush()
                reader?.readLine()
            }
        }
        runCatching { reader?.close() }
        runCatching { writer?.close() }
        runCatching { socket?.close() }
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
