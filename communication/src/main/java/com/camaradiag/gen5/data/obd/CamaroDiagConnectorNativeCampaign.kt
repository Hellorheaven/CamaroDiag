package com.camarodiag.gen5.data.obd

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.camarodiag.connectors.J1979UsbAuth
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
 * CJ/1 frame checks alone do not prove complete protocol semantics or the
 * vehicle's identity. The raw capture remains available for full inspection.
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

    data class Export(val capture: File, val report: File, val frameIntegrityVerified: Boolean)

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
                    error = result.error
                } catch (failure: IOException) {
                    error = failure.message ?: "USB capture failed"
                } finally {
                    channel.force(true)
                }
            }
        } catch (failure: IOException) {
            frames = false
            error = failure.message ?: "Cannot persist USB capture"
        } finally {
            key.fill(0)
        }
        val json = JSONObject().apply {
            put("schema", "camarodiag-functional-j1979-native-usb-frame-v1")
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
            put("capture_integrity_verified", false)
            put("evidence_authenticated", false)
            put("physical_vehicle_verified", false)
            put("observed_vin", JSONObject.NULL)
            put("records", records)
            put("raw_capture_name", capture.name)
            put("error", error ?: JSONObject.NULL)
        }
        FileChannel.open(report.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
            val bytes = java.nio.ByteBuffer.wrap(json.toString(2).toByteArray(Charsets.UTF_8))
            while (bytes.hasRemaining()) check(channel.write(bytes) > 0)
            channel.force(true)
        }
        Export(capture, report, frames)
    }
}
