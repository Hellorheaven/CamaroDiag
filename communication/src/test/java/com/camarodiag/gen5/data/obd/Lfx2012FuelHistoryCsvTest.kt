package com.camarodiag.gen5.data.obd

import java.io.StringWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Lfx2012FuelHistoryCsvTest {
    private val pids = Lfx2012FuelReadOnly.PIDS
    private val identity = Lfx2012FuelSessionHistory.SessionIdentity(
        "1G1FA1RXXR0123456", "session-1", Lfx2012FuelReadOnly.Source.J2534
    )

    private fun sample(
        time: Long, ethanol: Int?,
        identity: Lfx2012FuelSessionHistory.SessionIdentity = this.identity
    ): Lfx2012FuelSessionHistory.Sample {
        val survey = Lfx2012FuelReadOnly.Survey(
            Lfx2012FuelReadOnly.Source.J2534, pids.toSet(),
            pids.associateWith { "raw" }
        )
        val bytes = pids.associateWith { pid ->
            when (pid) {
                Lfx2012FuelReadOnly.ETHANOL -> ethanol?.let { intArrayOf(it) }
                else -> intArrayOf(128)
            }
        }
        return Lfx2012FuelSessionHistory.Sample(
            identity, Lfx2012FuelReadOnly.inspect(survey, bytes, time)
        )
    }

    @Test fun csvKeepsZeroAndUnavailableEthanolDistinct() {
        val output = StringWriter()
        Lfx2012FuelHistoryCsv.export(
            listOf(sample(100, 0), sample(200, null)), output
        )
        val lines = output.toString().trimEnd().lines()
        assertEquals(11, lines.size)
        assertTrue(lines[0].contains("availability,reported_percent,raw_byte"))
        assertTrue(lines[1].contains(",52,REPORTED,0.00000000,0"))
        assertTrue(lines[6].endsWith(",52,NO_VALID_RESPONSE,,"))
        assertFalse(lines[6].contains(",0.00000000,"))
    }

    @Test fun csvEscapesSessionIdentifierAndNeutralizesSpreadsheetFormulas() {
        val output = StringWriter()
        val risky = identity.copy(sessionId = "=SUM(1,2)\"injected")
        Lfx2012FuelHistoryCsv.export(listOf(sample(100, 128, risky)), output)
        assertTrue(output.toString().contains("\"'=SUM(1,2)\"\"injected\""))
    }

    @Test fun invalidMixedSessionHistoryWritesNoPartialCsv() {
        val output = StringWriter()
        val other = identity.copy(sessionId = "different")
        assertTrue(runCatching {
            Lfx2012FuelHistoryCsv.export(
                listOf(sample(100, 0), sample(200, 128, other)), output
            )
        }.isFailure)
        assertEquals("", output.toString())
    }
    @Test fun leadingSpacesCannotHideCsvFormulaPrefix() {
        val output = StringWriter()
        val risky = identity.copy(sessionId = "  =1+1")
        Lfx2012FuelHistoryCsv.export(listOf(sample(100, 0, risky)), output)
        assertTrue(output.toString().contains("\"'  =1+1\""))
    }

    @Test fun sessionIdsWithLineBreaksOrTabsAreRejectedBeforeExport() {
        assertTrue(runCatching {
            identity.copy(sessionId = "session\\nsecond")
        }.isFailure)
        assertTrue(runCatching {
            identity.copy(sessionId = "session\\tsecond")
        }.isFailure)
        assertTrue(runCatching {
            identity.copy(sessionId = "a".repeat(129))
        }.isFailure)
    }

}
