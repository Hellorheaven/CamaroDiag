package com.camarodiag.gen5.ui

import com.camarodiag.gen5.ui.J1979CatalogueJson.array
import com.camarodiag.gen5.ui.J1979CatalogueJson.bool
import com.camarodiag.gen5.ui.J1979CatalogueJson.get
import com.camarodiag.gen5.ui.J1979CatalogueJson.int as jsonInt
import com.camarodiag.gen5.ui.J1979CatalogueJson.obj
import com.camarodiag.gen5.ui.J1979CatalogueJson.text
import com.camarodiag.gen5.ui.J1979DocumentedCatalogue.Companion.hex
import java.util.Base64
import java.io.ByteArrayInputStream
import com.camarodiag.connectors.J1979LiveNativeReplay
import java.util.Locale

/**
 * Replays application bytes, identity, support pages and raw-only sample
 * chronology from the original live report. This does NOT replay an ELM
 * transcript or prove transport/physical provenance. The Linux verifier
 * independently replays all transport-specific evidence before comparison.
 */
internal object J1979CatalogueProjection {
    const val SCHEMA = "camarodiag-j1979-catalogue-local-projection-v1"
    private const val CAN = "camarodiag-j1979-live-v1"
    private const val LEGACY = "camarodiag-j1979-legacy-live-v1"
    private val schemas = setOf(CAN, LEGACY)
    private val aliases = listOf("j1979Live", "j1979_live", "liveReport", "live")
    private val statuses = setOf("PASSED", "RAW", "NEGATIVE", "TIMEOUT", "FAILED", "NOT_TESTED")

    data class Sample(val pid: Int, val cycle: Int, val elapsed: Int, val status: String, val response: String?)
    data class Source(val vin: String, val completed: Boolean, val protocol: String,
                      val channel: Map<String, Any>, val samples: List<Sample>, val finalIdentityVerified: Boolean)
    data class Result(val document: Map<String, Any?>, val catalogueSha: String,
                      val referenceSha: String, val reportSha: String, val counts: Map<String, Int>)

    private fun requiredText(row: Map<String, Any?>, key: String) = text(get(row, key))
    private fun optionalText(row: Map<String, Any?>, key: String): String? =
        get(row, key)?.let(::text)
    private fun int(row: Map<String, Any?>, key: String, low: Int, high: Int) = jsonInt(get(row, key), low, high)
    private fun flagFalse(row: Map<String, Any?>, key: String) = get(row, key).let { it == null || it == false }
    private fun id(row: Map<String, Any?>, key: String, max: Int): Int {
        val raw = requiredText(row, key)
        require(raw.matches(Regex("[0-9A-Fa-f]{1,8}"))) { "Invalid channel ID" }
        val value = raw.toLong(16)
        require(value in 0..max.toLong()) { "Channel ID out of range" }
        return value.toInt()
    }
    private fun selected(value: Any?): List<Int> {
        val pids = if (value is String) {
            require(value.length <= 44 && value.matches(Regex("[A-Za-z0-9+/]+={0,2}"))) { "Invalid PID base64" }
            val bytes = Base64.getDecoder().decode(value)
            require(Base64.getEncoder().encodeToString(bytes) == value) { "Noncanonical PID list" }
            bytes.map { it.toInt() and 255 }
        } else array(value, 1, 32).map { jsonInt(it, 1, 255) }
        require(pids.size in 1..32 && pids.distinct().size == pids.size &&
            pids.all { it in 1..255 && it % 32 != 0 }) { "Invalid selected PIDs" }
        return pids
    }
    fun validVin(vin: String) = vin.matches(Regex("[A-HJ-NPR-Z0-9]{17}"))
    private fun positiveVin(data: ByteArray?, vin: String) = data != null && data.size == 20 &&
        data[0] == 0x49.toByte() && data[1] == 2.toByte() && data[2] == 1.toByte() &&
        data.copyOfRange(3, 20).contentEquals(vin.toByteArray(Charsets.US_ASCII))
    private fun negative(data: ByteArray?, mode: Byte) = data != null && data.size == 3 &&
        data[0] == 0x7F.toByte() && data[1] == mode && data[2] !in setOf(0.toByte(), 0x78.toByte())

    private fun noMeasurement(row: Map<String, Any?>) {
        require(get(row, "measurement") == null) { "Raw row carries numerical value" }
        val multi = get(row, "multi_signals")
        require(multi == null || multi is List<*> && multi.isEmpty()) { "Raw row carries interpreted signals" }
    }
    private data class Entry(val row: Map<String, Any?>, val sample: Boolean, val index: Int,
                             val start: Int, val end: Int, val phase: String) {
        val priority: Int get() = if (sample) 1 else if (phase == "FINAL_VIN") 2 else 0
    }
    private fun entries(rows: List<Any?>, sample: Boolean): List<Entry> {
        var previous = 0
        return rows.mapIndexed { index, value ->
            val row = obj(value)
            val start = int(row, "started_elapsed_ms", 0, Int.MAX_VALUE)
            val end = int(row, "elapsed_ms", start, Int.MAX_VALUE)
            require(start >= previous) { "Reordered observations in source array" }
            previous = end
            Entry(row, sample, index, start, end, requiredText(row, "phase"))
        }
    }
    fun replay(raw: ByteArray, expectedVin: String): Source {
        require(validVin(expectedVin)) { "Expected VIN required" }
        var root = obj(J1979CatalogueJson.parse(raw, J1979DocumentedCatalogue.MAX_REPORT_BYTES))
        if (get(root, "schema") !in schemas) {
            val candidates = aliases.mapNotNull { alias ->
                val item = root[alias] as? Map<*, *> ?: return@mapNotNull null
                obj(item).takeIf { get(it, "schema") in schemas }
            }
            require(candidates.size == 1) { "Exactly one original live report required" }
            root = candidates.single()
        }
        val schema = requiredText(root, "schema")
        require(schema in schemas) { "Original live report required" }
        val legacy = schema == LEGACY
        val result = if ("result" in root) obj(root["result"]) else root
        require(bool(get(result, "raw_only")) && requiredText(result, "declared_vin") == expectedVin) {
            "Raw-only recording and matching independent VIN required"
        }
        require(listOf(root, result).all { flagFalse(it, "physical_vehicle_verified") &&
            flagFalse(it, "whole_vehicle_coverage_verified") }) { "Unsupported physical verification claim" }
        val channel: Map<String, Any> = if (legacy) {
            require(flagFalse(root, "physically_addressed")) { "Legacy cannot claim physical addressing" }
            val protocol = jsonInt(get(root, "expected_protocol") ?: get(root, "profile"), 1, 5)
            val source = id(root, "selected_response_source", 254)
            require(source != 0 && source != 0xF1) { "Invalid legacy response source" }
            mapOf("kind" to "legacy", "protocol" to protocol, "source" to source)
        } else {
            val tx = id(root, "tx_can_id", 0x1FFFFFFF)
            val rx = id(root, "rx_can_id", 0x1FFFFFFF)
            val target = (tx ushr 8) and 255
            require(tx in 0x7E0..0x7E7 && rx == tx + 8 ||
                tx and 0x1FFF00FF == 0x18DA00F1 && target < 0xF0 && rx == (0x18DAF100 or target)) {
                "Invalid physical CAN pair"
            }
            mapOf("kind" to "can", "tx" to tx, "rx" to rx)
        }
        val pids = selected(get(result, "selected_pids"))
        val cycles = int(result, "requested_cycles", 1, 1000)
        val interval = int(result, "interval_ms", 100, 60_000)
        val budget = int(result, "budget_ms", 1, 600_000)
        require(cycles * pids.size <= 10_000) { "Too many requested observations" }
        val admission = entries(array(get(result, "admission"), 1, 256), false)
        val observations = entries(array(get(result, "observations"), 0, 10_000), true)
        val combined = (admission + observations).sortedWith(
            compareBy<Entry> { it.start }.thenBy { it.priority }.thenBy { it.end }.thenBy { it.index }
        )
        val advertised = sortedSetOf<Int>()
        val samples = ArrayList<Sample>()
        val rawSeen = hashSetOf<Int>()
        var identity = false
        var initial = false
        var final = false
        var terminal = false
        var inventoryComplete = false
        var nextPage = 0
        var lastEnd = 0
        var lastIdentity = -1
        var lastCycleStart = -1
        var rawBytes = 0

        for (entry in combined) {
            val row = entry.row
            val start = entry.start; val end = entry.end
            val duration = int(row, "duration_ms", 0, Int.MAX_VALUE)
            require(start >= lastEnd && kotlin.math.abs(end.toLong() - start - duration) <= 2 &&
                !terminal && !final) { "Overlapping, inconsistent or post-terminal observation" }
            lastEnd = end
            val status = requiredText(row, "status")
            require(status in statuses) { "Unknown observation status" }
            val request = hex(requiredText(row, "request_hex"), 2)
            val responseHex = optionalText(row, "response_hex")
            val response = responseHex?.let { hex(it) }
            rawBytes += response?.size ?: 0
            require(rawBytes <= 16 * 1024 * 1024) { "Raw evidence exceeds byte limit" }
            noMeasurement(row)
            if (status in setOf("PASSED", "RAW", "NEGATIVE"))
                require(end <= budget + 1) { "Successful response beyond budget" }
            if (status == "NEGATIVE") require(request.isNotEmpty() && negative(response, request[0])) {
                "Uncorrelated negative response"
            }
            if (entry.sample) {
                val cycle = samples.size / pids.size + 1
                val pid = pids[samples.size % pids.size]
                require(entry.phase == "SAMPLE" && identity && pids.all { it in advertised } && cycle <= cycles &&
                    entry.index == samples.size && int(row, "cycle", 1, cycles) == cycle &&
                    int(row, "pid", 1, 255) == pid && request.contentEquals(byteArrayOf(1, pid.toByte())) &&
                    status != "PASSED") { "Unexpected sample or missing raw admission" }
                if (samples.size % pids.size == 0) {
                    require(lastCycleStart < 0 || start.toLong() + 2 >= lastCycleStart.toLong() + interval) {
                        "Cycle interval violated"
                    }
                    require(lastIdentity >= 0 && start - lastIdentity <= 30_002) { "Missing periodic VIN check" }
                    lastCycleStart = start
                }
                if (status == "RAW") {
                    require(response != null && response.size >= 3 && response[0] == 0x41.toByte() &&
                        response[1].toInt() and 255 == pid && (!legacy || response.size <= 7) &&
                        optionalText(row, "detail") == "RAW_UNINTERPRETED") { "Uncorrelated raw sample" }
                    rawSeen.add(pid)
                }
                samples.add(Sample(pid, cycle, end, status, responseHex?.uppercase(Locale.ROOT)))
            } else if (entry.phase in setOf("INITIAL_VIN", "PERIODIC_VIN", "FINAL_VIN")) {
                require(request.contentEquals(byteArrayOf(9, 2)) && status != "RAW") { "Unexpected VIN request" }
                if (entry.phase == "INITIAL_VIN") {
                    require(!initial && nextPage == 0 && samples.isEmpty() && entry.index == 0) {
                        "Duplicate or late initial VIN"
                    }
                    initial = true
                } else require(identity && pids.all { it in advertised } && samples.size % pids.size == 0) {
                    "Misplaced identity recheck"
                }
                identity = status == "PASSED" && positiveVin(response, expectedVin)
                if (status == "PASSED") require(identity) { "VIN response differs from independent target" }
                if (identity) lastIdentity = end else terminal = true
                if (entry.phase == "FINAL_VIN") {
                    require(samples.size == cycles * pids.size) { "Premature final VIN" }
                    final = identity
                }
            } else if (entry.phase == "INVENTORY") {
                require(identity && samples.isEmpty() && !inventoryComplete && nextPage <= 224 &&
                    request.contentEquals(byteArrayOf(1, nextPage.toByte())) && status != "RAW") {
                    "Unexpected support page"
                }
                if (status == "PASSED") {
                    require(response != null && response.size == 6 && response[0] == 0x41.toByte() &&
                        response[1].toInt() and 255 == nextPage) { "Invalid support bitmap" }
                    for (bit in 0 until 31) {
                        if (response[2 + bit / 8].toInt() and (0x80 ushr (bit % 8)) != 0)
                            advertised.add(nextPage + bit + 1)
                    }
                    val next = response[5].toInt() and 1 != 0
                    require(!next || nextPage < 224) { "Support bitmap exceeds PID FF" }
                    inventoryComplete = !next
                    nextPage += 32
                } else terminal = true
            } else throw IllegalArgumentException("Unexpected raw-only admission phase")
            if (status == "FAILED" || status == "NOT_TESTED") terminal = true
        }
        require(initial && samples.isNotEmpty()) { "No admitted samples to project" }
        require(requiredText(result, "observed_vin") == expectedVin) { "Declared initial VIN differs" }
        val declared = array(get(result, "advertised_pids"), 0, 255).map { jsonInt(it, 1, 255) }
        require(declared.distinct().size == declared.size &&
            declared.sorted() == advertised.toList()) { "Reported inventory differs" }
        require(bool(get(result, "inventory_complete")) == inventoryComplete) { "Inventory completeness differs" }
        val recorded = int(result, "completed_cycles", 0, cycles)
        val possible = samples.size / pids.size
        var complete = bool(get(result, "completed"))
        require(recorded == possible || !complete && terminal && samples.size % pids.size == 0 &&
            recorded == possible - 1) { "Recorded cycles differ from evidence" }
        require(int(result, "duration_ms", 0, Int.MAX_VALUE).toLong() + 2 >= lastEnd) { "Report precedes evidence" }
        require(!complete || final && !terminal && recorded == cycles && pids.all { it in rawSeen } &&
            get(result, "stop_reason") == null) { "Unsupported completed report" }
        require(complete || !optionalText(result, "stop_reason").isNullOrBlank()) { "Missing partial report reason" }
        if ("result" in root && get(root, "completed") != null && !bool(get(root, "completed"))) complete = false
        return Source(expectedVin, complete, if (legacy) "LEGACY" else "CAN", channel, samples.toList(), final)
    }

    const val NATIVE_SCHEMA = "camarodiag-j1979-native-catalogue-projection-v1"
    private fun isNative(bytes: ByteArray) = bytes.size >= 3 &&
        bytes[0] == 'C'.code.toByte() && bytes[1] == 'L'.code.toByte() && bytes[2] == 1.toByte()

    /** Original native bytes, independently replayed by the connector module.
     * A verified CL/1 application sequence is not a CAN frame replay. */
    private fun replayNative(raw: ByteArray, expectedVin: String): Source {
        require(validVin(expectedVin) && raw.size <= J1979LiveNativeReplay.MAX_CAPTURE)
        val report = J1979LiveNativeReplay.decode(ByteArrayInputStream(raw),null)
        require(report.captureIntegrityVerified && report.profile != null &&
            report.summary != null && report.result != null) { "Invalid or incomplete CL/1 evidence" }
        val profile = requireNotNull(report.profile)
        require(profile.vin == expectedVin) { "Native capture VIN differs from independently supplied VIN" }
        val summary = requireNotNull(report.summary)
        val result = requireNotNull(report.result)
        val samples = array(result["samples"],0,J1979LiveNativeReplay.MAX_SAMPLES).map { item ->
            val row=obj(item)
            Sample(int(row,"pid",1,255),int(row,"cycle",1,1000),
                int(row,"elapsed_ms",0,604000),requiredText(row,"status"),optionalText(row,"response_hex"))
        }
        return Source(profile.vin,summary["completed"]==true,"CAN",
            mapOf("kind" to "can","tx" to profile.tx,"rx" to profile.rx),samples,
            summary["final_identity_verified"]==true)
    }

    fun build(catalogueBytes: ByteArray, verifiedReferenceSha: String,
              sourceBytes: ByteArray, expectedVin: String): Result {
        val catalogue = J1979DocumentedCatalogue.parse(catalogueBytes, verifiedReferenceSha)
        val native = isNative(sourceBytes)
        val source = if(native) replayNative(sourceBytes,expectedVin) else replay(sourceBytes, expectedVin)
        val counts = sortedMapOf<String, Int>()
        var remaining = J1979DocumentedCatalogue.MAX_ROWS
        val rows = source.samples.map { sample ->
            val projected = catalogue.project(sample.pid, source.protocol, sample.cycle,
                sample.elapsed, sample.status, sample.response, remaining)
            remaining -= projected.signalCount
            val status = projected.row.getValue("projection_status") as String
            counts[status] = (counts[status] ?: 0) + 1
            projected.row
        }
        val sourceSha = J1979DocumentedCatalogue.sha(sourceBytes)
        val output = linkedMapOf<String, Any?>(
            "schema" to (if(native) NATIVE_SCHEMA else SCHEMA), "reference_edition" to catalogue.edition,
            "reference_sha256" to catalogue.referenceSha256, "catalogue_sha256" to catalogue.catalogueSha256,
            (if(native) "source_capture_sha256" else "source_report_sha256") to sourceSha, "expected_vin_independently_supplied" to true,
            "declared_vin" to source.vin, "channel" to source.channel, "source_completed" to source.completed,
            "final_identity_verified" to source.finalIdentityVerified,
            "source_replay_verified" to native, "reference_authenticity_verified" to false,
            "normative_compliance_verified" to false, "physical_vehicle_verified" to false,
            "authenticated" to false, "permits_programming" to false, "diagnostic_verdict" to null,
            "counts" to counts.toMap(), "rows" to rows
        )
        if(native) {
            output["native_capture_replayed"]=true
            output["transport_frames_replayed"]=false
        } else output["source_application_replay_verified"]=true
        return Result(output, catalogue.catalogueSha256, catalogue.referenceSha256, sourceSha, counts.toMap())
    }
}
