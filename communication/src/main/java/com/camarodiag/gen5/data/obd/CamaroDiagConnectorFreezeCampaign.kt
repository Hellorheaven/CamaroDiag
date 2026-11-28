package com.camarodiag.gen5.data.obd

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.camarodiag.connectors.J1979FreezeUsbAuth
import com.camarodiag.connectors.J1979FreezeUsbCapture
import com.camarodiag.connectors.UsbJ1979
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID

/** Native capture and independent replay. Neither USB authentication nor
 * recorded application replies attest physical provenance. */
object CamaroDiagConnectorFreezeCampaign {
    data class Target(
        val channel: CamaroDiagConnectorNativeCampaign.Target,
        val frames: List<Int>, val pids: List<Int>
    ) {
        val profile: J1979FreezeUsbAuth.Profile get() =
            J1979FreezeUsbAuth.Profile(channel.tx,channel.rx,channel.extended,channel.budgetMs,
                channel.vehicle,frames.toIntArray(),pids.toIntArray())
        val valid: Boolean get() = channel.valid && runCatching { profile }.isSuccess
        fun pinned(): Target = copy(frames=frames.toList(),pids=pids.toList()).also { require(it.valid) }
    }

    data class Evidence(
        val capture: File,
        val sha256: String?,
        val target: Target,
        val framed: Boolean,
        val credentialAuthenticated: Boolean,
        val proofSent: Boolean,
        val cancelled: Boolean,
        val observationCount: Int,
        val report: J1979FreezeResult?,
        val error: String?,
        val startedAtMillis: Long,
        val finishedAtMillis: Long
    ) {
        val captureIntegrityVerified: Boolean get() = framed && report != null && error == null && !cancelled
        val executionCompleted: Boolean get() = captureIntegrityVerified &&
            credentialAuthenticated && proofSent && report?.completed == true
    }

    internal suspend fun replay(decoded: J1979FreezeUsbCapture.Result,
        expected: J1979FreezeUsbAuth.Profile): J1979FreezeResult {
        require(decoded.frameIntegrityVerified && decoded.profile != null &&
            decoded.profile.encode().contentEquals(expected.encode()))
        val terminal = requireNotNull(decoded.terminal)
        // CF/1 declares the physical address width, not the CAN bitrate.
        // This synthetic selector only supplies the address validator used
        // by the deterministic recipe. Do not publish it as observed ATDPN.
        val target = J1979LiveTarget(if (expected.extended) 7 else 6,expected.rx,expected.vin)
        val plan = J1979FreezePlan(target,expected.frames().toList(),expected.pids().toList(),
            expected.budgetMs.toLong())
        var position = 0
        val result = J1979FreezeCampaign.execute(plan,observe={ request ->
            require(position < decoded.observations.size) { "Missing CF/1 observation" }
            val row = decoded.observations[position++]
            require(row.request().contentEquals(request)) { "Unexpected CF/1 request" }
            J1979FreezeCampaign.Read(row.outcome,row.response(),row.durationMs,row.attempted)
        },elapsed={ terminal.durationMs })
        require(position == decoded.observations.size && terminal.steps == position &&
            result.stopReason == terminal.stopReason && result.frames.size == terminal.visitedFrames &&
            result.completed == terminal.completed && result.identityRechecked == terminal.identityRechecked &&
            result.allFramesStable == terminal.allFramesStable &&
            result.frames.map { it.status } == terminal.frameStates) { "CF/1 terminal differs from replay" }
        return result
    }

    /** Replay a local capture without opening USB or using a credential. */
    suspend fun inspectCapture(raw: ByteArray, expectedVin: String): Pair<J1979FreezeUsbAuth.Profile,J1979FreezeResult> {
        require(raw.isNotEmpty() && raw.size.toLong() <= J1979FreezeUsbCapture.MAX_CAPTURE.toLong())
        val decoded = J1979FreezeUsbCapture.decode(raw.inputStream(),null)
        val profile = requireNotNull(decoded.profile)
        require(profile.vin == expectedVin) { "Independent VIN differs from CF/1" }
        return profile to replay(decoded,profile)
    }

    private fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        var count = 0L
        file.inputStream().buffered().use { stream ->
            val block=ByteArray(65536)
            while(true) {
                val n=stream.read(block)
                if(n<0)break
                count+=n
                require(count <= J1979FreezeUsbCapture.MAX_CAPTURE)
                hash.update(block,0,n)
            }
        }
        return ElmJ1979EvapTest.hex(hash.digest()).lowercase(java.util.Locale.ROOT)
    }

    suspend fun run(manager: UsbManager,device: UsbDevice,requested: Target,credential: ByteArray,
        directory: File,stillApproved: (Long)->Boolean): Evidence = withContext(Dispatchers.IO) {
        val target=requested.pinned()
        require(directory.isDirectory && credential.size==32 && device.deviceName==target.channel.connector)
        val job=currentCoroutineContext()
        fun current()=job.isActive && stillApproved(target.channel.connectionGeneration) &&
            manager.hasPermission(device) && manager.deviceList[device.deviceName]==device
        check(current())
        val started=System.currentTimeMillis()
        val file=File(directory,"j1979-freeze-${UUID.randomUUID()}.cf")
        val key=credential.copyOf()
        var capture:J1979FreezeUsbCapture.Result?=null
        var verified:J1979FreezeResult?=null
        var sha:String?=null
        var error:String?=null
        try {
            FileChannel.open(file.toPath(),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE).use { channel ->
                try {
                    capture=UsbJ1979.captureFreeze(manager,device,target.channel.controlId,target.channel.dataId,
                        target.profile,key,Channels.newOutputStream(channel),::current)
                } finally { channel.force(true) }
            }
            val live=requireNotNull(capture)
            // Verify the persisted bytes, not merely an in-memory transcript.
            sha=digest(file)
            val saved=file.inputStream().buffered().use { J1979FreezeUsbCapture.decode(it,target.profile) }
            check(sha==digest(file)) { "CF/1 changed during inspection" }
            if(live.frameIntegrityVerified && saved.frameIntegrityVerified) {
                verified=replay(saved,target.profile)
                val liveReplay=replay(live,target.profile)
                check(verified==liveReplay) { "Persisted CF/1 differs from live capture" }
            } else error=live.error ?: saved.error ?: "CF/1 incomplete"
        } catch (cancelled:kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure:Exception) {
            error=failure.message ?: failure.javaClass.simpleName
            verified=null
        } finally { key.fill(0) }
        val cancelled=!current()
        Evidence(file,sha,target,capture?.frameIntegrityVerified==true,
            capture?.deviceCredentialAuthenticated==true,capture?.clientProofSent==true,cancelled,
            capture?.observations?.size ?: 0,verified,error ?: if(cancelled)"CU/2 cancelled" else null,
            started,System.currentTimeMillis())
    }
}
