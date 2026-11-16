package com.camarodiag.gen5.data.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CamaroLfxObdSimulatorTest {

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
        assertEquals(setOf("P0133", "P0171"), report.confirmedDtcs.map { it.code }.toSet())
        assertTrue(report.rawLog.any { it.contains("ATZ") })
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
