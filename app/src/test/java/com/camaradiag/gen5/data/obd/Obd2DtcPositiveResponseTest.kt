package com.camarodiag.gen5.data.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Obd2DtcPositiveResponseTest {
    @Test fun noDataIsNotMistakenForAWorkingDtcService() = runBlocking {
        val commands = mutableListOf<String>()
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            commands += command
            "NO DATA"
        })
        val (dtcs, verified) = engine.readDtcsWithPositiveResponseProof()
        assertFalse(verified)
        assertTrue(dtcs.first.isEmpty() && dtcs.second.isEmpty() && dtcs.third.isEmpty())
        assertEquals(listOf("03", "07", "0A"), commands)
    }

    @Test fun paddedCanAndNumberedElmResponsesAreDecodedWithoutPaddingCodes() = runBlocking {
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            when (command) {
                "03" -> "7E8 04 43 01 03 00 AA AA AA>"
                "07" -> "008\r0: 47 03 01 00 02 00\r1: 03 00"
                else -> "7E9 02 4A 00 00 00 00 00 00"
            }
        })
        val modes = engine.readDtcModeEvidence()
        assertEquals(listOf("P0300"), modes[3]?.map { it.code })
        assertEquals(listOf("P0100", "P0200", "P0300"), modes[7]?.map { it.code })
        assertEquals(emptyList<ObdDtc>(), modes[10])
    }

    @Test fun incompleteNumberedElmResponseIsNotPositiveEvidence() = runBlocking {
        val engine = Obd2FeatureEngine(ObdCommandChannel { _, _ ->
            "008\r0: 43 03 01 00 02"
        })
        assertEquals(null, engine.readDtcModeEvidence()[3])
    }

    @Test fun physicalMultiFrameDtcResponsesAreReassembledPerEcu() = runBlocking {
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            if (command == "03") """
                7E8 10 08 43 03 01 00 02 00
                18DAF110 08 10 08 43 03 03 01 04 01
                7E8 21 03 00 AA AA AA AA AA
                18DAF110 03 21 05 01
                7EA 02 43 00 00 00 00 00 00
            """.trimIndent() else "NO DATA"
        })
        val modes = engine.readDtcModeEvidence()
        assertEquals(setOf("P0100", "P0200", "P0300", "P0301", "P0401", "P0501"),
            modes[3]?.map { it.code }?.toSet())
        assertEquals(null, modes[7])
    }

    @Test fun incompleteOrMisorderedPhysicalDtcResponsesCannotProveSuccess() = runBlocking {
        val first = "7E8 10 08 43 03 01 00 02 00"
        for (response in listOf(
            first,
            first + "\n7E9 21 03 00",
            first + "\n7E8 22 03 00",
            first + "\n7E8 21 03",
            first + "\n7E8 21 00 00",
            first + "\n7E8 21 03 00\n7E8 21 03 00",
            "7E9 02 43 00\n" + first,
            first + "\n7E8 21 03 00\n7E8 04 43 01 05 00"
        )) {
            val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
                if (command == "03") response else "NO DATA"
            })
            assertEquals(response, null, engine.readDtcModeEvidence()[3])
        }
    }

    @Test fun positiveResponseWithZeroDtcIsRealEvidence() = runBlocking {
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            if (command == "03") "430000" else "NO DATA"
        })
        val (dtcs, verified) = engine.readDtcsWithPositiveResponseProof()
        assertTrue(verified)
        assertTrue(dtcs.first.isEmpty() && dtcs.second.isEmpty() && dtcs.third.isEmpty())
    }
}
