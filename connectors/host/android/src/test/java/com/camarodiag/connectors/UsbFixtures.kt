package com.camarodiag.connectors

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Synthetic test data only: no physical USB operations or production keys. */
internal object UsbFixtures {
    val key get() = ByteArray(32) { it.toByte() }
    val nonce get() = ByteArray(32) { 'H'.code.toByte() }
    val device get() = ByteArray(32) { 'D'.code.toByte() }

    fun bytes(vararg data: Int) = ByteArray(data.size) { data[it].toByte() }
    fun hex(value: String): ByteArray {
        check(value.length % 2 == 0)
        return ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
    fun concat(vararg items: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        items.forEach { out.write(it) }
        return out.toByteArray()
    }
    fun le(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
    fun mac(key: ByteArray, value: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(value) }
    fun crc(data: ByteArray): Long = CRC32().also { it.update(data) }.value
    fun frame(magic: Char, kind: Int, sequence: Int, body: ByteArray): ByteArray {
        val data = le(12 + body.size).put(bytes('C'.code, magic.code, 1, kind))
            .putShort(sequence.toShort()).putShort(body.size.toShort()).put(body).array()
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(data.size - 4, crc(data.copyOf(data.size - 4)).toInt())
        return data
    }
    fun rejects(block: () -> Unit) {
        try { block() } catch (_: IOException) { return }
        error("Malformed evidence or retired session accepted")
    }
    fun flip(data: ByteArray, index: Int) {
        data[index] = (data[index].toInt() xor 1).toByte()
    }
    class Sim(private val bytes: ByteArray) : J1979UsbCapture.Link {
        private var pos = 0
        var writes = 0
            private set
        var closed = false
            private set
        override fun read(data: ByteArray, offset: Int, size: Int, timeoutMs: Int): Int {
            check(!closed && timeoutMs > 0)
            val count = minOf(7, size, bytes.size - pos)
            bytes.copyInto(data, offset, pos, pos + count)
            pos += count
            return count
        }
        override fun write(data: ByteArray, timeoutMs: Int) {
            check(!closed && timeoutMs > 0 && data.isNotEmpty())
            writes++
            check(writes <= 2)
        }
        override fun checkNoBufferedInput() {
            if (writes == 2 && pos != bytes.size) throw IOException("Trailing data")
        }
        override fun close() { closed = true }
    }
}
