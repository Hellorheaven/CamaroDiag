package com.camarodiag.gen5.data.obd

import org.junit.Assert.assertEquals
import org.junit.Test

class CanConsoleLiveDiagnosticCorrelationTest {
    private fun frame(input: String) = requireNotNull(RawCanFrameParser.parse(input))

    @Test fun genuinePhysicalMultiframeResponseWithinDeadlineIsAccepted() {
        val correlation = CanConsoleLiveDiagnosticCorrelation(frame("7E0 03 22 F1 90"), 500)
        correlation.append("7E9 08 10 0A 62 F1 90 31 32 33\r\n", 100)
        correlation.append("7E8 08 10 0A 62 F1 90 31 32 33\r", 110)
        correlation.append("\n7E9 05 21 34 35 36 37\r\n", 120)
        correlation.append("7E8 05 21 34 35 36 37\r\n", 130)
        assertEquals(CanConsoleDiagnosticStatus.POSITIVE, correlation.complete(131))
    }

    @Test fun lateIsoTpContinuationNeverBecomesAnEcuResult() {
        val correlation = CanConsoleLiveDiagnosticCorrelation(frame("7E0 03 22 F1 90"), 200)
        correlation.append("7E8 08 10 0A 62 F1 90 31 32 33\r\n", 10)
        correlation.append("7E8 05 21 34 35 36 37\r\n", 210)
        assertEquals(CanConsoleDiagnosticStatus.NONE, correlation.complete(211))
    }

    @Test fun functionalResponsesAreAssembledIndependentlyPerEcu() {
        val correlation = CanConsoleLiveDiagnosticCorrelation(frame("7DF 03 22 F1 90"))
        correlation.append("7E8 08 10 0A 62 F1 90 31 32 33\r\n", 100)
        correlation.append("7E9 05 21 34 35 36 37\r\n", 110)
        assertEquals(CanConsoleDiagnosticStatus.NONE, correlation.complete(120))
        val second = CanConsoleLiveDiagnosticCorrelation(frame("7DF 03 22 F1 90"))
        second.append("7E8 08 10 0A 62 F1 90 31 32 33\r\n", 100)
        second.append("7E9 05 21 34 35 36 37\r\n", 110)
        second.append("7E8 05 21 34 35 36 37\r\n", 120)
        assertEquals(CanConsoleDiagnosticStatus.POSITIVE, second.complete(130))
    }

    @Test fun negativeEcuReplyCannotBePromotedToPositiveStatus() {
        val correlation = CanConsoleLiveDiagnosticCorrelation(frame("7E0 03 22 F1 90"))
        correlation.append("OK\r\n7E8 04 03 7F 22 31\r\n", 100)
        assertEquals(CanConsoleDiagnosticStatus.NEGATIVE, correlation.complete(101))
    }

    @Test fun allAdapterFramesAreDecodedBeforeTheUiWindowIsBounded() {
        val correlation = CanConsoleLiveDiagnosticCorrelation(frame("7E0 02 01 0C"))
        val stream = (0 until 350).joinToString("\r\n") { index ->
            if (index % 2 == 0) "7E8 04 03 41 0C 1A" else "7E9 04 03 41 0C 1B"
        } + "\r\n"
        correlation.append(stream, 50L)
        val recent = correlation.recentReceivedRecords()
        assertEquals(300, recent.size)
        assertEquals(300, recent.count { it.record.direction == "RX" })
        assertEquals("CAN ID=7E8 DLC=4 DATA=03 41 0C 1A", recent.first().record.content)
        assertEquals("CAN ID=7E9 DLC=4 DATA=03 41 0C 1B", recent.last().record.content)
        assertEquals(50L, recent.first().timestampMillis)
        assertEquals(CanConsoleDiagnosticStatus.POSITIVE, correlation.complete(51L))
    }

    @Test fun independentPhysicalReceiveChunksKeepTheirActualHostTimestamps() {
        val correlation = CanConsoleLiveDiagnosticCorrelation(frame("7E0 02 01 0C"))
        correlation.append("7E9 04 03 41 0C 1B\r\n", 10L, 1_800_000_000_010L)
        correlation.append("7E8 04 03 41 0C 1A\r\n", 20L, 1_800_000_000_020L)
        assertEquals(CanConsoleDiagnosticStatus.POSITIVE, correlation.complete(30L))
        assertEquals(listOf(1_800_000_000_010L, 1_800_000_000_020L),
            correlation.recentReceivedRecords().map { it.timestampMillis })
        assertEquals(listOf("RX", "RX"),
            correlation.recentReceivedRecords().map { it.record.direction })
    }

    @Test fun trailingFrameIsProcessedWithoutInventingMissingBytes() {
        val correlation = CanConsoleLiveDiagnosticCorrelation(frame("7E0 02 01 0C"))
        correlation.append("7E8 04 03 41", 50)
        correlation.append(" 0C 1A", 60)
        assertEquals(CanConsoleDiagnosticStatus.POSITIVE, correlation.complete(61))
        val malformed = CanConsoleLiveDiagnosticCorrelation(frame("7E0 02 01 0C"))
        malformed.append("7E8 05 03 41 0C 1A", 50)
        assertEquals(CanConsoleDiagnosticStatus.NONE, malformed.complete(60))
    }
}
