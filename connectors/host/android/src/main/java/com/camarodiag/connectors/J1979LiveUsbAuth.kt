package com.camarodiag.connectors

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/** CU/3 binds the exact raw Mode 01 scope to both nonces. */
object J1979LiveUsbAuth {
    const val SCOPE_SIZE = 58
    const val PLAN_SIZE = 67
    const val AUTH_SIZE = 71
    const val HELLO_SIZE = 107
    const val CHALLENGE_SIZE = 68
    const val PROOF_SIZE = 36
    const val MAX_SAMPLES = 10000

    @JvmStatic
    @Throws(IOException::class)
    fun require(value: Boolean, message: String) {
        if (!value) throw IOException(message)
    }

    @JvmStatic fun u(b: Byte): Int = CuWire.unsigned(b)
    @JvmStatic fun validVin(vin: String?): Boolean = CuWire.validVin(vin)
    @JvmStatic fun nonzero(bytes: ByteArray, from: Int, to: Int): Boolean = CuWire.nonzero(bytes, from, to)

    class Profile(
        @JvmField val tx: Int, @JvmField val rx: Int, @JvmField val extended: Boolean,
        vin: String?, pids: IntArray?, @JvmField val cycles: Int,
        @JvmField val intervalMs: Int, @JvmField val budgetMs: Int
    ) {
        @JvmField val vin: String
        private val selectedPids: IntArray

        init {
            kotlin.require(J1979UsbAuth.physicalPair(tx, rx, extended) && validVin(vin) &&
                pids != null && pids.size in 1..32 && cycles in 1..1000 &&
                cycles * pids.size <= MAX_SAMPLES && intervalMs in 100..60000 &&
                budgetMs in 1000..600000) { "Invalid CU/3 live scope" }
            this.vin = requireNotNull(vin)
            selectedPids = pids.copyOf()
            val seen = BooleanArray(256)
            for (pid in selectedPids) {
                kotlin.require(pid in 1..255 && pid % 32 != 0 && !seen[pid]) { "Invalid selected raw PID" }
                seen[pid] = true
            }
        }

        fun pids(): IntArray = selectedPids.copyOf()

        fun encode(): ByteArray {
            val wire = ByteArray(PLAN_SIZE)
            val b = CuWire.little(wire)
            b.putInt(tx).putInt(rx).put(if (extended) 1.toByte() else 0.toByte())
                .put(vin.toByteArray(StandardCharsets.US_ASCII)).put(selectedPids.size.toByte())
            for (pid in selectedPids) b.put(pid.toByte())
            b.position(59)
            b.putShort(cycles.toShort()).putShort(intervalMs.toShort()).putInt(budgetMs)
            return wire
        }

        fun encodeAuth(): ByteArray {
            val plan = encode()
            return ByteBuffer.allocate(AUTH_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                .put(plan, 0, 9).putInt(budgetMs).put(plan, 9, SCOPE_SIZE).array()
        }

        companion object {
            @JvmStatic
            @Throws(IOException::class)
            fun decode(wire: ByteArray?): Profile {
                if (wire == null || wire.size != PLAN_SIZE || u(wire[8]) > 1 ||
                    u(wire[26]) !in 1..32) throw IOException("Invalid CL/1 plan")
                val b = CuWire.little(wire)
                val pids = IntArray(u(wire[26])) { u(wire[27 + it]) }
                try {
                    val result = Profile(b.getInt(0), b.getInt(4), wire[8] == 1.toByte(),
                        String(wire, 9, 17, StandardCharsets.US_ASCII), pids,
                        b.getShort(59).toInt() and 65535, b.getShort(61).toInt() and 65535, b.getInt(63))
                    require(wire.contentEquals(result.encode()), "Noncanonical CL/1 plan")
                    return result
                } catch (error: IllegalArgumentException) {
                    throw IOException("Invalid CL/1 profile", error)
                }
            }

            @JvmStatic
            @Throws(IOException::class)
            fun decodeAuth(data: ByteArray?): Profile {
                if (data == null || data.size != AUTH_SIZE) throw IOException("Invalid CU/3 auth profile")
                val plan = ByteArray(PLAN_SIZE)
                data.copyInto(plan, 0, 0, 9)
                data.copyInto(plan, 9, 13, 13 + SCOPE_SIZE)
                val profile = decode(plan)
                require(data.contentEquals(profile.encodeAuth()), "Contradictory CU/3 budget")
                return profile
            }
        }
    }

    @JvmStatic
    fun hello(profile: Profile?): ByteArray = hello(profile, CuWire.nonce())

    @JvmStatic
    fun hello(profile: Profile?, nonce: ByteArray?): ByteArray {
        kotlin.require(profile != null && nonce != null && nonce.size == 32 && nonzero(nonce, 0, 32)) {
            "Invalid CU/3 nonce"
        }
        return ByteBuffer.allocate(HELLO_SIZE).put(CuWire.prefix(3, 1))
            .put(profile.encodeAuth()).put(nonce).array()
    }

    @JvmStatic
    @Throws(IOException::class)
    fun proof(request: ByteArray?, challenge: ByteArray?, key: ByteArray?): ByteArray {
        if (request == null || challenge == null || key == null ||
            !CuWire.header(request, HELLO_SIZE, 3, 1) ||
            !CuWire.header(challenge, CHALLENGE_SIZE, 3, 2) ||
            key.size != 32 || !nonzero(request, 4 + AUTH_SIZE, HELLO_SIZE) ||
            !nonzero(challenge, 4, 36)) throw IOException("Invalid CU/3 transcript")
        Profile.decodeAuth(request.copyOfRange(4, 4 + AUTH_SIZE))
        return CuWire.proof(3, request, challenge, key, 'L', 'l',
            "CU/3 server proof rejected", "CU/3 cryptography unavailable")
    }
}
