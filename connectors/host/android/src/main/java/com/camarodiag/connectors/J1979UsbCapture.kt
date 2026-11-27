package com.camarodiag.connectors

import java.io.IOException
import java.io.OutputStream
import java.util.Collections
import java.util.function.LongSupplier

/** One CU/1 capture on a caller-owned USB link. Frame integrity is not proof of physical origin. */
class J1979UsbCapture @JvmOverloads constructor(
    private val link: Link,
    clock: LongSupplier = LongSupplier { System.nanoTime() / 1_000_000L }
) {
    interface Link : AutoCloseable {
        @Throws(IOException::class)
        fun read(data: ByteArray, offset: Int, size: Int, timeoutMs: Int): Int
        @Throws(IOException::class)
        fun write(data: ByteArray, timeoutMs: Int)
        @Throws(IOException::class)
        fun checkNoBufferedInput()
        @Throws(IOException::class)
        override fun close()
    }

    class Result(
        @JvmField val deviceCredentialAuthenticated: Boolean,
        @JvmField val frameIntegrityVerified: Boolean,
        @JvmField val records: Int,
        @JvmField val error: String?,
        steps: List<J1979CampaignSemantics.Step>,
        summary: J1979CampaignSemantics.Summary?
    ) {
        @JvmField val evidenceAuthenticated = false
        @JvmField val physicalVehicleVerified = false
        @JvmField val steps: List<J1979CampaignSemantics.Step> =
            Collections.unmodifiableList(ArrayList(steps))
        @JvmField val summary = if (frameIntegrityVerified) summary else null
        @JvmField val captureIntegrityVerified = this.summary != null
        @JvmField val observedVin: String? = steps.mapNotNull { it.observedVin() }.lastOrNull()
    }

    private val reader = CuCaptureReader(link, clock, "CU/1")
    private var used = false

    @Synchronized
    @Throws(IOException::class)
    fun run(tx: Int, rx: Int, extended: Boolean, budget: Int, key: ByteArray?, raw: OutputStream?): Result =
        runRequest(J1979UsbAuth.hello(tx, rx, extended, budget), budget, key, raw)

    @Synchronized
    @Throws(IOException::class)
    fun runRequest(hello: ByteArray, budget: Int, key: ByteArray?, raw: OutputStream?): Result {
        if (used) throw IOException("Fresh CU/1 session required")
        require(raw != null && key != null && key.size == 32 && budget in 1..600000) {
            "Invalid capture parameters"
        }
        // Do not send a caller-supplied malformed transcript or a contradictory budget.
        if (!CuWire.header(hello, J1979UsbAuth.HELLO_SIZE, 1, 1) ||
            !CuWire.nonzero(hello, 17, 49)) throw IOException("Invalid CU/1 hello")
        val profile = CuWire.little(hello)
        val ext = CuWire.unsigned(profile.get(12))
        if (ext > 1 || !J1979UsbAuth.physicalPair(profile.getInt(4), profile.getInt(8), ext == 1) ||
            profile.getInt(13) != budget) throw IOException("CU/1 hello differs from approved budget or scope")
        used = true
        var authenticated = false
        var framed = false
        var records = 0
        var error: String? = null
        val steps = ArrayList<J1979CampaignSemantics.Step>()
        var summary: J1979CampaignSemantics.Summary? = null
        try {
            var start = reader.now()
            link.write(hello, reader.remaining(start, 5000))
            val challenge = reader.exact(J1979UsbAuth.CHALLENGE_SIZE, start, 5000)
            val proof = J1979UsbAuth.proof(hello, challenge, key)
            authenticated = true
            try {
                link.checkNoBufferedInput()
                link.write(proof, reader.remaining(start, 5000))
            } finally {
                proof.fill(0)
            }
            start = reader.now()
            val limit = budget + 15000
            val counts = LongArray(6)
            for (sequence in 0 until 2048) {
                val head = reader.exact(8, start, limit, raw)
                val kind = CuCaptureReader.u(head[3])
                val length = CuCaptureReader.le16(head, 6)
                if (head[0] != 'C'.code.toByte() || head[1] != 'J'.code.toByte() ||
                    head[2] != 1.toByte() || CuCaptureReader.le16(head, 4) != sequence || length > 4110)
                    throw IOException("Invalid CJ/1 frame")
                val tail = reader.exact(length + 4, start, limit, raw)
                if (!CuCaptureReader.validCrc(head, tail, length)) throw IOException("CJ/1 CRC mismatch")
                when {
                    sequence == 0 -> {
                        if (kind != 1 || length != 13 ||
                            !tail.copyOf(13).contentEquals(hello.copyOfRange(4, 17)))
                            throw IOException("Unexpected CJ/1 campaign profile")
                    }
                    kind == 2 -> {
                        val state = CuCaptureReader.u(tail[0])
                        if (length < 9 || state !in 1..5) throw IOException("Invalid CJ/1 observation")
                        val req = CuCaptureReader.u(tail[1])
                        val res = CuCaptureReader.le16(tail, 6)
                        if (req !in 1..7 || res > 4095 || length != 8 + req + res || ((state <= 3) != (res > 0)))
                            throw IOException("Invalid CJ/1 observation length")
                        steps.add(J1979CampaignSemantics.Step(sequence, state, CuCaptureReader.le32(tail, 2),
                            tail.copyOfRange(8, 8 + req), tail.copyOfRange(8 + req, length)))
                        counts[state]++
                    }
                    kind == 3 -> {
                        if (length != 22 || CuCaptureReader.u(tail[0]) !in 1..5 ||
                            CuCaptureReader.le32(tail, 2) != (sequence - 1).toLong() ||
                            (1..4).any { CuCaptureReader.le32(tail, 2 + 4 * it) != counts[it] })
                            throw IOException("Inconsistent CJ/1 summary")
                        link.checkNoBufferedInput()
                        reader.remaining(start, limit)
                        records++
                        framed = true
                        summary = J1979CampaignSemantics.verify(steps, CuCaptureReader.u(tail[0]), CuCaptureReader.u(tail[1]))
                        break
                    }
                    else -> throw IOException("Unexpected CJ/1 record")
                }
                records++
            }
            if (!framed) throw IOException("CJ/1 record limit exceeded")
        } catch (failure: IOException) {
            error = failure.message ?: "CU/1 exchange failed"
            framed = false
        } finally {
            try {
                link.close()
            } catch (failure: IOException) {
                framed = false
                if (error == null) error = failure.message ?: "USB close failed"
            }
            try {
                raw.flush()
            } catch (failure: IOException) {
                framed = false
                if (error == null) error = failure.message ?: "Capture flush failed"
            }
        }
        return Result(authenticated, framed, records, error, steps, summary)
    }
}
