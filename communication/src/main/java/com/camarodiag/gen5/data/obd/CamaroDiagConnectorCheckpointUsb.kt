package com.camarodiag.gen5.data.obd

import java.io.IOException

/** Isolated CDJ/USB/1 service only; never enables ECU diagnostics or programming. */
object CamaroDiagConnectorCheckpointUsb {
    private const val MAX_REPLY = 135
    private const val DEADLINE = 3_000_000_000L
    private val replyPattern = Regex("CDJ/1 [0-9a-f]{128}\\n")

    internal fun decode(line: String): ByteArray? {
        if (line == "CDJ/1 NONE\n") return null
        if (line == "CDJ/1 CORRUPT\n") throw IOException("Connector checkpoint corrupt or locked")
        if (!replyPattern.matches(line)) throw IOException("Invalid checkpoint response")
        val bytes = line.substring(6, 134).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        if (CamaroDiagConnectorCheckpoint.inspect(bytes).record == null)
            throw IOException("Invalid checkpoint record")
        return bytes
    }

    fun capture(
        channel: CamaroDiagConnectorDiscovery.Channel,
        ensureCurrent: () -> Unit = {},
        clock: () -> Long = System::nanoTime
    ): ByteArray? {
        val start = clock()
        ensureCurrent()
        channel.write("CDJ/1 READ\n".toByteArray(Charsets.US_ASCII))
        val buffer = ByteArray(512)
        val message = StringBuilder(MAX_REPLY)
        while (clock() - start < DEADLINE) {
            ensureCurrent()
            val count = channel.read(buffer, 100)
            ensureCurrent()
            if (clock() - start >= DEADLINE || count < 0 || count > buffer.size)
                throw IOException("Checkpoint timeout or invalid USB read")
            for (i in 0 until count) {
                val value = buffer[i].toInt() and 0xff
                if (message.length >= MAX_REPLY || (value != 10 && value !in 32..126))
                    throw IOException("Malformed checkpoint response")
                message.append(value.toChar())
                if (value == 10) {
                    if (i != count - 1) throw IOException("Unexpected trailing USB data")
                    ensureCurrent()
                    return decode(message.toString())
                }
            }
        }
        throw IOException("Checkpoint timeout")
    }
}
