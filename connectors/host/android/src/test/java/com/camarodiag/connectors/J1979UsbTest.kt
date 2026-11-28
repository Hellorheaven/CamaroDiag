package com.camarodiag.connectors

import java.io.ByteArrayOutputStream
import com.camarodiag.connectors.UsbFixtures.bytes
import com.camarodiag.connectors.UsbFixtures.hex
import com.camarodiag.connectors.UsbFixtures.concat
import com.camarodiag.connectors.UsbFixtures.le
import com.camarodiag.connectors.UsbFixtures.flip

object J1979UsbTest {
    private fun frame(kind: Int, seq: Int, body: ByteArray) = UsbFixtures.frame('J', kind, seq, body)
    private fun step(seq: Int, state: Int, request: String, response: String) =
        J1979CampaignSemantics.Step(seq, state, 1, hex(request), hex(response))

    @JvmStatic fun main(args: Array<String>) {
        val key = UsbFixtures.key
        val hello = J1979UsbAuth.hello(0x7e0, 0x7e8, false, 10000, UsbFixtures.nonce)
        val challenge = concat(bytes(67, 85, 1, 2), UsbFixtures.device,
            hex("b97b539ae306e6525316479277660def5260ffaa27908b36a82d071cd3f51694"))
        val proof = concat(bytes(67, 85, 1, 3),
            hex("effe75029406a796ee6bc3a18c55a59298f7d4760996d8a3e40ff660ac691590"))
        check(proof.contentEquals(J1979UsbAuth.proof(hello, challenge, key)))
        val start = frame(1, 0, hello.copyOfRange(4, 17))
        val end = frame(3, 1, ByteArray(22).also { it[0] = 4 })
        val capture = concat(start, end)
        val good = UsbFixtures.Sim(concat(challenge, capture))
        var raw = ByteArrayOutputStream()
        var result = J1979UsbCapture(good).runRequest(hello, 10000, key, raw)
        check(result.deviceCredentialAuthenticated && result.frameIntegrityVerified &&
            result.captureIntegrityVerified && !result.physicalVehicleVerified)
        check(good.closed && good.writes == 2 && raw.toByteArray().contentEquals(capture))
        val bad = challenge.copyOf().also { flip(it, 67) }
        val wrong = UsbFixtures.Sim(concat(bad, capture))
        result = J1979UsbCapture(wrong).runRequest(hello, 10000, key, ByteArrayOutputStream())
        check(!result.deviceCredentialAuthenticated && !result.frameIntegrityVerified && wrong.closed && wrong.writes == 1)
        val forgedEnd = frame(3, 1, ByteArray(22).also { it[0] = 1; it[1] = 3 })
        val forged = UsbFixtures.Sim(concat(challenge, start, forgedEnd))
        result = J1979UsbCapture(forged).runRequest(hello, 10000, key, ByteArrayOutputStream())
        check(!result.captureIntegrityVerified && result.summary == null)
        val initial = le(16).put(1).put(2).putInt(1).putShort(6).put(bytes(1,0,0x41,0,0,0,0,0)).array()
        val missingSummary = le(22).put(1).put(3).putInt(1).putInt(1).array()
        val missing = UsbFixtures.Sim(concat(challenge,start,frame(2,1,initial),frame(3,2,missingSummary)))
        result = J1979UsbCapture(missing).runRequest(hello,10000,key,ByteArrayOutputStream())
        check(!result.captureIntegrityVerified && result.steps.size == 1)
        val rpm = checkNotNull(step(6,1,"010C","410C1AF8").measurement())
        check(rpm.number == 1726.0 && rpm.unit == "rpm")
        val freeze = checkNotNull(step(7,1,"020500","4205005A").measurement())
        check(freeze.mode == 2 && freeze.number == 50.0)
        check(step(8,2,"01A1","41A101").measurement() == null)
        check(step(3,1,"03","43040123456789ABCDEF").dtcCodes() == listOf("P0123","C0567","B09AB","U0DEF"))
        val denied = step(4,3,"07","7F0711")
        check(denied.dtcCodes() == null && denied.negativeResponseCode() == 0x11)
        check(step(5,1,"0A","4A00").dtcCodes()?.isEmpty() == true)
        val monitors = checkNotNull(step(9,1,"0601","4601010212340000FFFF01030280000001FFFE").monitorRecords())
        check(monitors.size == 2 && monitors[0].valueEncoded == 0x1234 &&
            monitors[1].minimumEncoded == 1 && monitors[1].maximumEncoded == 65534)
        check(step(9,1,"0600","460080000000").monitorRecords() == null)
        check(step(10,1,"0904","49040143414C31000000000000000000000000").vehicleInfo()?.values == listOf("CAL1"))
        check(step(11,1,"0906","49060201020304FFFFFFFF").vehicleInfo()?.values == listOf("01020304","FFFFFFFF"))
        check(step(12,1,"0904","49040143004131000000000000000000000000").vehicleInfo() == null)
        val ready = checkNotNull(step(13,1,"0101","410182112105").readiness())
        check(ready.milOn && ready.confirmedDtcCount == 2 && !ready.compressionIgnition)
        check(ready.supportedMonitors["misfire"] == false && ready.supportedMonitors["catalyst"] == false &&
            ready.supportedMonitors["oxygen-sensor"] == true && !ready.supportedMonitors.containsKey("evaporative-system"))
        val diesel = checkNotNull(step(14,1,"0101","410100084040").readiness())
        check(diesel.compressionIgnition && diesel.supportedMonitors["particulate-filter"] == false &&
            !diesel.supportedMonitors.containsKey("oxygen-sensor-heater"))
        val page = checkNotNull(step(15,1,"0100","410080000003").supportedPage())
        check(page.hasNextPage && page.supportedIds == listOf(1,31))
        val trigger = checkNotNull(step(16,1,"020200","4202000123").freezeTrigger())
        check(trigger.present && trigger.dtc == "P0123")
        check(step(17,1,"020200","4202000000").freezeTrigger()?.present == false)
        val odo = checkNotNull(step(18,1,"01A6","41A60012D687").measurement())
        check(odo.number == 123456.7 && odo.unit == "km")
        check(step(19,1,"01A6","41A6FFFFFFFF").measurement()?.number == 429496729.5)
        check(step(20,2,"01A6","41A60012D687").measurement() == null)
        fun decode(q: String,r: String?,status: String) = J1979CampaignSemantics.decodeObservation(q,r,status)
        check(decode("03","4300","PASSED") != null)
        check(decode("07","7F0711","NEGATIVE") != null)
        check(decode("07","7F0778","NEGATIVE") == null)
        check(decode("07","7F0311","NEGATIVE") == null)
        check(decode("07",null,"NEGATIVE") == null)
        check(decode("0105",null,"TIMEOUT") != null)
        check(decode("0105","41055A","TIMEOUT") == null)
        check(decode("0105","4105","PASSED") == null)
        check(decode("04",null,"FAILED") == null)
        check(decode("0801","4801","RAW") == null)
        check(checkNotNull(decode("01A6","41A60012D687","RAW")).measurement() == null)
        val partial = UsbFixtures.Sim(concat(challenge,capture.copyOf(capture.size-1)))
        raw = ByteArrayOutputStream()
        result = J1979UsbCapture(partial).runRequest(hello,10000,key,raw)
        check(!result.frameIntegrityVerified && result.deviceCredentialAuthenticated && partial.closed && raw.size() == capture.size-1)
        println("CU/1 Android Kotlin fixtures passed")
    }
}
