package com.camarodiag.gen5.data.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ElmUdsPayloadTransportTest {
    @Test fun physicalReadOn250kReassemblesOnlySelectedResponder() = runBlocking {
        val commands = mutableListOf<String>()
        val transport = ElmUdsPayloadTransport(0x7E0, 0x7E8, 35) { command, _ ->
            commands += command
            if (command == "03221234")
                "7E9 04 62 12 34 FF\r7E8 10 0A 62 12 34 01 02 03\r7E8 21 04 05 06 07"
            else "OK"
        }
        assertArrayEquals(byteArrayOf(0x62,0x12,0x34,1,2,3,4,5,6,7),
            transport.exchange(byteArrayOf(0x22,0x12,0x34), 3000))
        assertTrue(commands.containsAll(listOf("ATSP8", "ATSH 7E0", "ATCRA 7E8", "ATFCSH 7E0", "ATCFC1")))
    }

    @Test fun supportsSplitAndCompact29BitHeaders() {
        val expected = byteArrayOf(0x62,0x12,0x34,0x55)
        for (header in listOf("18 DA F1 10", "18DAF110")) {
            assertArrayEquals(expected, ElmUdsPayloadTransport.decode(
                "$header 04 62 12 34 55", 0x18DAF110, true))
        }
    }

    @Test fun rejectsMissingWrongSequenceDuplicateOrWrongResponder() {
        for (input in listOf(
            "7E9 04 62 12 34 55",
            "7E8 10 0A 62 12 34 01 02 03",
            "7E8 10 0A 62 12 34 01 02 03\n7E8 22 04 05 06 07",
            "7E8 04 62 12 34 55\n7E8 04 62 12 34 55",
            "7E8 04 62 12",
            "7E8 04 62 12 34 55\nBUFFER FULL"
        )) assertTrue(input, runCatching {
            ElmUdsPayloadTransport.decode(input, 0x7E8, false)
        }.isFailure)
    }

    @Test fun configurationFailureAndMutatingRequestsNeverSendEcuPayload() = runBlocking {
        val commands = mutableListOf<String>()
        val transport = ElmUdsPayloadTransport(0x7E0, 0x7E8, 33) { command, _ ->
            commands += command
            if (command == "ATCRA 7E8") "?" else "OK"
        }
        assertTrue(runCatching { transport.exchange(byteArrayOf(0x2E,0x12,0x34,0x55), 1000) }.isFailure)
        assertTrue(commands.isEmpty())
        assertTrue(runCatching { transport.exchange(byteArrayOf(0x22,0x12,0x34), 1000) }.isFailure)
        assertFalse(commands.any { it == "03221234" })
    }
}
