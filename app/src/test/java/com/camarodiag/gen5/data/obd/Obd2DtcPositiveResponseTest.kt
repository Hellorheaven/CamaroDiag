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

    @Test fun positiveResponseWithZeroDtcIsRealEvidence() = runBlocking {
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            if (command == "03") "430000" else "NO DATA"
        })
        val (dtcs, verified) = engine.readDtcsWithPositiveResponseProof()
        assertTrue(verified)
        assertTrue(dtcs.first.isEmpty() && dtcs.second.isEmpty() && dtcs.third.isEmpty())
    }
}
