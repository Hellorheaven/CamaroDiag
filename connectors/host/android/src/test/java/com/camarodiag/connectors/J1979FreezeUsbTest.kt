package com.camarodiag.connectors

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import com.camarodiag.connectors.UsbFixtures.bytes
import com.camarodiag.connectors.UsbFixtures.concat
import com.camarodiag.connectors.UsbFixtures.le
import com.camarodiag.connectors.UsbFixtures.mac
import com.camarodiag.connectors.UsbFixtures.flip
import com.camarodiag.connectors.UsbFixtures.rejects

/** Complete and interrupted CF/1 capture fixtures, independent of physical USB. */
object J1979FreezeUsbTest {
    private const val VIN = "1G1FA1E30D1234567"
    private fun frame(kind: Int, seq: Int, body: ByteArray) = UsbFixtures.frame('F',kind,seq,body)
    private fun observation(seq: Int, request: ByteArray, response: ByteArray): ByteArray =
        frame(2,seq,le(10+request.size+response.size)
            .put(1).put(1).put(request.size.toByte()).put(0).putInt(5)
            .putShort(response.size.toShort()).put(request).put(response).array())

    private fun capture(profile: J1979FreezeUsbAuth.Profile): ByteArray {
        val identity = concat(bytes(0x49,2,1),VIN.toByteArray(Charsets.US_ASCII))
        val terminal = le(20).put(0).put(1).put(7).put(0).putInt(6).putInt(30).put(2).array()
        return concat(frame(1,0,profile.encode()),
            observation(1,bytes(9,2),identity),
            observation(2,bytes(2,0,0),bytes(0x42,0,0,0x40,0x10,0,0)),
            observation(3,bytes(2,2,0),bytes(0x42,2,0,3,1)),
            observation(4,bytes(2,12,0),bytes(0x42,12,0,0x1f,0x40)),
            observation(5,bytes(2,2,0),bytes(0x42,2,0,3,1)),
            observation(6,bytes(9,2),identity),frame(3,7,terminal))
    }

    @JvmStatic fun main(args: Array<String>) {
        val profile = J1979FreezeUsbAuth.Profile(0x7e0,0x7e8,false,60000,VIN,intArrayOf(0),intArrayOf(12))
        val wire = profile.encode()
        check(wire.size == 104 && wire.contentEquals(J1979FreezeUsbAuth.Profile.decode(wire).encode()))
        rejects { J1979FreezeUsbAuth.Profile.decode(wire.copyOf().also { it[33] = 1 }) }
        val key = UsbFixtures.key
        val device = UsbFixtures.device
        val hello = J1979FreezeUsbAuth.hello(profile,UsbFixtures.nonce)
        val transcript = concat(bytes('F'.code),hello.copyOfRange(4,hello.size),device)
        val challenge = concat(bytes(67,85,2,2),device,mac(key,transcript))
        transcript[0] = 'f'.code.toByte()
        check(J1979FreezeUsbAuth.proof(hello,challenge,key).contentEquals(concat(bytes(67,85,2,3),mac(key,transcript))))
        val raw = capture(profile)
        val decoded = J1979FreezeUsbCapture.decode(ByteArrayInputStream(raw),profile)
        check(decoded.frameIntegrityVerified && decoded.observations.size == 6)
        val terminal = checkNotNull(decoded.terminal)
        check(terminal.completed && terminal.allFramesStable)
        check(!decoded.evidenceAuthenticated && !decoded.physicalVehicleVerified && !decoded.permitsProgramming)
        val modified = decoded.observations[0].request()
        modified[0] = 4
        check(decoded.observations[0].request()[0].toInt() == 9)
        val damaged = raw.copyOf().also { flip(it,50) }
        for (bad in listOf(damaged,raw.copyOf(raw.size-1),concat(raw,bytes(0))))
            check(!J1979FreezeUsbCapture.decode(ByteArrayInputStream(bad),profile).frameIntegrityVerified)
        val prefix = raw.copyOf(raw.size-32)
        val parsed = J1979FreezeUsbCapture.inspectPrefix(prefix,VIN)
        check(parsed.observations.size == 6 && parsed.completeRecords == 7)
        check(parsed.discardedTailBytes == 0 && parsed.interruption == "MISSING_TERMINAL" &&
            parsed.lastCompleteOffset == prefix.size)
        val onlyPlan = raw.copyOf(12+J1979FreezeUsbAuth.PLAN_SIZE)
        check(J1979FreezeUsbCapture.inspectPrefix(onlyPlan,VIN).observations.isEmpty())
        check(J1979FreezeUsbCapture.inspectPrefix(concat(prefix,bytes(67,70,1,3,7)),VIN).interruption == "TRUNCATED_HEADER")
        check(J1979FreezeUsbCapture.inspectPrefix(raw.copyOf(raw.size-1),VIN).interruption == "TRUNCATED_RECORD")
        val corruptedPrefix = prefix.copyOf().also { flip(it,it.size-5) }
        for (bad in listOf(raw,corruptedPrefix,concat(prefix,bytes('X'.code)),concat(prefix,bytes(67,70,1,3,8))))
            rejects { J1979FreezeUsbCapture.inspectPrefix(bad,VIN) }
        rejects { J1979FreezeUsbCapture.inspectPrefix(prefix,"1G1FA1E30D7654321") }
        val link = UsbFixtures.Sim(concat(challenge,raw))
        val output = ByteArrayOutputStream()
        val live = J1979FreezeUsbCapture(link).runRequest(profile,hello,key,output) { true }
        check(live.frameIntegrityVerified && live.deviceCredentialAuthenticated && live.clientProofSent)
        check(link.writes == 2 && link.closed && output.toByteArray().contentEquals(raw))
        flip(challenge,challenge.size-1)
        val wrong = UsbFixtures.Sim(challenge)
        val refused = J1979FreezeUsbCapture(wrong).runRequest(profile,hello,key,ByteArrayOutputStream()) { true }
        check(!refused.frameIntegrityVerified && !refused.deviceCredentialAuthenticated && wrong.writes == 1 && wrong.closed)
        println("CU/2 Android Kotlin fixtures passed (simulation)")
    }
}
