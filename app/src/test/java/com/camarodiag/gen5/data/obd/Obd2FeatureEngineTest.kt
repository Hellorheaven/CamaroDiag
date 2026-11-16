package com.camarodiag.gen5.data.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class Obd2FeatureEngineTest {

    @Test
    fun doesNotQueryMode01PidThatTheEcuDidNotAdvertise() = runBlocking {
        val commands = mutableListOf<String>()
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            commands += command
            "NO DATA"
        })

        assertEquals(null, engine.readMode01PidIfSupported(0xA6, setOf(0x0C)))
        assertTrue(commands.isEmpty())
    }

    @Test
    fun readsMode01PidOnlyAfterSupportBitmapConfirmsIt() = runBlocking {
        val commands = mutableListOf<String>()
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            commands += command
            if (command == "01A6") "41A6000012D687" else "NO DATA"
        })

        val response = engine.readMode01PidIfSupported(0xA6, setOf(0xA6))

        assertEquals("41A6000012D687", response)
        assertEquals(listOf("01A6"), commands)
    }

    @Test
    fun fullScanUsesOnlyReadOnlyServicesAndAdapterSetupCommands() = runBlocking {
        val commands = mutableListOf<String>()
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            commands += command
            when (command) {
                "0100", "0101" -> if (command == "0101") "NO DATA" else "4100000000"
                else -> "NO DATA"
            }
        })

        val report = engine.fullReadOnlyScan()

        assertTrue(report.supportedPids.isEmpty())
        assertTrue(commands.containsAll(listOf("ATZ", "ATSP6", "ATI", "AT@1", "03", "07", "0A", "0902", "0904", "0906", "090A", "0600")))
        assertTrue(commands.none { command ->
            command == "04" || command.startsWith("2E") || command.startsWith("31") ||
                command.startsWith("34") || command.startsWith("36") || command.startsWith("11")
        })
    }

    @Test
    fun decodesFreezeFrameDtcAndKeepsOnlyTheFrameNumberOutOfSensorData() = runBlocking {
        val commands = mutableListOf<String>()
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            commands += command
            when (command) {
                "020200" -> "4202000171"
                "020000" -> "42000008100000"
                "020500" -> "4205007F"
                "020C00" -> "420C001AF8"
                else -> "NO DATA"
            }
        })

        val triggerDtc = engine.readFreezeFrameDtc()
        val supported = engine.discoverFreezeFrameSupportedPids()
        val values = engine.readFreezeFrame(supported)

        assertEquals("P0171", triggerDtc?.code)
        assertEquals("freeze-frame", triggerDtc?.status)
        assertEquals(setOf(0x05, 0x0C), supported)
        assertEquals(listOf(0x7F), values[0x05])
        assertEquals(listOf(0x1A, 0xF8), values[0x0C])
        assertEquals(setOf(0x05, 0x0C), values.keys)
        assertEquals(listOf("020200", "020000", "020500", "020C00"), commands)
    }

    @Test
    fun discoversMode02PidsWithoutCountingBitmapContinuationMarkers() = runBlocking {
        val commands = mutableListOf<String>()
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            commands += command
            when (command) {
                "020000" -> "42000000000001"
                "022000" -> "42200000000001"
                "024000" -> "42400080000000"
                else -> "NO DATA"
            }
        })

        assertEquals(setOf(0x41), engine.discoverFreezeFrameSupportedPids())
        assertEquals(listOf("020000", "022000", "024000"), commands)
    }

    @Test
    fun doesNotQueryFreezeFrameValuesWhenNoTriggerDtcIsStored() = runBlocking {
        val commands = mutableListOf<String>()
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            commands += command
            when (command) {
                "0100" -> "410000000000"
                "020200" -> "4202000000"
                else -> "NO DATA"
            }
        })

        val report = engine.fullReadOnlyScan()

        assertNull(report.freezeFrameDtc)
        assertTrue(report.freezeFrame.isEmpty())
        assertEquals(listOf("020200"), commands.filter { it.startsWith("02") })
    }

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
    fun followsContinuationPagesWithoutReportingTheirMarkerAsADataPid() = runBlocking {
        val requested = mutableListOf<String>()
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            requested += command
            when (command) {
                // Only the continuation flag (bit 31) is set on the first two pages.
                "0100" -> "410000000001"
                "0120" -> "412000000001"
                // PID 0x41 is supported; no further page is advertised.
                "0140" -> "414080000000"
                else -> "NO DATA"
            }
        })

        assertEquals(setOf(0x41), engine.discoverSupportedPids())
        assertEquals(listOf("0100", "0120", "0140"), requested)
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
    fun decodesDirectInjectionFuelRailAbsolutePressure() = runBlocking {
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            if (command == "0159") "4159 03E8" else "NO DATA"
        })

        val value = engine.readLiveData(setOf(0x59)).single()

        assertEquals("Fuel rail absolute pressure", value.name)
        assertEquals("10000.00", value.value)
        assertEquals("kPa", value.unit)
    }

    @Test
    fun decodesRelativeAcceleratorPedalPosition() = runBlocking {
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            if (command == "015A") "415A 80" else "NO DATA"
        })

        val value = engine.readLiveData(setOf(0x5A)).single()

        assertEquals("Relative accelerator pedal position", value.name)
        assertEquals("50.20", value.value)
        assertEquals("%", value.unit)
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
