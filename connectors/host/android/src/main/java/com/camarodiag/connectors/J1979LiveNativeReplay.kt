package com.camarodiag.connectors

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.Locale
import java.util.zip.CRC32

/** Independent CL/1 replay; offline bytes never prove physical provenance. */
object J1979LiveNativeReplay {
    const val MAX_SAMPLES = 10000
    const val MAX_STEPS = 11010
    const val MAX_RAW = 16 * 1024 * 1024
    const val OBSERVATION_SIZE = 26
    const val FINAL_SIZE = 128
    const val MAX_BODY = OBSERVATION_SIZE + 4095
    const val MAX_CAPTURE = MAX_RAW + (MAX_STEPS + 2) * 38 + 1024
    private val phases = arrayOf("", "INITIAL_VIN", "INVENTORY", "PERIODIC_VIN", "SAMPLE", "FINAL_VIN")
    private val states = arrayOf("", "PASSED", "RAW", "NEGATIVE", "TIMEOUT", "FAILED")
    private val stops = arrayOf(null, "INVALID_SCOPE", "BUDGET_EXHAUSTED", "CONTEXT_CHANGED",
        "TRANSPORT_ERROR", "VIN_UNVERIFIED", "VIN_MISMATCH", "SELECTED_PID_NOT_VERIFIED",
        "SELECTED_RAW_PID_NO_RESPONSE", "SINK_STOPPED", "INVALID_SEQUENCE", "EVIDENCE_LIMIT")
    private const val HEX = "0123456789ABCDEF"
    private fun u(value: Byte) = CuWire.unsigned(value)

    private fun checkWire(ok: Boolean, message: String) {
        if (!ok) throw IOException(message)
    }

    @JvmStatic fun u16(data: ByteArray, at: Int): Int = CuCaptureReader.le16(data, at)
    @JvmStatic fun u32(data: ByteArray, at: Int): Long = CuCaptureReader.le32(data, at)
    @JvmStatic fun hex(data: ByteArray): String = buildString(data.size * 2) {
        for (value in data) {
            append(HEX[u(value) ushr 4])
            append(HEX[u(value) and 15])
        }
    }

    private fun map(vararg values: Pair<String, Any?>): Map<String, Any?> =
        Collections.unmodifiableMap(linkedMapOf(*values))

    private fun <T> frozen(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))

    private fun profileView(p: J1979LiveUsbAuth.Profile): Map<String, Any?> = map(
        "request_can_id" to p.tx.toString(16).uppercase(Locale.ROOT),
        "response_can_id" to p.rx.toString(16).uppercase(Locale.ROOT),
        "extended" to p.extended, "expected_vin" to p.vin, "selected_pids" to frozen(p.pids().toList()),
        "requested_cycles" to p.cycles, "interval_ms" to p.intervalMs,
        "budget_ms" to p.budgetMs, "raw_only" to true
    )

    @JvmStatic
    @Throws(IOException::class)
    fun header(head: ByteArray, seq: Int): Int {
        checkWire(head.size == 8 && head[0] == 'C'.code.toByte() && head[1] == 'L'.code.toByte() &&
            head[2] == 1.toByte() && u(head[3]) in 1..3 && u16(head, 4) == seq, "Invalid CL/1 header")
        val len = u16(head, 6)
        checkWire(len in 1..MAX_BODY, "Invalid CL/1 record size")
        return len
    }

    @JvmStatic
    @Throws(IOException::class)
    fun body(head: ByteArray, tail: ByteArray, length: Int): ByteArray {
        checkWire(length in 1..MAX_BODY && tail.size == length + 4, "Truncated CL/1 record")
        val crc = CRC32().also { it.update(head); it.update(tail, 0, length) }
        checkWire(crc.value == u32(tail, length), "CL/1 CRC mismatch")
        return tail.copyOf(length)
    }

    private fun exact(stream: InputStream, count: Int): ByteArray {
        val data = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val n = stream.read(data, offset, count - offset)
            if (n <= 0 || n > count - offset) throw EOFException("Truncated CL/1 record")
            offset += n
        }
        return data
    }

    private class Event(body: ByteArray) {
        init { checkWire(body.size >= OBSERVATION_SIZE, "Truncated CL/1 observation") }
        val phase = u(body[0])
        val state = u(body[1])
        val accepted = body[2] == 1.toByte()
        val cycle = u16(body, 4)
        val pid = u(body[6])
        val start = u32(body, 8)
        val end = u32(body, 12)
        val timeout = u16(body, 16)
        val exchangeMs = u32(body, 20)
        init {
            val size = u16(body, 18)
            checkWire(phase in 1..5 && state in 1..5 && u(body[2]) <= 1 &&
                body[3] == 0.toByte() && body[7] == 0.toByte() && start <= end &&
                end <= 604000 && timeout in 1..4000 && size <= 4095 &&
                body.size == OBSERVATION_SIZE + size && (state <= 3) == (size > 0) &&
                (accepted || state == 5), "Malformed CL/1 observation")
        }
        val request = body.copyOfRange(24, 26)
        val response = body.copyOfRange(26, body.size)

        fun shape(): Int {
            if (response.isEmpty()) return if (state == 4 || state == 5) state else 0
            if (u(response[0]) == 0x7f) return if (response.size == 3 && response[1] == request[0] &&
                u(response[2]) != 0 && u(response[2]) != 0x78) 3 else 0
            if (u(request[0]) == 9 && u(request[1]) == 2) return if (response.size == 20 &&
                u(response[0]) == 0x49 && u(response[1]) == 2 && u(response[2]) == 1 &&
                CuWire.validVin(String(response, 3, 17, Charsets.US_ASCII))) 1 else 0
            if (u(request[0]) != 1 || response.size < 3 || u(response[0]) != 0x41 ||
                response[1] != request[1]) return 0
            val id = u(request[1])
            if (id % 32 == 0) return if (response.size == 6 &&
                (id != 224 || u(response[5]) and 1 == 0)) 1 else 0
            return 2
        }

        fun view(): Map<String, Any?> = map(
            "phase" to phases[phase], "status" to states[state], "accepted" to accepted,
            "cycle" to if (phase == 4) cycle else null, "pid" to if (phase == 4) pid else null,
            "started_elapsed_ms" to start, "elapsed_ms" to end, "duration_ms" to end - start,
            "exchange_duration_ms" to exchangeMs, "timeout_ms" to timeout, "request_hex" to hex(request),
            "response_hex" to if (response.isEmpty()) null else hex(response)
        )

        fun sample(): Map<String, Any?> = map("cycle" to cycle, "pid" to pid, "elapsed_ms" to end,
            "status" to states[state], "response_hex" to if (response.isEmpty()) null else hex(response))
    }

    private class Replay(private val profile: J1979LiveUsbAuth.Profile) {
        private val selected = profile.pids()
        private val advertised = BooleanArray(256)
        private val rawPerPid = IntArray(32)
        private val rows = ArrayList<Map<String, Any?>>()
        private val observations = ArrayList<Map<String, Any?>>()
        private var stage = 1
        private var stop = 0
        private var steps = 0
        private var samples = 0
        private var raw = 0
        private var negative = 0
        private var timeouts = 0
        private var rawBytes = 0
        private var cycles = 0
        private var base = 0
        private var index = 0
        private var duration = 0L
        private var lastIdentity = 0L
        private var cycleStart = -1L
        private var initial = false
        private var finalIdentity = false
        private var inventoryComplete = false
        private var completed = false
        private var earlyIdentity = false

        private fun abort(reason: Int, now: Long) {
            stop = reason
            stage = 7
            duration = now
        }

        private fun phase(now: Long): Int {
            if (stage == 7 || now >= profile.budgetMs) return 7
            if (stage == 4 && index == 0) {
                if (cycleStart >= 0 && now - cycleStart < profile.intervalMs) return 6
                if (now - lastIdentity >= 30000) return 3
            }
            return stage
        }

        private fun canRetain(phase: Int): Boolean {
            val maximum = when (phase) {
                1, 3, 5 -> 20
                2 -> 6
                4 -> 4095
                else -> 0
            }
            return maximum > 0 && maximum <= MAX_RAW - rawBytes
        }

        private fun capacityStop(now: Long): Boolean {
            var p = phase(now)
            if (p == 4 && index == 0 && !earlyIdentity && now - lastIdentity >= 29000) p = 3
            if (stage == 4 && index == 0 && cycleStart >= 0 && now - cycleStart < profile.intervalMs + 25L) return false
            return p in 1..5 && !canRetain(p)
        }

        private fun request(phase: Int): ByteArray = when (phase) {
            1, 3, 5 -> byteArrayOf(9, 2)
            2 -> byteArrayOf(1, base.toByte())
            4 -> byteArrayOf(1, selected[index].toByte())
            else -> throw IOException("CL/1 request outside sequence")
        }

        fun observe(e: Event) {
            checkWire(e.start >= duration && stage != 7 && steps < MAX_STEPS,
                "Overlapping or post-terminal CL/1 observation")
            var p = phase(e.start)
            if (e.phase == 3 && p == 4 && index == 0 && !earlyIdentity) p = 3
            checkWire(p == e.phase && p in 1..5, "Unexpected CL/1 phase")
            checkWire(canRetain(p), "CL/1 request exceeds remaining evidence capacity")
            val req = request(p)
            checkWire(req.contentEquals(e.request) && e.timeout <= profile.budgetMs - e.start &&
                e.cycle == (if (p == 4) cycles + 1 else 0) &&
                e.pid == (if (p == 4) u(req[1]) else 0), "CL/1 request outside approved scope")
            if (p == 4 && index == 0) cycleStart = e.start
            steps++
            duration = e.end
            observations.add(e.view())
            if (e.end >= profile.budgetMs) {
                checkWire(!e.accepted, "Late response accepted")
                abort(2, e.end)
                return
            }
            if (e.end - e.start > e.timeout || e.exchangeMs > e.timeout) {
                checkWire(!e.accepted, "Exchange exceeded deadline")
                abort(4, e.end)
                return
            }
            if (!e.accepted) {
                checkWire(e.state == 5 && e.response.isEmpty(), "Rejected event carries data")
                abort(4, e.end)
                return
            }
            checkWire(e.state in 1..4 && e.shape() == e.state, "CL/1 response not correlated")
            rawBytes += e.response.size
            checkWire(rawBytes <= MAX_RAW, "CL/1 raw evidence limit")
            if (p == 1 || p == 3 || p == 5) {
                if (e.state != 1) { abort(5, e.end); return }
                if (String(e.response, 3, 17, Charsets.US_ASCII) != profile.vin) { abort(6, e.end); return }
                lastIdentity = e.end
                when (p) {
                    1 -> { initial = true; stage = 2 }
                    3 -> { earlyIdentity = true; stage = 4 }
                    else -> {
                        finalIdentity = true
                        stage = 7
                        if (selected.indices.any { rawPerPid[it] == 0 }) { stop = 8; return }
                        completed = true
                    }
                }
                return
            }
            if (p == 2) {
                if (e.state != 1) { abort(7, e.end); return }
                for (bit in 0 until 31) {
                    if (u(e.response[2 + bit / 8]) and (0x80 ushr (bit % 8)) != 0) advertised[base + bit + 1] = true
                }
                val more = u(e.response[5]) and 1 != 0
                inventoryComplete = !more
                if (selected.all { advertised[it] }) stage = 4
                else if (!more || base == 224) abort(7, e.end)
                else base += 32
                return
            }
            checkWire(p == 4 && e.state in 2..4, "Invalid CL/1 sample state")
            samples++
            when (e.state) {
                2 -> { raw++; rawPerPid[index]++ }
                3 -> negative++
                else -> timeouts++
            }
            rows.add(e.sample())
            index++
            if (index == selected.size) {
                index = 0
                earlyIdentity = false
                cycles++
                if (cycles == profile.cycles) stage = 5
            }
        }

        private fun summaryWire(): ByteArray {
            val wire = ByteArray(FINAL_SIZE)
            val b = ByteBuffer.wrap(wire).order(ByteOrder.LITTLE_ENDIAN)
            val flags = (if (initial) 1 else 0) or (if (finalIdentity) 2 else 0) or
                (if (inventoryComplete) 4 else 0) or (if (completed) 8 else 0)
            b.put(stop.toByte()).put(flags.toByte()).putShort(cycles.toShort())
                .putInt(steps).putInt(samples).putInt(raw).putInt(negative).putInt(timeouts)
                .putInt(duration.toInt()).putInt(rawBytes)
            for (pid in 1..255) if (advertised[pid]) {
                val at = 32 + pid / 8
                wire[at] = (u(wire[at]) or (1 shl (pid % 8))).toByte()
            }
            b.position(64)
            for (count in rawPerPid) b.putShort(count.toShort())
            return wire
        }

        fun finish(body: ByteArray): Map<String, Any?> {
            checkWire(body.size == FINAL_SIZE, "Malformed CL/1 terminal")
            val reason = u(body[0])
            checkWire(reason < stops.size && reason !in listOf(1, 9, 10) &&
                u(body[1]) and 15.inv() == 0, "Malformed CL/1 terminal")
            val end = u32(body, 24)
            checkWire(end >= duration && end <= 604000, "Invalid terminal duration")
            if (stage != 7) {
                checkWire(reason in listOf(2, 3, 11) && (reason != 2 || profile.budgetMs - end <= 1) &&
                    (reason != 11 || capacityStop(end)), "Unsupported terminal state")
                abort(reason, end)
            }
            checkWire(body.contentEquals(summaryWire()), "CL/1 terminal counters differ from replay")
            val ids = frozen((1..255).filter { advertised[it] })
            val counts = frozen(selected.indices.map { map("pid" to selected[it], "count" to rawPerPid[it]) })
            return map("stop_reason" to stops[stop], "completed" to completed, "initial_identity_verified" to initial,
                "final_identity_verified" to finalIdentity, "inventory_complete" to inventoryComplete,
                "completed_cycles" to cycles, "steps" to steps, "samples" to samples, "raw" to raw,
                "negative" to negative, "timeout" to timeouts, "duration_ms" to duration, "raw_bytes" to rawBytes,
                "advertised_pids" to ids, "raw_per_pid" to counts)
        }

        fun prefix(): Map<String, Any?> = map("initial_identity_verified_in_prefix" to initial,
            "final_identity_observed_in_prefix" to finalIdentity, "complete_observations" to steps,
            "last_elapsed_ms" to duration, "raw_bytes" to rawBytes, "completed_cycles_in_prefix" to cycles,
            "raw_samples_in_prefix" to raw, "negative_samples_in_prefix" to negative,
            "timeout_samples_in_prefix" to timeouts, "observations" to frozen(observations), "samples" to frozen(rows))

        fun result(): Map<String, Any?> = map("schema" to "camarodiag-j1979-live-inspection-v1",
            "verified" to true, "raw_only" to true, "declared_vin" to profile.vin,
            "channel" to map("kind" to "can", "tx" to profile.tx, "rx" to profile.rx),
            "completed" to completed, "final_identity_verified" to finalIdentity, "samples" to frozen(rows),
            "observations" to frozen(observations), "physical_vehicle_verified" to false, "authenticated" to false)
    }

    class Result(
        @JvmField val profile: J1979LiveUsbAuth.Profile?, @JvmField val observationsReceived: Int,
        @JvmField val captureIntegrityVerified: Boolean, @JvmField val deviceCredentialAuthenticated: Boolean,
        @JvmField val clientProofSent: Boolean, @JvmField val cancelled: Boolean, @JvmField val source: String,
        summary: Map<String, Any?>?, result: Map<String, Any?>?, @JvmField val error: String?,
        @JvmField val hostElapsedMs: Long?
    ) {
        @JvmField val summary = if (captureIntegrityVerified) summary else null
        @JvmField val result = if (captureIntegrityVerified) result else null
        fun completed(): Boolean = captureIntegrityVerified && summary?.get("completed") == true

        fun report(): Map<String, Any?> {
            val report = LinkedHashMap(map("schema" to "camarodiag-functional-j1979-native-live-v1",
                "protocol" to "SAE-J1979", "source" to source, "profile" to profile?.let(::profileView),
                "capture_integrity_verified" to captureIntegrityVerified,
                "device_credential_authenticated" to deviceCredentialAuthenticated,
                "hardware_provenance_verified" to false, "evidence_authenticated" to false,
                "physical_vehicle_verified" to false, "permits_programming" to false,
                "observations_received" to observationsReceived, "summary" to summary, "result" to result, "error" to error))
            if (source == "LIVE_USB_CU3") {
                report["client_proof_sent"] = clientProofSent
                report["cancelled"] = cancelled
            }
            if (hostElapsedMs != null) report["host_elapsed_ms"] = hostElapsedMs
            return Collections.unmodifiableMap(report)
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun inspectPrefix(bytes: ByteArray, expectedVin: String?): Map<String, Any?> {
        checkWire(CuWire.validVin(expectedVin), "Independently supplied VIN required")
        checkWire(bytes.isNotEmpty() && bytes.size <= MAX_CAPTURE, "Invalid CL/1 input size")
        var position = 0
        var sequence = 0
        var profile: J1979LiveUsbAuth.Profile? = null
        var replay: Replay? = null
        var interruption = "MISSING_TERMINAL"
        val magic = byteArrayOf('C'.code.toByte(), 'L'.code.toByte(), 1)
        while (position < bytes.size) {
            checkWire(sequence < MAX_STEPS + 2, "CL/1 record count exceeded")
            val remaining = bytes.size - position
            val head = bytes.copyOfRange(position, position + minOf(8, remaining))
            for (i in 0 until minOf(3, head.size)) checkWire(head[i] == magic[i], "Invalid partial CL/1 header")
            if (head.size >= 4) checkWire(if (sequence == 0) u(head[3]) == 1 else u(head[3]) in 2..3,
                "Invalid CL/1 record kind")
            if (head.size >= 5) checkWire(u(head[4]) == (sequence and 255), "Invalid partial CL/1 sequence")
            if (head.size >= 6) checkWire(u(head[5]) == (sequence ushr 8), "Invalid partial CL/1 sequence")
            if (remaining < 8) { interruption = "TRUNCATED_HEADER"; break }
            val len = header(head, sequence)
            val kind = u(head[3])
            checkWire((kind != 1 || len == 67) && (kind != 2 || len >= 26) &&
                (kind != 3 || len == FINAL_SIZE), "Invalid CL/1 body length")
            val size = 8 + len + 4
            if (remaining < size) { interruption = "TRUNCATED_RECORD"; break }
            val data = body(head, bytes.copyOfRange(position + 8, position + size), len)
            when (kind) {
                1 -> {
                    val decoded = J1979LiveUsbAuth.Profile.decode(data)
                    checkWire(decoded.vin == expectedVin, "CL/1 plan differs from independent VIN")
                    profile = decoded
                    replay = Replay(decoded)
                }
                2 -> (replay ?: throw IOException("Missing CL/1 plan")).observe(Event(data))
                else -> {
                    (replay ?: throw IOException("Missing CL/1 plan")).finish(data)
                    checkWire(position + size == bytes.size, "Trailing CL/1 bytes")
                    throw IOException("CL/1 contains a terminal record; use inspect")
                }
            }
            position += size
            sequence++
        }
        val verifiedProfile = profile ?: throw IOException("No complete CL/1 plan")
        val result = LinkedHashMap((replay ?: throw IOException("No complete CL/1 plan")).prefix())
        checkWire(sequence <= MAX_STEPS + 1, "CL/1 record count exceeded")
        result.putAll(map("schema" to "camarodiag-j1979-native-live-prefix-v1", "protocol" to "SAE-J1979",
            "source" to "OFFLINE_CL1_PREFIX", "profile" to profileView(verifiedProfile), "prefix_integrity_verified" to true,
            "capture_integrity_verified" to false, "execution_completed" to false, "terminal_present" to false,
            "expected_vin_independently_supplied" to true, "complete_records" to sequence, "last_complete_offset" to position,
            "discarded_tail_bytes" to bytes.size - position, "interruption" to interruption,
            "physical_vehicle_verified" to false, "hardware_provenance_verified" to false,
            "evidence_authenticated" to false, "permits_programming" to false))
        return Collections.unmodifiableMap(result)
    }

    @JvmStatic
    fun decode(stream: InputStream, expected: J1979LiveUsbAuth.Profile?): Result {
        var profile: J1979LiveUsbAuth.Profile? = null
        var replay: Replay? = null
        var summary: Map<String, Any?>? = null
        var received = 0
        var total = 0
        var error: String? = null
        try {
            for (seq in 0 until MAX_STEPS + 2) {
                val head = exact(stream, 8)
                val len = header(head, seq)
                total += 8 + len + 4
                checkWire(total <= MAX_CAPTURE, "CL/1 capture byte limit")
                val data = body(head, exact(stream, len + 4), len)
                val kind = u(head[3])
                if (profile == null) {
                    checkWire(kind == 1, "Missing CL/1 plan")
                    val decoded = J1979LiveUsbAuth.Profile.decode(data)
                    checkWire(expected == null || data.contentEquals(expected.encode()), "CL/1 scope differs from CU/3")
                    profile = decoded
                    replay = Replay(decoded)
                } else if (kind == 2) {
                    received++
                    (replay ?: throw IOException("Missing CL/1 plan")).observe(Event(data))
                } else if (kind == 3) {
                    summary = (replay ?: throw IOException("Missing CL/1 plan")).finish(data)
                    checkWire(stream.read() == -1, "Trailing CL/1 bytes")
                    break
                } else throw IOException("Duplicate CL/1 plan")
            }
            checkWire(profile != null && summary != null, "Incomplete CL/1 capture")
        } catch (failure: IOException) {
            error = failure.message ?: "CL/1 replay failed"
            summary = null
        } catch (failure: IllegalArgumentException) {
            error = failure.message ?: "CL/1 replay failed"
            summary = null
        }
        return Result(profile, received, error == null, false, false, false, "OFFLINE_CL1",
            summary, if (error == null) replay?.result() else null, error, null)
    }
}
