package com.camarodiag.connectors

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Collections
import java.util.function.BooleanSupplier
import java.util.function.LongSupplier

/** CF/1 framing is distinct from semantic replay and never proves physical provenance. */
class J1979FreezeUsbCapture @JvmOverloads constructor(
    private val link: J1979UsbCapture.Link,
    clock: LongSupplier = LongSupplier { System.nanoTime() / 1_000_000L }
) {
    class Observation(
        request: ByteArray, response: ByteArray?,
        @JvmField val outcome: String, @JvmField val attempted: Boolean,
        @JvmField val durationMs: Long
    ) {
        private val requestBytes = request.copyOf()
        private val responseBytes = response?.copyOf()
        fun request(): ByteArray = requestBytes.copyOf()
        fun response(): ByteArray? = responseBytes?.copyOf()
    }

    class Terminal(
        @JvmField val stopReason: String?, @JvmField val visitedFrames: Int,
        @JvmField val steps: Int, flags: Int, @JvmField val durationMs: Long,
        frameStates: List<String>
    ) {
        @JvmField val completed = (flags and 1) != 0
        @JvmField val identityRechecked = (flags and 2) != 0
        @JvmField val allFramesStable = (flags and 4) != 0
        @JvmField val frameStates: List<String> = Collections.unmodifiableList(ArrayList(frameStates))
    }

    class Result(
        @JvmField val deviceCredentialAuthenticated: Boolean,
        @JvmField val clientProofSent: Boolean, @JvmField val frameIntegrityVerified: Boolean,
        @JvmField val cancelled: Boolean, @JvmField val profile: J1979FreezeUsbAuth.Profile?,
        observations: List<Observation>, @JvmField val terminal: Terminal?,
        @JvmField val error: String?
    ) {
        @JvmField val evidenceAuthenticated = false
        @JvmField val physicalVehicleVerified = false
        @JvmField val permitsProgramming = false
        @JvmField val observations: List<Observation> = Collections.unmodifiableList(ArrayList(observations))
    }

    /** Complete records only; a missing terminal is never interpreted as successful completion. */
    class Prefix(
        @JvmField val profile: J1979FreezeUsbAuth.Profile, observations: List<Observation>,
        @JvmField val completeRecords: Int, @JvmField val lastCompleteOffset: Int,
        @JvmField val discardedTailBytes: Int, @JvmField val interruption: String
    ) {
        @JvmField val observations: List<Observation> = Collections.unmodifiableList(ArrayList(observations))
    }

    private val reader = CuCaptureReader(link, clock, "CU/2")
    private var used = false

    companion object {
        const val MAX_STEPS = 8 * (8 + 2 + 247) + 2
        const val MAX_BODY = 4108
        const val MAX_CAPTURE = (MAX_STEPS + 2L) * (MAX_BODY + 12)
        private val outcomes = arrayOf("", "RECEIVED", "TIMEOUT", "TRANSPORT_ERROR",
            "BUDGET", "CONTEXT_CHANGED", "LATE_RESPONSE")
        private val stops = arrayOf(null, "BUDGET_EXHAUSTED", "TRANSPORT_ERROR", "CONTEXT_CHANGED",
            "VIN_UNVERIFIED", "VIN_MISMATCH", "FINAL_VIN_UNVERIFIED", "FINAL_VIN_MISMATCH")
        private val states = arrayOf("UNAVAILABLE", "ABSENT", "COMPLETE", "PARTIAL", "CHANGED")

        private fun u(value: Byte) = CuCaptureReader.u(value)
        private fun le16(bytes: ByteArray, offset: Int) = CuCaptureReader.le16(bytes, offset)
        private fun le32(bytes: ByteArray, offset: Int) = CuCaptureReader.le32(bytes, offset)

        private fun header(head: ByteArray, sequence: Int): Int {
            if (head.size != 8 || head[0] != 'C'.code.toByte() || head[1] != 'F'.code.toByte() ||
                head[2] != 1.toByte() || u(head[3]) !in 1..3 ||
                le16(head, 4) != sequence || le16(head, 6) !in 1..MAX_BODY)
                throw IOException("Invalid CF/1 header")
            val length = le16(head, 6)
            val kind = u(head[3])
            if ((sequence == 0 && kind != 1) || (sequence != 0 && kind == 1) ||
                (kind == 1 && length != J1979FreezeUsbAuth.PLAN_SIZE) ||
                (kind == 2 && length < 12) || (kind == 3 && length != 20))
                throw IOException("Invalid CF/1 record type or length")
            return length
        }

        private fun body(head: ByteArray, tail: ByteArray, length: Int): ByteArray {
            if (!CuCaptureReader.validCrc(head, tail, length)) throw IOException("CF/1 CRC mismatch or truncated record")
            return tail.copyOf(length)
        }

        private fun observation(bytes: ByteArray): Observation {
            if (bytes.size < 10 || u(bytes[0]) !in 1..6 || u(bytes[1]) > 1 ||
                u(bytes[2]) !in 2..3 || bytes[3] != 0.toByte()) throw IOException("Invalid CF/1 observation")
            val state = u(bytes[0])
            val size = u(bytes[2])
            val responseSize = le16(bytes, 8)
            val attempted = bytes[1] == 1.toByte()
            val received = state == 1 || state == 6
            val duration = le32(bytes, 4)
            if (responseSize > 4095 || bytes.size != 10 + size + responseSize || duration > 3600000 ||
                received != (responseSize > 0) || ((received || state == 2 || state == 3) && !attempted) ||
                ((state == 4 || state == 5) && attempted)) throw IOException("Inconsistent CF/1 observation")
            return Observation(bytes.copyOfRange(10, 10 + size),
                if (received) bytes.copyOfRange(10 + size, bytes.size) else null,
                outcomes[state], attempted, duration)
        }

        private fun terminal(body: ByteArray, profile: J1979FreezeUsbAuth.Profile,
                             rows: List<Observation>): Terminal {
            if (body.size != 20 || u(body[0]) >= stops.size || u(body[1]) > profile.frames().size ||
                (u(body[2]) and 7.inv()) != 0 || body[3] != 0.toByte() ||
                le32(body, 4) != rows.size.toLong() || rows.isEmpty())
                throw IOException("Malformed CF/1 terminal")
            val duration = le32(body, 8)
            if (duration > 3600000 || rows.sumOf { it.durationMs } > duration)
                throw IOException("Inconsistent CF/1 duration")
            val visited = u(body[1])
            val frameStates = ArrayList<String>()
            for (index in 0 until 8) {
                val state = u(body[12 + index])
                if (state >= states.size || (index >= visited && state != 0))
                    throw IOException("Invalid CF/1 frame status")
                if (index < visited) frameStates.add(states[state])
            }
            return Terminal(stops[u(body[0])], visited, rows.size, u(body[2]), duration, frameStates)
        }

        private fun exact(stream: InputStream, size: Int): ByteArray {
            val bytes = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val count = stream.read(bytes, offset, size - offset)
                if (count <= 0 || count > size - offset) throw EOFException("Truncated CF/1 stream")
                offset += count
            }
            return bytes
        }

        @JvmStatic
        fun decode(stream: InputStream, expected: J1979FreezeUsbAuth.Profile?): Result {
            var profile: J1979FreezeUsbAuth.Profile? = null
            var summary: Terminal? = null
            val rows = ArrayList<Observation>()
            var error: String? = null
            try {
                for (seq in 0 until MAX_STEPS + 2) {
                    val head = exact(stream, 8)
                    val length = header(head, seq)
                    val payload = body(head, exact(stream, length + 4), length)
                    when (u(head[3])) {
                        1 -> {
                            profile = J1979FreezeUsbAuth.Profile.decode(payload)
                            if (expected != null && !payload.contentEquals(expected.encode()))
                                throw IOException("CF/1 plan differs from approved scope")
                        }
                        2 -> {
                            if (rows.size >= MAX_STEPS) throw IOException("CF/1 observation limit exceeded")
                            rows.add(observation(payload))
                        }
                        3 -> {
                            summary = terminal(payload, profile ?: throw IOException("Missing CF/1 plan"), rows)
                            if (stream.read() != -1) throw IOException("Trailing CF/1 bytes")
                            break
                        }
                    }
                }
                if (summary == null) throw IOException("Incomplete CF/1 capture")
            } catch (failure: IOException) {
                error = failure.message ?: "CF/1 read failed"
            }
            return Result(false, false, error == null, false, profile, rows, summary, error)
        }

        @JvmStatic
        @Throws(IOException::class)
        fun inspectPrefix(raw: ByteArray?, expectedVin: String?): Prefix {
            if (raw == null || raw.isEmpty() || raw.size.toLong() > MAX_CAPTURE || !CuWire.validVin(expectedVin))
                throw IOException("Invalid interrupted CF/1 input or independently supplied VIN")
            var profile: J1979FreezeUsbAuth.Profile? = null
            val observations = ArrayList<Observation>()
            var position = 0
            var sequence = 0
            var interruption = "MISSING_TERMINAL"
            val magic = byteArrayOf('C'.code.toByte(), 'F'.code.toByte(), 1)
            while (position < raw.size) {
                if (sequence >= MAX_STEPS + 2) throw IOException("CF/1 prefix record limit")
                val remaining = raw.size - position
                val known = minOf(8, remaining)
                for (index in 0 until minOf(3, known))
                    if (raw[position + index] != magic[index]) throw IOException("Invalid CF/1 partial header")
                if (known >= 4) {
                    val kind = u(raw[position + 3])
                    if (if (sequence == 0) kind != 1 else kind != 2 && kind != 3)
                        throw IOException("Invalid CF/1 prefix kind")
                }
                if (known >= 5 && u(raw[position + 4]) != (sequence and 255))
                    throw IOException("Invalid CF/1 low sequence")
                if (known >= 6 && u(raw[position + 5]) != (sequence shr 8))
                    throw IOException("Invalid CF/1 high sequence")
                if (remaining < 8) { interruption = "TRUNCATED_HEADER"; break }
                val head = raw.copyOfRange(position, position + 8)
                val length = header(head, sequence)
                val kind = u(head[3])
                val size = 8 + length + 4
                if (remaining < size) { interruption = "TRUNCATED_RECORD"; break }
                val payload = body(head, raw.copyOfRange(position + 8, position + size), length)
                when (kind) {
                    1 -> {
                        profile = J1979FreezeUsbAuth.Profile.decode(payload)
                        if (profile.vin != expectedVin) throw IOException("CF/1 prefix VIN differs from independently supplied VIN")
                    }
                    2 -> {
                        if (profile == null || observations.size >= MAX_STEPS)
                            throw IOException("CF/1 prefix observation without plan or beyond limit")
                        observations.add(observation(payload))
                    }
                    3 -> {
                        if (profile == null) throw IOException("CF/1 terminal without plan")
                        if (position + size != raw.size) throw IOException("Trailing complete CF/1 terminal bytes")
                        throw IOException("CF/1 has a terminal; use ordinary inspection")
                    }
                }
                position += size
                sequence++
            }
            return Prefix(profile ?: throw IOException("No complete CF/1 prefix plan"),
                observations, sequence, position, raw.size - position, interruption)
        }
    }

    @Synchronized
    @Throws(IOException::class)
    fun run(profile: J1979FreezeUsbAuth.Profile?, key: ByteArray?, raw: OutputStream?,
            allowed: BooleanSupplier?): Result =
        runRequest(profile, J1979FreezeUsbAuth.hello(profile), key, raw, allowed)

    @Synchronized
    @Throws(IOException::class)
    fun runRequest(profile: J1979FreezeUsbAuth.Profile?, hello: ByteArray?, key: ByteArray?,
                   raw: OutputStream?, allowed: BooleanSupplier?): Result {
        if (used) throw IOException("Fresh CU/2 session required")
        require(profile != null && key != null && key.size == 32 && raw != null && allowed != null) {
            "Invalid CU/2 capture parameters"
        }
        if (hello == null || !CuWire.header(hello, J1979FreezeUsbAuth.HELLO_SIZE, 2, 1) ||
            !CuWire.nonzero(hello, 108, 140) || !hello.copyOfRange(4, 108).contentEquals(profile.encode()))
            throw IOException("CU/2 hello differs from approved scope")
        used = true
        var authenticated = false
        var sent = false
        var error: String? = null
        val copy = ByteArrayOutputStream()
        try {
            reader.current(allowed)
            var start = reader.now()
            link.write(hello, reader.remaining(start, 5000))
            val challenge = reader.exact(68, start, 5000, allowed = allowed)
            val proof = J1979FreezeUsbAuth.proof(hello, challenge, key)
            authenticated = true
            try {
                reader.current(allowed)
                link.checkNoBufferedInput()
                reader.current(allowed)
                link.write(proof, reader.remaining(start, 5000))
                sent = true
            } finally {
                proof.fill(0)
            }
            start = reader.now()
            val limit = profile.budgetMs + 15000
            var finished = false
            fun exact(count: Int) = reader.exact(count, start, limit, raw, copy, allowed, MAX_CAPTURE) {
                copy.size().toLong()
            }
            for (seq in 0 until MAX_STEPS + 2) {
                val head = exact(8)
                val length = header(head, seq)
                val payload = body(head, exact(length + 4), length)
                if (seq == 0 && !payload.contentEquals(profile.encode()))
                    throw IOException("CF/1 scope differs from CU/2 plan")
                if (u(head[3]) == 3) {
                    reader.current(allowed)
                    link.checkNoBufferedInput()
                    reader.current(allowed)
                    reader.remaining(start, limit)
                    finished = true
                    break
                }
            }
            if (!finished) throw IOException("CF/1 record limit exceeded")
        } catch (failure: IOException) {
            error = failure.message ?: "CU/2 exchange failed"
        } finally {
            try { link.close() } catch (failure: IOException) { if (error == null) error = "USB close failed" }
            try { raw.flush() } catch (failure: IOException) { if (error == null) error = "Capture flush failed" }
        }
        val decoded = decode(ByteArrayInputStream(copy.toByteArray()), profile)
        val cancelled = !allowed.asBoolean
        return Result(authenticated, sent, authenticated && sent && !cancelled && error == null &&
            decoded.frameIntegrityVerified, cancelled, decoded.profile, decoded.observations,
            decoded.terminal, error ?: if (cancelled) "CU/2 cancelled" else decoded.error)
    }
}
