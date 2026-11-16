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
    fun discoversObservedFunctionalObdCanResponseIdsAndRestoresElmFormatting() = runBlocking {
        val commands = mutableListOf<String>()
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            commands += command
            when (command) {
                "0100" -> "7E8 06 41 00 FF FF FF FF 7E9 06 41 00 80 00 00 00"
                else -> "OK"
            }
        })

        assertEquals(listOf("7E8", "7E9"), engine.discoverFunctionalObdResponders())
        assertTrue(commands.containsAll(listOf("ATS1", "ATH1", "0100", "ATH0", "ATS0")))
    }

    @Test
    fun doesNotTreatFunctionalObdRequestIdAsAnEcuResponse() = runBlocking {
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            if (command == "0100") "7DF 06 41 00 FF FF FF FF" else "OK"
        })

        assertTrue(engine.discoverFunctionalObdResponders().isEmpty())
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
    @Test
    fun decodesWidebandOxygenRatioAndVoltage() = runBlocking {
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            if (command == "0124") "4124 80 00 40 00" else "NO DATA"
        })

        val values = engine.readLiveData(setOf(0x24))

        assertEquals(2, values.size)
        assertEquals("O2 Bank 1 sensor 1 equivalence ratio", values[0].name)
        assertEquals("1.00", values[0].value)
        assertEquals("lambda", values[0].unit)
        assertEquals("O2 Bank 1 sensor 1 voltage", values[1].name)
        assertEquals("2.00", values[1].value)
        assertEquals("V", values[1].unit)
    }

    @Test
    fun decodesWidebandOxygenCurrent() = runBlocking {
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            if (command == "0134") "4134 80 00 80 00" else "NO DATA"
        })

        val values = engine.readLiveData(setOf(0x34))

        assertEquals(2, values.size)
        assertEquals("O2 Bank 1 sensor 1 equivalence ratio", values[0].name)
        assertEquals("1.00", values[0].value)
        assertEquals("O2 Bank 1 sensor 1 current", values[1].name)
        assertEquals("0.00", values[1].value)
        assertEquals("mA", values[1].unit)
    }

    @Test
    fun labelsWidebandOxygenSensorsByBankAndPosition() = runBlocking {
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            if (command == "0128") "4128 80 00 40 00" else "NO DATA"
        })

        val values = engine.readLiveData(setOf(0x28))

        assertEquals("O2 Bank 2 sensor 1 equivalence ratio", values[0].name)
        assertEquals("O2 Bank 2 sensor 1 voltage", values[1].name)
    }

    @Test
    fun decodesZeroEvaporativeVapourPressureAtTheStandardOffset() = runBlocking {
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            if (command == "0132") "4132 8000" else "NO DATA"
        })

        val value = engine.readLiveData(setOf(0x32)).single()

        assertEquals("0.00", value.value)
        assertEquals("Pa", value.unit)
    }

    @Test
    fun decodesFuelRailPressureRelativeToManifoldVacuum() = runBlocking {
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            if (command == "0122") "4122 03E8" else "NO DATA"
        })

        val value = engine.readLiveData(setOf(0x22)).single()

        assertEquals("Fuel rail pressure relative to manifold vacuum", value.name)
        assertEquals("79.00", value.value)
        assertEquals("kPa", value.unit)
    }

    @Test
    fun decodesCatalystTemperature() = runBlocking {
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            if (command == "013C") "413C 0320" else "NO DATA"
        })

        val value = engine.readLiveData(setOf(0x3C)).single()

        assertEquals("Bank 1 sensor 1 catalyst temperature", value.name)
        assertEquals("40.00", value.value)
        assertEquals("°C", value.unit)
    }

    @Test
    fun ignoresElmBufferFullTextInsteadOfParsingItAsHexBytes() = runBlocking {
        val engine = Obd2FeatureEngine(ObdCommandChannel { _, _ -> "BUFFER FULL" })

        assertTrue(engine.readLiveData(setOf(0x22)).isEmpty())
    }

    @Test
    fun identifiesElmAdapterAndReturnsAdapterDescription() = runBlocking {
        val commands = mutableListOf<String>()
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            commands += command
            when (command) {
                "ATI" -> "ELM327 v1.5"
                "AT@1" -> "OBDII to RS232 Interpreter"
                "ATDP" -> "ISO 15765-4 CAN (11 bit ID, 500 kbaud)"
                "ATRV" -> "12.4V"
                else -> "OK"
            }
        })

        val info = engine.initialize()

        assertEquals("ELM327 v1.5", info["IDENTIFICATION"])
        assertEquals("OBDII to RS232 Interpreter", info["DESCRIPTION"])
        assertTrue(commands.containsAll(listOf("ATZ", "ATSP6", "ATI", "AT@1", "ATDP", "ATRV")))
    }

}
