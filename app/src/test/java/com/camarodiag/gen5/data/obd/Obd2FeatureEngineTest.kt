package com.camarodiag.gen5.data.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Obd2FeatureEngineTest {

    @Test
    fun decodesMode09CalibrationIdAndCvnRecords() = runBlocking {
        val commands = mutableListOf<String>()
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            commands += command
            when (command) {
                "0904" -> "49040141424344454647483132333435363738"
                "0906" -> "4906019F3E61C2"
                else -> "NO DATA"
            }
        })

        assertEquals(listOf("ABCDEFGH12345678"), engine.readCalibrationIds())
        assertEquals(listOf("9F3E61C2"), engine.readCalibrationVerificationNumbers())
        assertTrue(commands.containsAll(listOf("0904", "0906")))
    }

    @Test
    fun readsAndDecodesEngineRpmFromSupportedPid() = runBlocking {
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            when (command) {
                "0100" -> "410000100000"
                "010C" -> "410C1AF8"
                else -> "NO DATA"
            }
        })

        val supported = engine.discoverSupportedPids()
        assertTrue(0x0C in supported)
        val rpm = engine.readLiveData(supported).single { it.pid == 0x0C }
        assertEquals("1726.00", rpm.value)
        assertEquals("rpm", rpm.unit)
    }

    @Test
    fun stopsPidDiscoveryWhenTheAdapterDoesNotReturnAValidBitmap() = runBlocking {
        val requested = mutableListOf<String>()
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            requested += command
            "NO DATA"
        })

        assertTrue(engine.discoverSupportedPids().isEmpty())
        assertEquals(listOf("0100"), requested)
    }

    @Test
    fun decodesConfirmedPendingAndPermanentDiagnosticCodes() = runBlocking {
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            when (command) {
                "03" -> "4301710000"
                "07" -> "4701330000"
                "0A" -> "4A01020000"
                else -> "NO DATA"
            }
        })

        val (confirmed, pending, permanent) = engine.readDtcs()
        assertEquals("P0171", confirmed.single().code)
        assertEquals("P0133", pending.single().code)
        assertEquals("P0102", permanent.single().code)
    }

    @Test
    fun parsesMode09VinWithCountByte() = runBlocking {
        val vin = "1G1FA1RXXR0123456"
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            if (command == "0902") "490201${vin.toByteArray().joinToString("") { "%02X".format(it) }}" else "NO DATA"
        })
        assertEquals(vin, engine.readVin())
    }
}
