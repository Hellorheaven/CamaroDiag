package com.camarodiag.connectors

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/** Canonical CU/2 plan, distinct from CU/1 and discovery. */
object J1979FreezeUsbAuth {
    const val PLAN_SIZE = 104
    const val HELLO_SIZE = 140
    const val CHALLENGE_SIZE = 68
    const val PROOF_SIZE = 36

    class Profile(
        @JvmField val tx: Int, @JvmField val rx: Int, @JvmField val extended: Boolean,
        @JvmField val budgetMs: Int, vin: String?, frames: IntArray?, pids: IntArray?
    ) {
        @JvmField val vin: String
        private val selectedFrames: IntArray
        private val selectedPids: IntArray

        init {
            require(J1979UsbAuth.physicalPair(tx, rx, extended) && budgetMs in 1000..600000 &&
                CuWire.validVin(vin) && frames != null && frames.size in 1..8 &&
                pids != null && pids.size <= 64) { "Invalid CU/2 profile" }
            this.vin = requireNotNull(vin)
            selectedFrames = frames.copyOf()
            selectedPids = pids.copyOf()
            val seen = BooleanArray(256)
            for (frame in selectedFrames) {
                require(frame in 0..255 && !seen[frame]) { "Invalid freeze frame selection" }
                seen[frame] = true
            }
            seen.fill(false)
            for (pid in selectedPids) {
                require(pid in 1..255 && pid != 2 && pid % 32 != 0 && !seen[pid]) {
                    "Invalid freeze PID selection"
                }
                seen[pid] = true
            }
        }

        fun frames(): IntArray = selectedFrames.copyOf()
        fun pids(): IntArray = selectedPids.copyOf()

        fun encode(): ByteArray {
            val wire = ByteArray(PLAN_SIZE)
            val p = CuWire.little(wire)
            p.putInt(tx).putInt(rx).put(if (extended) 1.toByte() else 0.toByte())
                .putInt(budgetMs).put(vin.toByteArray(StandardCharsets.US_ASCII))
                .put(selectedFrames.size.toByte())
            for (frame in selectedFrames) p.put(frame.toByte())
            p.position(39)
            p.put(selectedPids.size.toByte())
            for (pid in selectedPids) p.put(pid.toByte())
            return wire
        }

        companion object {
            @JvmStatic
            @Throws(IOException::class)
            fun decode(wire: ByteArray?): Profile {
                if (wire == null || wire.size != PLAN_SIZE || CuWire.unsigned(wire[8]) > 1 ||
                    CuWire.unsigned(wire[30]) !in 1..8 || CuWire.unsigned(wire[39]) > 64)
                    throw IOException("Invalid CF/1 plan")
                val p = CuWire.little(wire)
                val frames = IntArray(CuWire.unsigned(wire[30])) { CuWire.unsigned(wire[31 + it]) }
                val pids = IntArray(CuWire.unsigned(wire[39])) { CuWire.unsigned(wire[40 + it]) }
                try {
                    val profile = Profile(p.getInt(0), p.getInt(4), wire[8] == 1.toByte(),
                        p.getInt(9), String(wire, 13, 17, StandardCharsets.US_ASCII), frames, pids)
                    if (!wire.contentEquals(profile.encode())) throw IOException("Noncanonical CF/1 plan")
                    return profile
                } catch (error: IllegalArgumentException) {
                    throw IOException("Invalid CF/1 profile", error)
                }
            }
        }
    }

    @JvmStatic
    fun hello(profile: Profile?): ByteArray = hello(profile, CuWire.nonce())

    @JvmStatic
    fun hello(profile: Profile?, nonce: ByteArray?): ByteArray {
        require(profile != null && nonce != null && nonce.size == 32 && CuWire.nonzero(nonce, 0, 32)) {
            "Invalid CU/2 nonce"
        }
        return ByteBuffer.allocate(HELLO_SIZE).put(CuWire.prefix(2, 1))
            .put(profile.encode()).put(nonce).array()
    }

    @JvmStatic
    @Throws(IOException::class)
    fun proof(request: ByteArray?, challenge: ByteArray?, key: ByteArray?): ByteArray {
        if (request == null || challenge == null || key == null ||
            !CuWire.header(request, HELLO_SIZE, 2, 1) ||
            !CuWire.header(challenge, CHALLENGE_SIZE, 2, 2) ||
            key.size != 32 || !CuWire.nonzero(request, 108, 140) ||
            !CuWire.nonzero(challenge, 4, 36)) throw IOException("Invalid CU/2 transcript")
        Profile.decode(request.copyOfRange(4, 108))
        return CuWire.proof(2, request, challenge, key, 'F', 'f',
            "CU/2 server proof rejected", "CU/2 cryptography unavailable")
    }
}
