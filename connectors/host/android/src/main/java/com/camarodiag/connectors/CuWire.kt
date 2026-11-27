package com.camarodiag.connectors

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Shared wire primitives for the distinct authenticated CU protocols. */
internal object CuWire {
    private val random = SecureRandom()

    fun unsigned(value: Byte): Int = value.toInt() and 0xff

    fun nonzero(data: ByteArray, start: Int, end: Int): Boolean {
        var bits = 0
        for (index in start until end) bits = bits or unsigned(data[index])
        return bits != 0
    }

    fun header(data: ByteArray?, size: Int, version: Int, operation: Int): Boolean =
        data != null && data.size == size &&
            data[0] == 'C'.code.toByte() && data[1] == 'U'.code.toByte() &&
            unsigned(data[2]) == version && unsigned(data[3]) == operation

    fun prefix(version: Int, operation: Int): ByteArray =
        byteArrayOf('C'.code.toByte(), 'U'.code.toByte(), version.toByte(), operation.toByte())

    fun nonce(): ByteArray = ByteArray(32).also(random::nextBytes)

    fun validVin(vin: String?): Boolean = vin != null && vin.matches(Regex("[A-HJ-NPR-Z0-9]{17}"))

    fun little(data: ByteArray): ByteBuffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

    /** Transcript covers both nonces and the full canonical scope, with version-specific domains. */
    @Throws(IOException::class)
    fun proof(
        version: Int, request: ByteArray, challenge: ByteArray, key: ByteArray,
        serverDomain: Char, clientDomain: Char, rejection: String, unavailable: String
    ): ByteArray {
        val transcript = ByteArray(1 + request.size - 4 + 32)
        request.copyInto(transcript, 1, 4)
        challenge.copyInto(transcript, 1 + request.size - 4, 4, 36)
        try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            transcript[0] = serverDomain.code.toByte()
            val server = mac.doFinal(transcript)
            try {
                if (!MessageDigest.isEqual(server, challenge.copyOfRange(36, 68))) {
                    throw IOException(rejection)
                }
            } finally {
                server.fill(0)
            }
            transcript[0] = clientDomain.code.toByte()
            val tag = mac.doFinal(transcript)
            try {
                return ByteBuffer.allocate(36).put(prefix(version, 3)).put(tag).array()
            } finally {
                tag.fill(0)
            }
        } catch (error: GeneralSecurityException) {
            throw IOException(unavailable, error)
        } finally {
            transcript.fill(0)
        }
    }
}
