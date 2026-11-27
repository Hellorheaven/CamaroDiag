package com.camarodiag.connectors

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.camarodiag.connectors.UsbFixtures.bytes
import com.camarodiag.connectors.UsbFixtures.hex
import com.camarodiag.connectors.UsbFixtures.concat
import com.camarodiag.connectors.UsbFixtures.le
import com.camarodiag.connectors.UsbFixtures.flip
import com.camarodiag.connectors.UsbFixtures.rejects

/** Journal and archive contracts; simulated link never touches an ECU. */
object J1979MaintenanceUsbTest {
    private const val VIN = "1G1FA1RXXR0123456"
    private val key = UsbFixtures.key
    private val nonce = UsbFixtures.nonce
    private val device = UsbFixtures.device
    private fun mac(data: ByteArray) = UsbFixtures.mac(key,data)

    private fun row(stage: Int,status: Int,command: String,response: ByteArray,index: Int): ByteArray {
        val req = hex(command)
        return le(18+response.size).put(stage.toByte()).put(status.toByte()).put(req.size.toByte())
            .put(req[0]).put(if(req.size==2) req[1] else 0.toByte()).put(0)
            .putShort(response.size.toShort()).putInt(index*10).putInt(index*10+5)
            .putShort(1800).put(response).array()
    }
    private fun summary(outcome: Int,reason: Int,count: Int,nrc: Int,a: Int,b: Int) =
        le(20).put(outcome.toByte()).put(reason.toByte()).put(count.toByte()).put(nrc.toByte())
            .put(a.toByte()).put(b.toByte()).putShort(0).putInt(count*10-5).array()

    private fun record(profile: J1979MaintenanceUsb.Profile,number: Int,kind: Int,summary: ByteArray?,
                       rows: Array<ByteArray>,count: Int): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(profile.encode())
        if(kind!=1) {
            body.write(checkNotNull(summary))
            for(i in 0 until count) body.write(rows[i])
        }
        val payload = body.toByteArray()
        val framed = le(12+payload.size+4).put(bytes(67,77,1,kind,number,count,0,0))
            .putInt(payload.size).put(payload).array()
        ByteBuffer.wrap(framed).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(framed.size-4,UsbFixtures.crc(framed.copyOf(framed.size-4)).toInt())
        return framed
    }

    private fun fixture(profile: J1979MaintenanceUsb.Profile,failure: Int): Array<ByteArray> {
        val clear = profile.operation==1
        val plan = if(clear) listOf("0902","03","07","0A","0101","010D","010C","0902","010D","010C","04",
            "03","07","0A","0101","0902") else listOf("0902","0800","010D","010C","0902","0800","010D","010C","0801")
        val stages = if(clear) intArrayOf(1,2,2,2,2,2,2,4,4,4,6,7,7,7,7,7) else intArrayOf(1,2,2,2,4,4,4,4,6)
        val baseline = if(clear) 7 else 4
        val intent = if(clear) 10 else 8
        val count = if(failure==0) plan.size else intent+1
        val rows = Array(count) { i ->
            val command = plan[i]
            var reply = when(command) {
                "0902" -> concat(hex("490201"),VIN.toByteArray(Charsets.US_ASCII))
                "03" -> hex(if(i<intent) "43010123" else "4300")
                "07" -> hex("4700")
                "0A" -> hex("4A00")
                "0101" -> hex("410100000000")
                "010D" -> hex("410D00")
                "010C" -> hex("410C0000")
                "0800" -> hex("480080000000")
                "04" -> hex("44")
                "0801" -> hex("4801")
                else -> error(command)
            }
            var status = 1
            if(i==intent&&failure!=0) {
                if(failure==1) { status=2; reply=bytes(0x7f,if(clear) 4 else 8,0x22) }
                else { status=3; reply=ByteArray(0) }
            }
            row(stages[i],status,command,reply,i)
        }
        val a = if(failure==0) { if(clear) 255 else 63 } else { if(clear) 183 else 55 }
        val b = if(failure==0) { if(clear) 31 else 16 } else if(failure==1) 16 else 48
        return arrayOf(record(profile,0,1,null,rows,0),
            record(profile,1,2,summary(0,0,baseline,0,if(clear)144 else 16,0),rows,baseline),
            record(profile,2,3,summary(0,0,intent,0,if(clear)177 else 49,0),rows,intent),
            record(profile,3,4,summary(if(failure==0){if(clear)5 else 6}else if(failure==1)2 else 3,
                if(failure==0)0 else if(failure==1)11 else 10,count,if(failure==1)0x22 else 0,a,b),rows,count))
    }

    private fun reject(data: ByteArray,prefix: Boolean,vin: String) = rejects {
        if(prefix) J1979MaintenanceJournal.inspectPrefix(data,vin) else J1979MaintenanceJournal.inspect(data,vin)
    }

    private class Sim(private val challenge: ByteArray,private val result: ByteArray,
                      private val expectedHello: ByteArray,private val expectedProof: ByteArray) : J1979UsbCapture.Link {
        private var available = ByteArray(0)
        private var offset = 0
        var writes = 0
            private set
        var closed = false
            private set
        var failProof = false
        override fun read(data: ByteArray,offset: Int,size: Int,timeoutMs: Int): Int {
            val take = minOf(7,size,available.size-this.offset)
            available.copyInto(data,offset,this.offset,this.offset+take)
            this.offset += take
            return take
        }
        override fun write(data: ByteArray,timeoutMs: Int) {
            writes++
            when(writes) {
                1 -> { check(data.contentEquals(expectedHello)); available=challenge; offset=0 }
                2 -> {
                    check(data.contentEquals(expectedProof))
                    if(failProof) throw IOException("Uncertain proof write")
                    available=result; offset=0
                }
                else -> error("CU/4 or CU/5 retry")
            }
        }
        override fun checkNoBufferedInput() {
            if(offset!=available.size) throw IOException("Unexpected trailing bytes")
        }
        override fun close(){closed=true}
    }

    private fun envelope(raw: ByteArray,recovery: Boolean) =
        concat(le(12).put(bytes(67,85,4,4,if(recovery)3 else 2,0,0,0)).putInt(raw.size).array(),raw)

    private fun runCapture(profile: J1979MaintenanceUsb.Profile,raw: ByteArray,recovery: Boolean) {
        val hello=J1979MaintenanceUsb.hello(profile,nonce)
        val transcript=concat(bytes('M'.code),hello.copyOfRange(4,hello.size),device)
        val challenge=concat(bytes(67,85,4,2),device,mac(transcript))
        transcript[0]='m'.code.toByte()
        val proof=concat(bytes(67,85,4,3),mac(transcript))
        check(J1979MaintenanceUsb.proof(hello,challenge,key).contentEquals(proof))
        for(which in 0..4) {
            val ch=challenge.copyOf()
            var response=envelope(raw,recovery)
            if(which==1)flip(ch,36)
            if(which==2)response=response.copyOf(response.size-1)
            if(which==4)response[4]=(if(recovery)2 else 3).toByte()
            val sim=Sim(ch,response,hello,proof).apply { failProof=which==3 }
            val output=ByteArrayOutputStream()
            val capture=J1979MaintenanceUsb(sim){0L}
            val report=capture.run(profile,key,output,{true},nonce)
            check(sim.closed && report["permits_automatic_retry"]==false)
            if(which==0) {
                check(report["journal_transfer_verified"]==true&&report["recovery_required"]==recovery)
                check(output.toByteArray().contentEquals(raw))
            } else {
                check(report["journal_transfer_verified"]==false&&report["recovery_required"]==(which!=1))
                check(sim.writes==(if(which==1)1 else 2))
            }
            rejects { capture.run(profile,key,output,{true},nonce) }
        }
    }

    private fun archiveEnvelope(raw: ByteArray,records: Int,flags: Int): ByteArray {
        val head=le(12).put(bytes(67,85,5,4,flags,records,0,0)).putInt(raw.size).array()
        val tail=le(12).put(bytes(67,85,5,5)).putInt(raw.size).putInt(UsbFixtures.crc(raw).toInt()).array()
        return concat(head,raw,tail)
    }

    private fun archive(profile: J1979MaintenanceUsb.Profile,records: Array<ByteArray>,recovery: Boolean) {
        val hello=concat(bytes(67,85,5,1),profile.encode(),nonce)
        val transcript=concat(bytes('R'.code),hello.copyOfRange(4,hello.size),device)
        val challenge=concat(bytes(67,85,5,2),device,mac(transcript))
        transcript[0]='r'.code.toByte()
        val proof=concat(bytes(67,85,5,3),mac(transcript))
        for(count in 1..records.size) {
            val raw=concat(*records.take(count).toTypedArray())
            val terminal=count==4
            val required=!terminal||recovery
            val flags=(if(terminal)1 else 0) or (if(required)4 else 0)
            for(scenario in 0..11) {
                val ch=challenge.copyOf()
                var response=archiveEnvelope(raw,count,flags)
                when(scenario) {
                    1 -> flip(ch,36)
                    2 -> response=response.copyOf(response.size-12)
                    3 -> flip(response,response.lastIndex)
                    4 -> response=response.copyOf(response.size+1)
                    5 -> response[5]=(if(count==4)3 else 4).toByte()
                    6 -> response[2]=4
                    7 -> response[4]=(flags xor 1).toByte()
                    8 -> flip(response,22)
                    9 -> response=response.copyOf(12+raw.size/2)
                }
                val sim=Sim(ch,response,hello,proof).apply { failProof=scenario==10 }
                val output=ByteArrayOutputStream()
                val capture=J1979MaintenanceUsb(sim){0L}
                val revoke=scenario==11
                val report=capture.runArchive(profile,key,output,{!revoke||sim.writes<2},nonce)
                check(sim.closed)
                for(name in listOf("maintenance_command_sent","physical_vehicle_verified","permits_automatic_retry"))
                    check(report[name]==false)
                check(report["archive_transfer_verified"]==(scenario==0)&&
                    report["archive_close_receipt_verified"]==(scenario==0)&&
                    report["recovery_required"]==(scenario!=0||required))
                if(scenario==0) {
                    check(output.toByteArray().contentEquals(raw))
                    check(J1979MaintenanceJournal.inspect(raw,VIN)==report["inspection"])
                } else check(report["inspection"]==null)
                check(sim.writes==(if(scenario==1)1 else 2))
                rejects { capture.runArchive(profile,key,output,{true},nonce) }
            }
        }
    }

    @JvmStatic fun main(args: Array<String>) {
        for(operation in 1..2) {
            val profile=J1979MaintenanceUsb.Profile(operation,0x7e0,0x7e8,false,VIN,60000)
            for(failure in 0..2) {
                val records=fixture(profile,failure)
                val full=concat(*records)
                val report=J1979MaintenanceJournal.inspect(full,VIN)
                val terminal=report["terminal"] as? Map<*,*> ?: error("Missing terminal")
                val outcome=if(failure==0){if(operation==1)"ACKNOWLEDGED_READBACK" else "ACKNOWLEDGED_EVAP"}
                    else if(failure==1)"REFUSED" else "UNCERTAIN"
                check(terminal["outcome"]==outcome&&report["recovery_required"]==(failure==2))
                val prefix=concat(records[0],records[1],records[2])
                for(length in listOf(0,1,5,12,records[3].size-1)) {
                    val partial=J1979MaintenanceJournal.inspectPrefix(concat(prefix,records[3].copyOf(length)),VIN)
                    check(partial["prefix_integrity_verified"]==true && partial["record_consistency_verified"]==false &&
                        partial["terminal_present"]==false&&partial["recovery_required"]==true&&
                        partial["verified_prefix_bytes"]==prefix.size&&partial["unverified_trailing_bytes"]==length)
                }
                reject(full,true,VIN)
                reject(prefix,true,"1FA6P8TH0L5100000")
                reject(prefix.copyOf().also { flip(it,it.lastIndex) },true,VIN)
                reject(concat(prefix,bytes('X'.code)),true,VIN)
                runCapture(profile,full,failure==2)
                archive(profile,records,failure==2)
            }
        }
        println("CU/4 and CU/5 Android Kotlin fixtures passed (simulation)")
    }
}
