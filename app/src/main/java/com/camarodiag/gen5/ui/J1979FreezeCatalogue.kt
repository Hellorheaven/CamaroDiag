package com.camarodiag.gen5.ui

import com.camarodiag.gen5.data.obd.CamaroDiagConnectorFreezeCampaign
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Frame-specific documented definitions, never inferred from live Mode 01.
 * Only the original independently replayed CF/1 capture can admit a value. */
internal object J1979FreezeCatalogue {
    const val SCHEMA = "camarodiag-j1979-freeze-catalogue-v1"
    const val PROJECTION = "camarodiag-j1979-native-freeze-catalogue-projection-v1"
    private const val LIMIT = 25000
    data class Catalogue(
        val edition: String, val referenceName: String, val referenceSha: String,
        val sha: String, val definitions: Map<Pair<Int,Int>,J1979DocumentedCatalogue.Definition>
    )
    fun parse(raw: ByteArray, pin: String): Catalogue {
        val root = J1979CatalogueJson.fields(
            J1979CatalogueJson.parse(raw,J1979DocumentedCatalogue.MAX_CATALOGUE_BYTES),
            setOf("schema","edition","reference_name","reference_sha256","frames"))
        require(root["schema"] == SCHEMA) { "Explicit Mode 02 catalogue required" }
        val ref = J1979DocumentedCatalogue.digest(J1979CatalogueJson.label(root["reference_sha256"]))
        require(ref == J1979DocumentedCatalogue.digest(pin))
        val seen = mutableSetOf<Int>()
        val definitions = linkedMapOf<Pair<Int,Int>,J1979DocumentedCatalogue.Definition>()
        for (entry in J1979CatalogueJson.array(root["frames"],1,8)) {
            val frame = J1979CatalogueJson.fields(entry,setOf("frame","definitions"))
            val number = J1979CatalogueJson.int(frame["frame"],0,255)
            require(seen.add(number)) { "Duplicate frame definition" }
            for (row in J1979CatalogueJson.array(frame["definitions"],1,247)) {
                val definition = J1979DocumentedCatalogue.definition(row)
                require(definition.pid != 2 && definition.width <= 4092 && definition.protocols == listOf("CAN"))
                require(definitions.put(number to definition.pid,definition) == null) { "Duplicate frame/PID" }
            }
        }
        return Catalogue(J1979CatalogueJson.label(root["edition"]),J1979CatalogueJson.label(root["reference_name"]),
            ref,J1979DocumentedCatalogue.sha(raw),definitions.toMap())
    }

    suspend fun build(raw: ByteArray, referenceSha: String, capture: ByteArray,
                      expectedVin: String): J1979CatalogueProjection.Result {
        require(expectedVin.matches(Regex("[A-HJ-NPR-Z0-9]{17}")))
        val catalogue = parse(raw,referenceSha)
        val (profile,result) = CamaroDiagConnectorFreezeCampaign.inspectCapture(capture,expectedVin)
        val identity = result.identityRechecked && result.initialVin == expectedVin && result.finalVin == expectedVin
        val rows = mutableListOf<Map<String,Any?>>()
        val frames = mutableListOf<Map<String,Any?>>()
        val counts = sortedMapOf<String,Int>()
        val consumed = mutableSetOf<Int>()
        var signals = 0
        for (frame in result.frames) {
            currentCoroutineContext().ensureActive()
            val stable = frame.triggerStable && frame.triggerDtc != null && frame.triggerDtc == frame.finalTriggerDtc
            frames.add(mapOf("frame" to frame.frame,"source_status" to frame.status,
                "trigger_dtc" to frame.triggerDtc,"final_trigger_dtc" to frame.finalTriggerDtc,
                "trigger_stable" to stable,"inventory_complete" to frame.inventoryComplete))
            for (stepIndex in frame.measurementSteps) {
                currentCoroutineContext().ensureActive()
                require(stepIndex in result.steps.indices && consumed.add(stepIndex))
                val step = result.steps[stepIndex]
                val request = J1979DocumentedCatalogue.hex(step.requestHex,3)
                require(request.size == 3 && request[0].toInt() == 2 && (request[2].toInt() and 255) == frame.frame)
                val pid = request[1].toInt() and 255
                require(pid != 2 && pid % 32 != 0 && pid in frame.advertisedPids)
                val row = linkedMapOf<String,Any?>("frame" to frame.frame,"pid" to pid,
                    "step_index" to stepIndex,"source_status" to step.status,
                    "response_hex" to step.responseHex,"signals" to emptyList<Any>())
                val status = when {
                    !identity -> "IDENTITY_NOT_RECHECKED"
                    !stable -> "TRIGGER_NOT_STABLE"
                    step.status !in listOf("RAW","PASSED") -> "NO_CORRELATED_RAW_PAYLOAD"
                    else -> {
                        val reply = J1979DocumentedCatalogue.hex(requireNotNull(step.responseHex))
                        require(reply.size > 3 && reply[0].toInt() == 0x42 &&
                            (reply[1].toInt() and 255) == pid && (reply[2].toInt() and 255) == frame.frame)
                        val definition = catalogue.definitions[frame.frame to pid]
                        when {
                            definition == null -> "NO_DOCUMENTED_DEFINITION"
                            reply.size != definition.width+3 -> "DOCUMENTED_WIDTH_MISMATCH"
                            else -> {
                                signals += definition.signals.size
                                require(signals <= LIMIT)
                                val payload = reply.copyOfRange(3,reply.size)
                                row["reference"] = definition.reference
                                row["signals"] = definition.signals.map { it.project(payload) }
                                "PROJECTED"
                            }
                        }
                    }
                }
                row["projection_status"] = status
                counts[status] = (counts[status] ?: 0) + 1
                rows.add(row)
                require(rows.size <= LIMIT)
            }
        }
        val captureSha = J1979DocumentedCatalogue.sha(capture)
        val document = linkedMapOf<String,Any?>(
            "schema" to PROJECTION,"reference_edition" to catalogue.edition,"reference_sha256" to catalogue.referenceSha,
            "catalogue_sha256" to catalogue.sha,"source_capture_sha256" to captureSha,
            "expected_vin_independently_supplied" to true,"declared_vin" to expectedVin,
            "channel" to mapOf("kind" to "can","tx" to profile.tx,"rx" to profile.rx,"extended" to profile.extended),
            "source_completed" to result.completed,"final_identity_verified" to identity,
            "source_replay_verified" to true,"native_capture_replayed" to true,"transport_frames_replayed" to false,
            "reference_authenticity_verified" to false,"normative_compliance_verified" to false,
            "physical_vehicle_verified" to false,"authenticated" to false,"permits_programming" to false,
            "diagnostic_verdict" to null,"frames" to frames,"unvisited_frames" to result.unvisitedFrames,
            "counts" to counts,"rows" to rows)
        return J1979CatalogueProjection.Result(document,catalogue.sha,catalogue.referenceSha,captureSha,counts)
    }
}
