package com.camarodiag.gen5.data.obd

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.camarodiag.connectors.J1979UsbAuth
import com.camarodiag.connectors.J1979CampaignSemantics
import org.json.JSONArray
import com.camarodiag.connectors.UsbJ1979
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.UUID

/**
 * Native USB campaign entry for the communication module, distinct from ELM
 * and public discovery. The caller supplies local owner approval and a
 * provisioned credential; never log/store the credential in a report.
 *
 * CJ/1 frame and application campaign checks establish recorded evidence
 * consistency, not physical provenance. Raw capture remains available.
 */
object CamaroDiagConnectorNativeCampaign {
    data class Target(
        val controlId: Int, val dataId: Int, val tx: Int, val rx: Int,
        val extended: Boolean, val budgetMs: Int,
        val vehicle: String, val ecu: String, val connector: String,
        val connectionGeneration: Long
    ) {
        val valid: Boolean get() =
            controlId >= 0 && dataId >= 0 && controlId != dataId &&
            J1979UsbAuth.physicalPair(tx, rx, extended) &&
            budgetMs in 1..600000 && connectionGeneration > 0 &&
            listOf(vehicle, ecu, connector).all { it.isNotBlank() && it.length <= 256 &&
                it.none { ch -> ch.code < 32 } }
    }

    data class Export(val capture: File, val report: File, val frameIntegrityVerified: Boolean,
        val captureIntegrityVerified: Boolean, val campaignComplete: Boolean)

    suspend fun run(
        manager: UsbManager, selected: UsbDevice, target: Target,
        credential: ByteArray, privateDirectory: File, appVersion: String,
        stillApproved: (Long) -> Boolean
    ): Export = withContext(Dispatchers.IO) {
        require(target.valid && credential.size == 32 && appVersion.isNotBlank())
        require(privateDirectory.isDirectory)
        val jobContext = currentCoroutineContext()
        check(jobContext.isActive && stillApproved(target.connectionGeneration)) {
            "CU/1 session is not locally approved"
        }
        val id = UUID.randomUUID().toString()
        val capture = File(privateDirectory, "j1979-usb-$id.cj")
        val report = File(privateDirectory, "j1979-usb-$id.json")
        val key = credential.copyOf()
        val started = System.nanoTime()
        var authenticated = false
        var frames = false
        var records = 0
        var semantic = false
        var vin: String? = null
        var steps = emptyList<J1979CampaignSemantics.Step>()
        var summary: J1979CampaignSemantics.Summary? = null
        var error: String? = null
        try {
            FileChannel.open(capture.toPath(), StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE).use { channel ->
                try {
                    val result = UsbJ1979.capture(manager, selected, target.controlId, target.dataId,
                        target.tx, target.rx, target.extended, target.budgetMs, key,
                        Channels.newOutputStream(channel)) {
                        jobContext.isActive && stillApproved(target.connectionGeneration)
                    }
                    authenticated = result.deviceCredentialAuthenticated
                    frames = result.frameIntegrityVerified
                    records = result.records
                    semantic = result.captureIntegrityVerified
                    steps = result.steps
                    summary = result.summary
                    vin = result.observedVin
                    error = result.error
                } catch (failure: IOException) {
                    error = failure.message ?: "USB capture failed"
                } finally {
                    channel.force(true)
                }
            }
        } catch (failure: IOException) {
            frames = false
            semantic = false
            summary = null
            error = failure.message ?: "Cannot persist USB capture"
        } finally {
            key.fill(0)
        }
        val cancelled = !jobContext.isActive || !stillApproved(target.connectionGeneration)
        if (cancelled) semantic = false
        val json = JSONObject().apply {
            put("schema", "camarodiag-functional-j1979-native-usb-v1")
            put("protocol", "SAE-J1979")
            put("source", "LIVE_USB_CU1")
            put("app_version", appVersion)
            put("captured_at_utc", Instant.now().toString())
            put("duration_ms", (System.nanoTime() - started) / 1_000_000L)
            put("declared_vehicle", target.vehicle)
            put("declared_ecu", target.ecu)
            put("declared_connector", target.connector)
            put("connection_generation", target.connectionGeneration)
            put("request_can_id", target.tx.toString(16).uppercase(java.util.Locale.ROOT))
            put("response_can_id", target.rx.toString(16).uppercase(java.util.Locale.ROOT))
            put("extended", target.extended)
            put("budget_ms", target.budgetMs)
            put("device_credential_authenticated", authenticated)
            put("frame_integrity_verified", frames)
            put("capture_integrity_verified", semantic)
            put("cancelled", cancelled)
            put("service_observations", JSONArray().apply {
                for (mode in listOf(1, 2, 3, 6, 7, 8, 9, 10)) {
                    val rows = steps.filter { it.requestHex.take(2).toInt(16) == mode }
                    put(JSONObject().apply {
                        put("mode", "%02X".format(java.util.Locale.ROOT, mode))
                        put("attempted", rows.size)
                        for (status in listOf("PASSED", "RAW", "NEGATIVE", "TIMEOUT", "FAILED")) {
                            put(status.lowercase(java.util.Locale.ROOT), rows.count { it.status == status })
                        }
                    })
                }
            })
            put("evidence_authenticated", false)
            put("physical_vehicle_verified", false)
            put("observed_vin", vin ?: JSONObject.NULL)
            val expectedVin = target.vehicle.takeIf { it.matches(Regex("[A-HJ-NPR-Z0-9]{17}")) }
            put("declared_vin", expectedVin ?: JSONObject.NULL)
            put("vin_match", when {
                expectedVin == null -> "NOT_REQUESTED"
                !authenticated || !semantic || vin == null -> "UNVERIFIED"
                vin == expectedVin -> "MATCH"
                else -> "MISMATCH"
            })
            put("steps", JSONArray().apply {
                steps.forEach { step ->
                    put(JSONObject().apply {
                        put("sequence", step.sequence)
                        put("request_hex", step.requestHex)
                        put("response_hex", step.responseHex ?: JSONObject.NULL)
                        put("status", step.status)
                        put("duration_ms", step.durationMs)
                        put("dtc_codes", step.dtcCodes()?.let { JSONArray(it) } ?: JSONObject.NULL)
                        put("negative_response_code", step.negativeResponseCode() ?: JSONObject.NULL)
                        put("monitor_records", step.monitorRecords()?.let { values ->
                            JSONArray().apply { values.forEach { value ->
                                put(JSONObject().put("monitor_id", value.monitorId).put("test_id", value.testId)
                                    .put("unit_scaling_id", value.unitScalingId).put("value_encoded", value.valueEncoded)
                                    .put("minimum_encoded", value.minimumEncoded).put("maximum_encoded", value.maximumEncoded))
                            } }
                        } ?: JSONObject.NULL)
                        put("readiness", step.readiness()?.let { info ->
                            JSONObject().put("mil_on",info.milOn).put("confirmed_dtc_count",info.confirmedDtcCount)
                                .put("compression_ignition",info.compressionIgnition).put("byte_b",info.byteB)
                                .put("byte_c",info.byteC).put("byte_d",info.byteD)
                                .put("supported_monitors",JSONObject(info.supportedMonitors))
                        } ?: JSONObject.NULL)
                        put("vehicle_info", step.vehicleInfo()?.let { info ->
                            JSONObject().put("info_type", info.infoType).put("values", JSONArray(info.values))
                        } ?: JSONObject.NULL)
                        put("measurement", step.measurement()?.let { value ->
                            JSONObject().put("mode", value.mode).put("pid", value.pid)
                                .put("name", value.name).put("number", value.number).put("unit", value.unit)
                        } ?: JSONObject.NULL)
                    })
                }
            })
            put("summary", summary?.let { item ->
                JSONObject().apply {
                    put("end", item.end)
                    put("steps", item.steps)
                    val flags = listOf("transport_observed", "pid_inventory_complete",
                        "freeze_trigger_observed", "freeze_frame_present", "freeze_inventory_complete",
                        "monitor_inventory_complete", "control_inventory_complete", "identity_inventory_complete")
                    flags.forEachIndexed { index, name -> put(name, (item.flags and (1 shl index)) != 0) }
                }
            } ?: JSONObject.NULL)
            put("records", records)
            put("raw_capture_name", capture.name)
            put("error", error ?: JSONObject.NULL)
        }
        FileChannel.open(report.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
            val bytes = java.nio.ByteBuffer.wrap(json.toString(2).toByteArray(Charsets.UTF_8))
            while (bytes.hasRemaining()) check(channel.write(bytes) > 0)
            channel.force(true)
        }
        Export(capture, report, frames, semantic, semantic && summary?.end == "COMPLETE")
    }
}
