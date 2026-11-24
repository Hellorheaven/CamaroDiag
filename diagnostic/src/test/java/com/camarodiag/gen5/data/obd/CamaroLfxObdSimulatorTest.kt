package com.camarodiag.gen5.data.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CamaroLfxObdSimulatorTest {

    @Test fun missingAdvertisedSupportPagePreservesEarlierObservations() = runBlocking {
        val engine = Obd2FeatureEngine { command, _ ->
            when (command) {
                "0100" -> "410004000001"
                "0120" -> "412000000001"
                "0140" -> "NO DATA"
                else -> error("Unexpected PID request")
            }
        }
        val discovery = engine.discoverPidAvailability()
        assertEquals(setOf(0x06), discovery.supported)
        assertEquals(0x41, discovery.firstUnresolvedPid)
        val terminal = Obd2FeatureEngine { _, _ -> "410000000000" }.discoverPidAvailability()
        assertEquals(emptySet<Int>(), terminal.supported)
        assertEquals(null, terminal.firstUnresolvedPid)
    }

    @Test
    fun fullScanReturnsClearlySyntheticLfxDataWithoutHardware() = runBlocking {
        val report = CamaroLfxObdSimulator.createEngine().fullReadOnlyScan()

        assertTrue(report.supportedPids.containsAll(setOf(0x0C, 0x22, 0x24, 0x59, 0x5A)))
        assertTrue(0x20 !in report.supportedPids)
        assertTrue(0x40 !in report.supportedPids)
        assertTrue(report.respondingEcuAddresses.contains("7E8"))
        assertEquals("1G1TEST0000000001", report.vin)
        assertEquals(listOf("LFX12SIM00000001"), report.calibrationIds)
        assertEquals(listOf("12345678"), report.calibrationVerificationNumbers)
        assertEquals(listOf("CAMARODIAG LFX SIM"), report.ecuNames)
        assertTrue(report.adapterInfo["IDENTIFICATION"].orEmpty().contains("SIMULATED"))
        assertTrue(report.adapterInfo["DESCRIPTION"].orEmpty().contains("Offline Simulator"))
        assertTrue(report.readiness.orEmpty().contains("MIL=OFF"))
        assertTrue(report.readiness.orEmpty().contains("DTC_COUNT=2"))
        assertTrue(report.liveData.any { it.pid == 0x0C && it.value == "750.00" })
        assertTrue(report.liveData.any { it.pid == 0x22 && it.value == "79.00" })
        assertTrue(report.liveData.any { it.pid == 0x24 && it.name.contains("Bank 1 sensor 1") })
        assertEquals("P0171", report.freezeFrameDtc?.code)
        assertEquals(listOf(0x0B, 0xB8), report.freezeFrame[0x0C])
        assertEquals(listOf(0x7F), report.freezeFrame[0x05])
        assertEquals(setOf("P0133", "P0171"), report.confirmedDtcs.map { it.code }.toSet())
        assertTrue(report.rawLog.any { it.contains("ATZ") })
    }

    @Test
    fun everyAdvertisedDataPidHasAValidPositiveResponse() = runBlocking {
        val supported = CamaroLfxObdSimulator.createEngine().discoverSupportedPids()
        val expected = setOf(
            0x05, 0x0C, 0x0D, 0x0F, 0x11, 0x1F,
            0x22, 0x24, 0x2D, 0x2E, 0x2F, 0x31, 0x32, 0x33, 0x3C, 0x3E,
            0x42, 0x44, 0x46, 0x49, 0x4A, 0x4C, 0x4D, 0x4E, 0x52, 0x59,
            0x5A, 0x5C, 0x5E
        )

        assertEquals(expected, supported)
        supported.forEach { pid ->
            val marker = "41%02X".format(java.util.Locale.US, pid)
            val response = CamaroLfxObdSimulator.response("01%02X".format(java.util.Locale.US, pid))
            val normalized = response.uppercase().replace(Regex("[^0-9A-F]"), "")
            assertTrue("PID %02X is advertised but has no positive response: %s".format(
                java.util.Locale.US, pid, response
            ), normalized.contains(marker))
        }
    }

    @Test
    fun simulatorNeverAnswersUnknownOrWriteCommandsAsSuccessful() {
        assertEquals("NO DATA", CamaroLfxObdSimulator.response("01AA"))
        assertEquals("NO DATA", CamaroLfxObdSimulator.response("04"))
        assertEquals("NO DATA", CamaroLfxObdSimulator.response("2E F190 00"))
    }

    @Test
    fun supportedPidBitmapsAdvertiseOnlyConfiguredPages() {
        val first = CamaroLfxObdSimulator.response("0100")
        val second = CamaroLfxObdSimulator.response("0120")
        val third = CamaroLfxObdSimulator.response("0140")

        assertTrue(first.contains("41 00"))
        assertTrue(second.contains("41 20"))
        assertTrue(third.contains("41 40"))
    }
}
