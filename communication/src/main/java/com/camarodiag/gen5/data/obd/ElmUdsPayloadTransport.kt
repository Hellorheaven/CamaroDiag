package com.camarodiag.gen5.data.obd

import java.util.Locale
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Normal-addressed Classical ISO-TP, with ELM CAF0 and automatic receive flow
 * control. Only single-frame read requests are implemented; the adapter sends
 * flow control for multi-frame replies. No write/programming capability implied.
 */
internal class ElmUdsPayloadTransport(
    private val requestCanId: Int,
    private val responseCanId: Int,
    private val protocolNumber: Int,
    private val send: suspend (String, Long) -> String
) : UdsPayloadTransport {
    private val mutex = Mutex()
    private val extended = protocolNumber in setOf(34, 36)
    private val elmProtocol = DiagnosticRouteSelector.elmCanProtocol(extended,
        if (protocolNumber in setOf(35, 36)) 250_000 else 500_000)
    private var configured = false

    init {
        require(protocolNumber in setOf(33, 34, 35, 36))
        val max = if (extended) 0x1FFFFFFF else 0x7FF
        require(requestCanId in 0..max && responseCanId in 0..max &&
            requestCanId != responseCanId)
    }

    private fun id(value: Int) = value.toString(16).uppercase(Locale.ROOT)
        .padStart(if (extended) 8 else 3, '0')

    override suspend fun exchange(payload: ByteArray, timeoutMs: Long): ByteArray = mutex.withLock {
        require(payload.size in 2..7 && (payload[0].toInt() and 255) in setOf(0x19, 0x22)) {
            "ELM UDS supports single-frame diagnostic read requests only."
        }
        if (!configured) {
            // ELM327 AT command reference: CAF0 does not disable CFC1.
            // Explicit FC mode 1 is needed for manufacturer physical ID pairs.
            for (command in listOf("ATSP$elmProtocol", "ATH1", "ATS1", "ATD0",
                "ATCAF0", "ATSH ${id(requestCanId)}", "ATCRA ${id(responseCanId)}",
                "ATFCSH ${id(requestCanId)}", "ATFCSD 30 00 00", "ATFCSM1", "ATCFC1")) {
                check(CanConsoleAdapterRestorationPolicy.accepted(send(command, timeoutMs))) {
                    "ELM adapter rejected diagnostic configuration: $command"
                }
            }
            configured = true
        }
        val frame = byteArrayOf(payload.size.toByte()) + payload
        val command = frame.joinToString("") { "%02X".format(Locale.ROOT, it.toInt() and 255) }
        val response = decode(send(command, timeoutMs), responseCanId, extended)
        val sid = payload[0].toInt() and 255
        check(response.isNotEmpty() &&
            ((response[0].toInt() and 255) == sid + 0x40 ||
             (response.size == 3 && response[0] == 0x7F.toByte() && response[1] == payload[0]))) {
            "ELM UDS response does not match the requested service."
        }
        // Older ELM variants cannot reliably continue waiting after a prompt.
        // Return no invented positive response and never replay the request.
        check(!(response.size == 3 && response[0] == 0x7F.toByte() && response[2] == 0x78.toByte())) {
            "ECU response pending; this ELM transport cannot safely continue the exchange."
        }
        response
    }

    override suspend fun sendOnly(payload: ByteArray, timeoutMs: Long) {
        error("ELM diagnostic read transport does not support send-only services.")
    }

    companion object {
        /** Reject truncated, duplicate and out-of-order ISO-TP messages. */
        internal fun decode(output: String, responder: Int, extended: Boolean): ByteArray {
            val bytes = ArrayList<Byte>()
            var expected = 0
            var seq = 1
            var complete = false
            val width = if (extended) 8 else 3
            for (line in output.replace('\r', '\n').replace('>', '\n').lines()) {
                val clean = line.trim()
                if (clean.isEmpty() || clean.startsWith("FC:", true)) continue
                check(!listOf("NO DATA", "CAN ERROR", "BUS ERROR", "BUFFER FULL", "STOPPED", "UNABLE TO CONNECT", "?")
                    .any { clean.contains(it, true) }) { "ELM diagnostic exchange failed: $clean" }
                val tokens = clean.split(Regex("\\s+"))
                val headerCount = if (extended && tokens.take(4).size == 4 &&
                    tokens.take(4).all { it.length == 2 && it.all { c -> c.digitToIntOrNull(16) != null } }) 4 else 1
                val header = tokens.take(headerCount).joinToString("")
                if (header.length != width || header.toLongOrNull(16) != responder.toLong()) continue
                val data = tokens.drop(headerCount)
                check(data.size in 1..8 && data.all { it.length == 2 && it.all { c -> c.digitToIntOrNull(16) != null } }) {
                    "Malformed ELM CAN frame from selected ECU."
                }
                val raw = data.map { it.toInt(16) }
                check(!complete) { "Multiple ISO-TP responses from selected ECU." }
                when (raw[0] ushr 4) {
                    0 -> {
                        check(expected == 0)
                        expected = raw[0] and 15
                        check(expected in 1..7 && raw.size >= expected + 1)
                        bytes.addAll(raw.drop(1).take(expected).map(Int::toByte))
                    }
                    1 -> {
                        check(expected == 0 && raw.size == 8)
                        expected = ((raw[0] and 15) shl 8) or raw[1]
                        check(expected in 8..4095)
                        bytes.addAll(raw.drop(2).map(Int::toByte))
                    }
                    2 -> {
                        check(expected > bytes.size && expected > 7 && (raw[0] and 15) == seq)
                        val remaining = expected - bytes.size
                        check(raw.size >= minOf(remaining, 7) + 1)
                        bytes.addAll(raw.drop(1).take(remaining).map(Int::toByte))
                        seq = (seq + 1) and 15
                    }
                    else -> error("Unexpected ISO-TP frame from selected ECU.")
                }
                complete = expected > 0 && bytes.size == expected
            }
            check(complete) { "No complete ISO-TP response from selected ECU." }
            return bytes.toByteArray()
        }

        val restoreCommands = listOf("ATFCSM0", "ATCRA", "ATSP6", "ATSH 7DF",
            "ATCAF1", "ATCFC1", "ATH1", "ATS0", "ATD0")
    }
}
