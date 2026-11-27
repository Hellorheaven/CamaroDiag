package com.camarodiag.connectors

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** CU/1 command codec, separate from public discovery. No key provisioning. */
object J1979UsbAuth {
    const val HELLO_SIZE = 49
    const val CHALLENGE_SIZE = 68
    const val PROOF_SIZE = 36

    @JvmStatic
    fun physicalPair(tx: Int, rx: Int, extended: Boolean): Boolean {
        if (!extended) return tx in 0x7e0..0x7e7 && rx == tx + 8
        if (tx !in 0..0x1fffffff || (tx and 0x1fff00ff) != 0x18da00f1) return false
        val source = (tx ushr 8) and 255
        return source < 0xf0 && rx == (0x18daf100 or source)
    }

    @JvmStatic
    fun hello(tx: Int, rx: Int, extended: Boolean, budgetMs: Int): ByteArray =
        hello(tx, rx, extended, budgetMs, CuWire.nonce())

    @JvmStatic
    fun hello(tx: Int, rx: Int, extended: Boolean, budgetMs: Int, nonce: ByteArray?): ByteArray {
        require(physicalPair(tx, rx, extended) && budgetMs in 1..600000 &&
            nonce != null && nonce.size == 32 && CuWire.nonzero(nonce, 0, 32)) {
            "Invalid CU/1 profile or nonce"
        }
        return ByteBuffer.allocate(HELLO_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            .put(CuWire.prefix(1, 1)).putInt(tx).putInt(rx)
            .put(if (extended) 1.toByte() else 0.toByte()).putInt(budgetMs).put(nonce).array()
    }

    @JvmStatic
    @Throws(IOException::class)
    fun proof(request: ByteArray?, challenge: ByteArray?, key: ByteArray?): ByteArray {
        if (request == null || challenge == null || key == null ||
            !CuWire.header(request, HELLO_SIZE, 1, 1) ||
            !CuWire.header(challenge, CHALLENGE_SIZE, 1, 2) ||
            key.size != 32 || !CuWire.nonzero(request, 17, 49) ||
            !CuWire.nonzero(challenge, 4, 36)) throw IOException("Invalid CU/1 transcript")
        val p = CuWire.little(request)
        val ext = CuWire.unsigned(p.get(12))
        if (ext > 1 || !physicalPair(p.getInt(4), p.getInt(8), ext == 1) ||
            p.getInt(13) !in 1..600000) throw IOException("Invalid CU/1 profile")
        return CuWire.proof(1, request, challenge, key, 'S', 'C',
            "Device authentication failed", "CU/1 cryptography unavailable")
    }
}
