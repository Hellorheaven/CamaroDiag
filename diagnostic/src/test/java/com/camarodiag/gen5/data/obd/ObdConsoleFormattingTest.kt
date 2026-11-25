package com.camarodiag.gen5.data.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ObdConsoleFormattingTest {
    @Test fun livePidPreservesResponderBoundariesWithoutChangingFormat() = runBlocking {
        val sent = mutableListOf<String>()
        val reply = "7E8 03 41 0D 10\r7E9 03 41 0D 20"
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            sent += command
            reply
        }, physicalElmHeaders = true)
        assertEquals(reply, engine.readConsoleRequest(requireNotNull(SafeObdRequestPolicy.parse("010D"))))
        assertEquals(listOf("010D"), sent)
    }

    @Test fun identityAndDtcRequestsRestorePhysicalHeaderFormat() = runBlocking {
        for (command in listOf("0902", "03", "07", "0A")) {
            val sent = mutableListOf<String>()
            val reply = "NO DATA"
            val engine = Obd2FeatureEngine(ObdCommandChannel { text, _ ->
                sent += text
                if (text == command) reply else "OK"
            }, physicalElmHeaders = true)
            assertEquals(reply, engine.readConsoleRequest(requireNotNull(SafeObdRequestPolicy.parse(command))))
            assertEquals(listOf("ATH0", "ATS0", command, "ATH1", "ATS0"), sent)
        }
    }

    @Test fun malformedRestorationIsNeverReportedAsSuccessfulConsoleRead() = runBlocking {
        val sent = mutableListOf<String>()
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            sent += command
            when (command) {
                "ATH1" -> "?"
                "03" -> "4300"
                else -> "OK"
            }
        }, physicalElmHeaders = true)
        assertTrue(runCatching {
            engine.readConsoleRequest(requireNotNull(SafeObdRequestPolicy.parse("03")))
        }.exceptionOrNull() is ObdElmFormattingException)
        assertEquals(listOf("ATH0", "ATS0", "03", "ATH1", "ATS0"), sent)
    }
}
