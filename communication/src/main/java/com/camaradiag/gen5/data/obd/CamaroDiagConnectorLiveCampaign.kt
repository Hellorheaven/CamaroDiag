package com.camaradiag.gen5.data.obd

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.camaradiag.connectors.J1979LiveUsbAuth
import com.camaradiag.connectors.J1979LiveNativeReplay
import com.camaradiag.connectors.UsbJ1979
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID

/** Explicit native raw Mode 01 acquisition and independent persisted replay. */
object CamaroDiagConnectorLiveCampaign {
    data class Target(
        val channel: CamaroDiagConnectorNativeCampaign.Target,
        val pids: List<Int>, val cycles: Int, val intervalMs: Int
    ) {
        val profile: J1979LiveUsbAuth.Profile get() =
            J1979LiveUsbAuth.Profile(channel.tx,channel.rx,channel.extended,channel.vehicle,
                pids.toIntArray(),cycles,intervalMs,channel.budgetMs)
        val valid: Boolean get() = channel.valid && runCatching { profile }.isSuccess
        fun pinned(): Target = copy(pids=pids.toList()).also { require(it.valid) }
    }

    data class Evidence(
        val capture: File, val sha256: String?, val target: Target,
        val live: J1979LiveNativeReplay.Result?, val replay: J1979LiveNativeReplay.Result?,
        val error: String?, val cancelled: Boolean,
        val startedAtMillis: Long, val finishedAtMillis: Long
    ) {
        val captureIntegrityVerified: Boolean get() = error == null && !cancelled &&
            live?.captureIntegrityVerified == true && live.deviceCredentialAuthenticated &&
            live.clientProofSent && replay?.captureIntegrityVerified == true && sha256 != null
        val executionCompleted: Boolean get() = captureIntegrityVerified && replay?.completed() == true
        fun report(): Map<String,Any?> {
            val base = live?.report()?.toMutableMap() ?: linkedMapOf<String,Any?>(
                "schema" to "camarodiag-functional-j1979-native-live-v1", "protocol" to "SAE-J1979",
                "source" to "LIVE_USB_CU3", "device_credential_authenticated" to false,
                "client_proof_sent" to false, "physical_vehicle_verified" to false,
                "hardware_provenance_verified" to false, "evidence_authenticated" to false,
                "permits_programming" to false, "observations_received" to 0,
                "profile" to null, "summary" to null, "result" to null, "error" to null)
            base["capture_integrity_verified"] = captureIntegrityVerified
            base["summary"] = if(captureIntegrityVerified) replay?.summary else null
            base["result"] = if(captureIntegrityVerified) replay?.result else null
            base["error"] = error ?: if(cancelled) "CU/3 cancelled" else live?.error
            base["cancelled"] = cancelled
            base["capture_sha256"] = sha256
            base["raw_capture_name"] = capture.name
            return base
        }
    }

    /** Read the persisted capture again immediately before a derived export. */
    fun verifiedReplay(evidence: Evidence): J1979LiveNativeReplay.Result {
        require(evidence.captureIntegrityVerified)
        val expected=requireNotNull(evidence.sha256)
        check(digest(evidence.capture)==expected) { "CL/1 digest mismatch" }
        val result=evidence.capture.inputStream().buffered().use {
            J1979LiveNativeReplay.decode(it,evidence.target.profile)
        }
        check(result.captureIntegrityVerified && result.summary==evidence.replay?.summary &&
            result.result==evidence.replay?.result && digest(evidence.capture)==expected) {
            "CL/1 changed before derived export"
        }
        return result
    }

    fun digest(file: File): String {
        val sha = MessageDigest.getInstance("SHA-256")
        var total = 0L
        file.inputStream().buffered().use { stream ->
            val buffer = ByteArray(65536)
            while(true) {
                val n=stream.read(buffer)
                if(n<0) break
                total+=n
                require(total<=J1979LiveNativeReplay.MAX_CAPTURE)
                sha.update(buffer,0,n)
            }
        }
        return ElmJ1979EvapTest.hex(sha.digest()).lowercase(java.util.Locale.ROOT)
    }

    suspend fun run(manager: UsbManager, device: UsbDevice, requested: Target,
        credential: ByteArray, directory: File, stillApproved: (Long)->Boolean): Evidence =
        withContext(Dispatchers.IO) {
            val target=requested.pinned()
            require(directory.isDirectory && credential.size==32 &&
                target.channel.connector==device.deviceName)
            val job=currentCoroutineContext()
            fun current() = job.isActive && stillApproved(target.channel.connectionGeneration) &&
                manager.hasPermission(device) && manager.deviceList[device.deviceName]==device
            check(current())
            val started=System.currentTimeMillis()
            val file=File(directory,"j1979-live-"+UUID.randomUUID()+".cl")
            val key=credential.copyOf()
            var live:J1979LiveNativeReplay.Result?=null
            var replay:J1979LiveNativeReplay.Result?=null
            var sha:String?=null
            var error:String?=null
            try {
                FileChannel.open(file.toPath(),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE).use { channel ->
                    try {
                        live=UsbJ1979.captureLive(manager,device,target.channel.controlId,target.channel.dataId,
                            target.profile,key,Channels.newOutputStream(channel),::current)
                    } finally { channel.force(true) }
                }
                sha=digest(file)
                val saved=file.inputStream().buffered().use { J1979LiveNativeReplay.decode(it,target.profile) }
                check(sha==digest(file)) { "CL/1 changed during replay" }
                val acquired=requireNotNull(live)
                if(acquired.captureIntegrityVerified && saved.captureIntegrityVerified) {
                    check(acquired.profile?.encode()?.contentEquals(saved.profile?.encode() ?: byteArrayOf())==true &&
                        acquired.observationsReceived==saved.observationsReceived &&
                        acquired.summary==saved.summary && acquired.result==saved.result) {
                        "Persisted CL/1 differs from live acquisition"
                    }
                    replay=saved
                } else error=acquired.error ?: saved.error ?: "CL/1 incomplete"
            } catch(cancelled:kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch(failure:Exception) {
                error=failure.message ?: failure.javaClass.simpleName
                replay=null
            } finally { key.fill(0) }
            Evidence(file,sha,target,live,replay,error,!current(),started,System.currentTimeMillis())
        }
}
