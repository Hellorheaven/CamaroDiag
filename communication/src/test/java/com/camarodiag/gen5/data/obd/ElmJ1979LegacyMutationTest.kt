package com.camarodiag.gen5.data.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ElmJ1979LegacyMutationTest {
    private val vin = "1G1FA1RXXR0123456"
    private fun frame(protocol: Int, source: Int, payload: List<Int>): String {
        val header = if (protocol >= 4) listOf(0x80 or payload.size,0xF1,source)
            else listOf(if (protocol == 1) 0x41 else 0x48,0x6B,source)
        val bytes = header + payload
        return (bytes + ElmLegacyResponseFrames.checksum(protocol,bytes))
            .joinToString(" ",transform=ElmLegacyResponseFrames::hex)
    }

    @Test fun fixedProtocolMustNotAcceptAutomaticFallback() {
        for (p in 1..5) {
            assertEquals(p, ElmLegacyResponseFrames.fixedProtocol("ATDPN\r$p\r>"))
            assertNull(ElmLegacyResponseFrames.fixedProtocol("A$p"))
        }
        for (raw in listOf("6","2\r3","2\rERROR","NO DATA"))
            assertNull(ElmLegacyResponseFrames.fixedProtocol(raw))
    }

    @Test fun checksumAndSourceAreRequiredForAllFiveProtocols() {
        assertEquals(0x4B,ElmLegacyResponseFrames.checksum(2,"123456789".map(Char::code)))
        for (p in 1..5) {
            val line = frame(p,0x10,listOf(0x41,0,0x80,0,0,0))
            val record = ElmLegacyResponseFrames.decode(p,"0100",line).single()
            assertEquals(0x10,record.source)
            assertEquals(listOf(0x41,0,0x80,0,0,0),record.payload)
            for (bad in listOf("41 00 80 00 00 00",line + "\rNO DATA",
                line.dropLast(2)+ElmLegacyResponseFrames.hex(line.takeLast(2).toInt(16) xor 1))) {
                assertThrows(IllegalArgumentException::class.java) {
                    ElmLegacyResponseFrames.decode(p,"0100",bad)
                }
            }
        }
    }

    private suspend fun recipe(protocol: Int=2,clear: Boolean=true,wrongVin: Boolean=false,
        moving: Boolean=false,extra: Boolean=false,failSave: Boolean=false,
        missingAck: Boolean=false,failAfter: Boolean=false,missingPending: Boolean=false,
        secondEvap: Boolean=false): Pair<LegacyMutationResult,List<String>> {
        val sent = mutableListOf<String>()
        var saved = false
        var attempted = false
        val parts = (listOf(0,0,0)+(if (wrongVin) vin.dropLast(1)+"7" else vin).map(Char::code))
            .chunked(4).mapIndexed { i, bytes -> listOf(0x49,2,i+1)+bytes }
        fun encode(vararg records: Pair<Int,List<Int>>) = records.joinToString("\r") {
            frame(protocol,it.first,it.second)
        }.ifEmpty { "NO DATA" }
        val result = ElmJ1979LegacyMutation.run(
            LegacyMutationTarget(protocol,0x10,listOf(0x10,0x11),clear),vin,{},
            read = { command ->
                sent += command
                if (attempted && failAfter) error("Transport lost")
                when (command) {
                    "0100" -> encode(0x10 to listOf(0x41,0,0x80,0,0,0),
                        0x11 to listOf(0x41,0,0x80,0,0,0)) +
                        if (extra) "\r"+encode(0x12 to listOf(0x41,0,0x80,0,0,0)) else ""
                    "0901" -> encode(0x10 to listOf(0x49,1,5))
                    "0902" -> encode(*parts.map { 0x10 to it }.toTypedArray())
                    "03" -> encode(0x10 to (if (attempted) listOf(0x43,0,0,0,0,0,0)
                        else listOf(0x43,1,0x23,0,0,0,0)),0x11 to listOf(0x43,0,0,0,0,0,0))
                    "07" -> if (missingPending && attempted) "NO DATA" else
                        encode(0x10 to listOf(0x47,0,0,0,0,0,0),0x11 to listOf(0x47,0,0,0,0,0,0))
                    "0A" -> encode(0x10 to listOf(0x4A,1,0x23,0,0,0,0))
                    "0101" -> encode(0x10 to listOf(0x41,1,0,0,0,0))
                    "010D" -> encode(0x10 to listOf(0x41,0x0D,if (moving) 1 else 0))
                    "010C" -> encode(0x10 to listOf(0x41,0x0C,0,0))
                    "08000000000000" -> encode(0x10 to listOf(0x48,0,0,0x80,0,0,0)) +
                        if (secondEvap) "\r"+encode(0x11 to listOf(0x48,0,0,0x80,0,0,0)) else ""
                    else -> error("Unexpected read")
                }
            },observedProtocol={protocol},transmit={
                assertTrue(saved)
                assertFalse(attempted)
                attempted=true
                sent += if (clear) "04" else "08010000000000"
                if (!clear) encode(0x10 to listOf(0x48,1,0,0,0,0,0))
                else if (missingAck) encode(0x10 to listOf(0x44))
                else encode(0x10 to listOf(0x44),0x11 to listOf(0x44))
            },saveBaseline={
                assertFalse(it.attempted)
                assertEquals("BASELINE_SAVED",it.outcome)
                if (failSave) error("Disk unavailable")
                saved=true
            })
        return result to sent
    }

    @Test fun allVariantsRetainPerSourceDtcsAndSingleTransmission() = runBlocking {
        for (p in 1..5) {
            val (result,sent)=recipe(protocol=p)
            assertEquals("ACKNOWLEDGED_READBACK",result.outcome)
            assertEquals(listOf("P0123"),result.before["10"]?.get("03"))
            assertEquals(emptyList<String>(),result.after["10"]?.get("03"))
            assertEquals(listOf("P0123"),result.after["10"]?.get("0A"))
            assertNull(result.after["11"]?.get("0A"))
            assertEquals(1,sent.count { it=="04" })
        }
    }

    @Test fun failedPrerequisitesPreventTransmission() = runBlocking {
        for ((result,sent) in listOf(recipe(wrongVin=true),recipe(moving=true),recipe(extra=true),
            recipe(failSave=true),recipe(clear=false,secondEvap=true))) {
            assertEquals("NOT_TESTED",result.outcome)
            assertFalse(result.attempted)
            assertFalse(sent.any { it=="04" || it=="08010000000000" })
        }
    }

    @Test fun uncertainAndPartialReadbackAreNotRetried() = runBlocking {
        val (missing,sent)=recipe(missingAck=true)
        assertEquals("UNCERTAIN",missing.outcome)
        assertEquals(1,sent.count { it=="04" })
        val (lost,after)=recipe(failAfter=true)
        assertEquals("ACKNOWLEDGED_PARTIAL",lost.outcome)
        assertEquals(1,after.count { it=="04" })
        assertEquals("ACKNOWLEDGED",lost.acknowledgments["10"])
        val (pending,_)=recipe(missingPending=true)
        assertEquals("ACKNOWLEDGED_PARTIAL",pending.outcome)
        assertNull(pending.after["10"]?.get("07"))
    }

    @Test fun evapAcknowledgesPreparationOnly() = runBlocking {
        val (result,sent)=recipe(clear=false)
        assertEquals("ACKNOWLEDGED",result.outcome)
        assertEquals("LEAK_TEST_NOT_PERFORMED",result.reason)
        assertEquals(1,sent.count { it=="08010000000000" })
    }
}
