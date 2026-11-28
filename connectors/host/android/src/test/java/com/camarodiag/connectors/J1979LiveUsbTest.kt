package com.camarodiag.connectors

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import com.camarodiag.connectors.UsbFixtures.bytes
import com.camarodiag.connectors.UsbFixtures.concat
import com.camarodiag.connectors.UsbFixtures.le
import com.camarodiag.connectors.UsbFixtures.mac
import com.camarodiag.connectors.UsbFixtures.flip
import com.camarodiag.connectors.UsbFixtures.rejects

object J1979LiveUsbTest {
    private const val VIN = "1G1FA1RXXR0123456"
    private fun frame(kind: Int, seq: Int, body: ByteArray) = UsbFixtures.frame('L',kind,seq,body)
    private fun observation(phase: Int,state: Int,cycle: Int,pid: Int,start: Int,request: ByteArray,response: ByteArray) =
        le(26+response.size).put(phase.toByte()).put(state.toByte()).put(1).put(0)
            .putShort(cycle.toShort()).put(pid.toByte()).put(0).putInt(start).putInt(start+5)
            .putShort(100).putShort(response.size.toShort()).putInt(5).put(request).put(response).array()

    private fun bodies(profile: J1979LiveUsbAuth.Profile): Array<ByteArray> {
        val identity = concat(bytes(0x49,2,1),VIN.toByteArray(Charsets.US_ASCII))
        val buf = le(128)
        buf.put(0).put(15).putShort(2).putInt(7).putInt(4).putInt(4).putInt(0).putInt(0).putInt(125).putInt(60)
        val terminal = buf.array()
        terminal[32] = 32
        terminal[33] = 16
        buf.position(64)
        buf.putShort(2).putShort(2)
        return arrayOf(profile.encode(),
            observation(1,1,0,0,0,bytes(9,2),identity),
            observation(2,1,0,0,5,bytes(1,0),bytes(0x41,0,8,0x10,0,0)),
            observation(4,2,1,5,10,bytes(1,5),bytes(0x41,5,0x50)),
            observation(4,2,1,12,15,bytes(1,12),bytes(0x41,12,0x12,0x34)),
            observation(4,2,2,5,110,bytes(1,5),bytes(0x41,5,0x51)),
            observation(4,2,2,12,115,bytes(1,12),bytes(0x41,12,0x12,0x35)),
            observation(5,1,0,0,120,bytes(9,2),identity),terminal)
    }

    private fun capture(rows: Array<ByteArray>): ByteArray = concat(*rows.mapIndexed { i, body ->
        frame(if (i==0) 1 else if (i==rows.lastIndex) 3 else 2,i,body)
    }.toTypedArray())

    private fun prefix(profile: J1979LiveUsbAuth.Profile, full: ByteArray) {
        val rows = bodies(profile)
        fun prefixBytes() = concat(*rows.dropLast(1).mapIndexed { i, body ->
            frame(if (i==0) 1 else 2,i,body)
        }.toTypedArray())
        val original = prefixBytes()
        val terminal = frame(3,rows.lastIndex,rows.last())
        val tails = listOf(ByteArray(0),terminal.copyOf(5),terminal.copyOf(terminal.size-1))
        val reasons = listOf("MISSING_TERMINAL","TRUNCATED_HEADER","TRUNCATED_RECORD")
        for ((index,tail) in tails.withIndex()) {
            val result = J1979LiveNativeReplay.inspectPrefix(concat(original,tail),VIN)
            check(result["prefix_integrity_verified"] == true)
            for (key in listOf("capture_integrity_verified","execution_completed","terminal_present")) check(result[key] == false)
            check(result["complete_records"] == 8 && result["complete_observations"] == 7 &&
                result["raw_samples_in_prefix"] == 4 && result["last_complete_offset"] == original.size &&
                result["discarded_tail_bytes"] == tail.size && result["interruption"] == reasons[index])
        }
        val corrupt = original.copyOf().also { flip(it,it.lastIndex) }
        for (bad in listOf(corrupt,full,concat(full,bytes(0)),concat(original,bytes('X'.code)),
            concat(original,bytes(67,76,2)))) rejects { J1979LiveNativeReplay.inspectPrefix(bad,VIN) }
        rejects { J1979LiveNativeReplay.inspectPrefix(original,"1FA6P8TH0L5100000") }
        val swap = rows[3]
        rows[3] = rows[4]
        rows[4] = swap
        rejects { J1979LiveNativeReplay.inspectPrefix(prefixBytes(),VIN) }
    }

    @JvmStatic fun main(args: Array<String>) {
        val profile = J1979LiveUsbAuth.Profile(0x7e0,0x7e8,false,VIN,intArrayOf(5,12),2,100,1000)
        check(profile.encode().size == 67 &&
            profile.encode().contentEquals(J1979LiveUsbAuth.Profile.decodeAuth(profile.encodeAuth()).encode()))
        val key = UsbFixtures.key
        val device = UsbFixtures.device
        val hello = J1979LiveUsbAuth.hello(profile,UsbFixtures.nonce)
        val transcript = concat(bytes('L'.code),hello.copyOfRange(4,hello.size),device)
        val challenge = concat(bytes(67,85,3,2),device,mac(key,transcript))
        transcript[0] = 'l'.code.toByte()
        check(hello.size == 107 && J1979LiveUsbAuth.proof(hello,challenge,key)
            .contentEquals(concat(bytes(67,85,3,3),mac(key,transcript))))
        val raw = capture(bodies(profile))
        prefix(profile,raw)
        val decoded = J1979LiveNativeReplay.decode(ByteArrayInputStream(raw),profile)
        check(decoded.captureIntegrityVerified && decoded.completed() && decoded.observationsReceived == 7)
        check((decoded.summary?.get("raw") as? Number)?.toInt() == 4 &&
            decoded.report()["physical_vehicle_verified"] == false && !decoded.deviceCredentialAuthenticated)
        val projection=J1979LiveScalarProjection.project(decoded,VIN)
        check(projection.identityRechecked&&projection.numericProjectionComplete&&projection.samples.size==4)
        check(projection.channels[0].mean==40.5&&projection.channels[0].count==2)
        check(projection.channels[1].mean==1165.125&&projection.channels[1].missing==0)
        check(projection.report()["physical_vehicle_verified"]==false)
        check(J1979LiveScalarProjection.csv(projection,"a".repeat(64)).toString(Charsets.UTF_8).contains("DOCUMENTED_SCALAR"))
        check(runCatching { J1979LiveScalarProjection.project(decoded,"1FA6P8TH0L5100000") }.isFailure)
        val unverified=J1979LiveNativeReplay.Result(decoded.profile,decoded.observationsReceived,true,false,false,false,
            "OFFLINE_CL1",decoded.summary?.plus("final_identity_verified" to false),decoded.result,null,null)
        val partial=J1979LiveScalarProjection.project(unverified,VIN)
        check(!partial.identityRechecked&&!partial.numericProjectionComplete&&partial.samples.all { it.number==null })
        for (change in listOf(intArrayOf(3,25,6),intArrayOf(4,6,5),intArrayOf(5,8,20),
            intArrayOf(8,12,5),intArrayOf(1,3,1),intArrayOf(2,28,0),intArrayOf(7,27,0))) {
            val rows = bodies(profile)
            rows[change[0]][change[1]] = change[2].toByte()
            check(!J1979LiveNativeReplay.decode(ByteArrayInputStream(capture(rows)),profile).captureIntegrityVerified)
        }
        for (bad in listOf(raw.copyOf(raw.size-1),concat(raw,bytes(0)),raw.copyOf(8)))
            check(!J1979LiveNativeReplay.decode(ByteArrayInputStream(bad),profile).captureIntegrityVerified)
        val link = UsbFixtures.Sim(concat(challenge,raw))
        val output = ByteArrayOutputStream()
        val live = J1979LiveUsbCapture(link).runRequest(profile,hello,key,output) { true }
        check(live.captureIntegrityVerified && live.deviceCredentialAuthenticated && live.clientProofSent && live.completed())
        check(link.writes == 2 && link.closed && raw.contentEquals(output.toByteArray()))
        flip(challenge,challenge.lastIndex)
        val denied = UsbFixtures.Sim(challenge)
        val failed = J1979LiveUsbCapture(denied).runRequest(profile,hello,key,ByteArrayOutputStream()) { true }
        check(!failed.captureIntegrityVerified && !failed.deviceCredentialAuthenticated && denied.writes == 1 && denied.closed)
        val revoked = UsbFixtures.Sim(challenge)
        val cancelled = J1979LiveUsbCapture(revoked).runRequest(profile,hello,key,ByteArrayOutputStream()) { false }
        check(cancelled.cancelled && !cancelled.captureIntegrityVerified && revoked.writes == 0 && revoked.closed)
        println("CU/3 Android Kotlin fixtures passed (simulation)")
    }
}
