package com.camarodiag.gen5.data.obd

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class Obd2FeatureEngineTest {

    @Test
    fun physicalScanNeverIssuesElmOrFunctionalDiscoveryCommands() = runBlocking {
        val commands = mutableListOf<String>()
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            commands += command
            when(command) {
                "0100" -> "410000100000"
                "010C" -> "410C1AF8"
                "03" -> "43010300"
                "07" -> "4700"
                "0A" -> "4A00"
                "020200" -> "4202000000"
                "0600" -> "7F0611"
                else -> "NO DATA"
            }
        })
        val decodedModes = mutableListOf<Int>()
        val report = engine.fullPhysicalCanScan("7E8", mapOf("PROTOCOL" to "J2534 / ISO 15765-4")) { mode, raw ->
            decodedModes += mode
            when(mode) {
                3 -> { assertEquals("43010300",raw); listOf(ObdDtc("P0300","confirmed")) }
                7 -> { assertEquals("4700",raw); emptyList() }
                else -> { assertEquals("4A00",raw); emptyList() }
            }
        }
        assertEquals(listOf(3,7,10),decodedModes)
        assertEquals("7E8",report.physicalCanResponder)
        assertEquals(listOf("7E8"),report.respondingEcuAddresses)
        assertEquals("P0300",report.confirmedDtcs.single().code)
        assertEquals("1726.00",report.liveData.single { it.pid == 0x0C }.value)
        assertNull(report.monitorTestRaw)
        assertTrue(commands.none { it.startsWith("AT") || it == "04" })
        assertEquals(1,commands.count { it == "0100" })
        assertTrue(report.rawLog.any { it.contains("0600 -> 7F0611") })
    }

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
        assertTrue(commands.containsAll(listOf("ATI", "AT@1", "03", "07", "0A", "0902", "0904", "0906", "090A", "0600")))
        assertTrue(commands.none { it == "ATZ" || it.startsWith("ATSP") || it.startsWith("ATTP") })
        assertEquals(setOf(3, 7, 10), report.unavailableDtcModes)
        assertTrue(commands.none { command ->
            command == "04" || command.startsWith("2E") || command.startsWith("31") ||
                command.startsWith("34") || command.startsWith("36") || command.startsWith("11")
        })
    }

    @Test
    fun fullScanKeepsPartialDtcEvidenceAndExistingProtocol() = runBlocking {
        val commands = mutableListOf<String>()
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            commands += command
            when (command) {
                "ATDP" -> "ISO 15765-4 CAN (29 bit ID, 250 kbaud)"
                "0100" -> "410000000000"
                "03" -> "NO DATA"
                "07" -> "47010100"
                "0A" -> "4A00"
                else -> "NO DATA"
            }
        })
        val report = engine.fullReadOnlyScan()
        assertEquals(setOf(3), report.unavailableDtcModes)
        assertEquals(listOf(ObdDtc("P0100", "pending")), report.pendingDtcs)
        assertTrue(report.confirmedDtcs.isEmpty())
        assertTrue(report.adapterInfo.getValue("PROTOCOL").contains("29 bit"))
        assertTrue(commands.none { it == "ATZ" || it.startsWith("ATSP") })
    }

    @Test
    fun physicalScanKeepsOtherModesWhenConfirmedIsExplicitlyUnavailable() = runBlocking {
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            if (command == "0100") "410000000000" else "NO DATA"
        })
        val report = engine.fullPhysicalCanScan("7E8", emptyMap()) { mode, _ ->
            when (mode) {
                3 -> null
                7 -> listOf(ObdDtc("P0100", "pending"))
                else -> emptyList()
            }
        }
        assertEquals(setOf(3), report.unavailableDtcModes)
        assertEquals("P0100", report.pendingDtcs.single().code)
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
    fun machEObservedPidValuesAndOdometerRemainSeparate() = runBlocking {
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            when (command) {
                "0105" -> "7E8 03 41 05 FF 7EA 03 41 05 00 7EC 03 41 05 37"
                "0131" -> "7E8 04 41 31 39 3D"
                "01A6" -> "41A6000BD967"
                "011C" -> "411C03 411C03"
                "0101" -> "410100040000 410100278000"
                else -> "NO DATA"
            }
        })
        val rows = engine.readLiveData(setOf(5, 0x31, 0xA6, 0x1C))
        assertEquals(setOf("7E8", "7EA", "7EC"), rows.filter { it.pid == 5 }.map { it.responder }.toSet())
        val temperatures = rows.filter { it.pid == 5 }.associateBy { it.responder }
        assertEquals("215.00", temperatures["7E8"]?.value)
        assertEquals(true, temperatures["7E8"]?.temperatureAtEncodingLimit)
        assertEquals("-40.00", temperatures["7EA"]?.value)
        assertEquals(true, temperatures["7EA"]?.temperatureAtEncodingLimit)
        assertEquals("15.00", temperatures["7EC"]?.value)
        assertEquals(false, temperatures["7EC"]?.temperatureAtEncodingLimit)
        assertEquals(false, rows.single { it.pid == 0xA6 }.temperatureAtEncodingLimit)
        assertEquals("77655.10", rows.single { it.pid == 0xA6 }.value)
        assertEquals("14653.00", rows.single { it.pid == 0x31 }.value)
        assertEquals(listOf(3), rows.single { it.pid == 0x1C }.raw)
        assertEquals(2, engine.readReadiness()?.lines()?.size)
        assertEquals(77655.1, CamaroUdsProtocol.parseOdometerPidA6("41A6000BD967") ?: -1.0, 0.0001)
        assertNull(CamaroUdsProtocol.parseOdometerPidA6("41A6000BD967\\r41A600000001".replace("\\r", "\r")))
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
                "0100" -> "7E0 06 41 00 FF FF FF FF 7DF 06 41 00 FF FF FF FF 7E8 06 41 00 FF FF FF FF 7E9 06 41 00 80 00 00 00"
                else -> "OK"
            }
        })

        assertEquals(listOf("7E8", "7E9"), engine.discoverFunctionalObdResponders())
        assertTrue(commands.containsAll(listOf("ATS1", "ATH1", "0100", "ATH0", "ATS0")))
    }

    @Test
    fun physicalElmRecordsRestoreHeadersAndRetainIndependentReplies() = runBlocking {
        val commands = mutableListOf<String>()
        val vin = "3FMTK3SU0PMA00001"
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            commands += command
            when (command) {
                "0902" -> "490201" + vin.toByteArray().joinToString("") { "%02X".format(it) }
                "03" -> "43 01 03 00\r43 01 01 00"
                "07" -> "47 00"
                "0A" -> "NO DATA"
                "010C" -> "7E8 04 41 0C 1A F8\r7E9 04 41 0C 00 00"
                else -> "OK"
            }
        }, physicalElmHeaders = true)
        assertEquals(vin, engine.readVinForVerification())
        assertEquals(listOf("ATH0", "ATS0", "0902", "ATH1", "ATS0"), commands)
        commands.clear()
        val modes = engine.readDtcModeEvidence()
        assertEquals(setOf("P0300", "P0100"), modes[3]?.map { it.code }?.toSet())
        assertEquals(emptyList<ObdDtc>(), modes[7])
        assertNull(modes[10])
        assertEquals(listOf("ATH0", "ATS0", "03", "07", "0A", "ATH1", "ATS0"), commands)
        val rpm = engine.readLiveData(setOf(0x0C)).associateBy { it.responder }
        assertEquals("1726.00", rpm["7E8"]?.value)
        assertEquals("0.00", rpm["7E9"]?.value)
    }

    @Test
    fun failedFormattingRestorationCannotBeReportedAsSuccessfulIdentity() = runBlocking {
        val commands = mutableListOf<String>()
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            commands += command
            if (command == "ATH1") "?" else "OK"
        }, physicalElmHeaders = true)
        var failed = false
        try { engine.readVinForVerification() }
        catch (_: ObdElmFormattingException) { failed = true }
        assertTrue(failed)
        assertEquals(listOf("ATH1", "ATS0"), commands.takeLast(2))
    }

    @Test
    fun verifiesFreshVinWithBoundedRetryAndNoSavedFallback() = runBlocking {
        val commands = mutableListOf<String>()
        val vin = "3FMTK3SU0PMA00001"
        var reads = 0
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            commands += command
            when (command) {
                "0902" -> if (++reads == 1) "NO DATA" else "490201" + vin.toByteArray().joinToString("") { "%02X".format(it) }
                else -> "OK"
            }
        })
        assertEquals(vin, engine.readVinForVerification())
        assertEquals(listOf("ATH0", "ATS0", "0902", "0902"), commands)
    }

    @Test
    fun distinguishesZeroPidBitmapAndZeroDtcResponseFromNoResponse() = runBlocking {
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            when (command) {
                "0100" -> "410000000000"
                "03" -> "4300"
                "07" -> "NO DATA"
                "0A" -> "4A00"
                else -> "NO DATA"
            }
        })
        val bitmap = engine.discoverPidAvailability()
        assertTrue(bitmap.supported.isEmpty())
        assertNull(bitmap.firstUnresolvedPid)
        val modes = engine.readDtcModeEvidence()
        assertEquals(emptyList<ObdDtc>(), modes[3])
        assertNull(modes[7])
        assertEquals(emptyList<ObdDtc>(), modes[10])
    }

    @Test
    fun doesNotTreatFunctionalObdRequestIdAsAnEcuResponse() = runBlocking {
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            if (command == "0100") "7DF 06 41 00 FF FF FF FF" else "OK"
        })

        assertTrue(engine.discoverFunctionalObdResponders().isEmpty())
    }

    @Test
    fun machEHeaderlessBitmapsRemainAUnionNotOneEcu() = runBlocking {
        val replies = mapOf(
            "0100" to "410098180013 410000000000 410098180003 410098180003 4100AA082013",
            "0120" to "412080018001 412080018001 412080018001 41208003A001",
            "0140" to "4140D4C08001 414040800000 414040800000 414040800020",
            "0160" to "416002000001",
            "0180" to "418000000049",
            "01A0" to "41A004000000",
            "01A6" to "41A6000BD967",
            "0105" to "4105FF 410500 410537 4105FF"
        )
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ -> replies[command] ?: "NO DATA" })
        val result = engine.discoverPidAvailability()
        assertTrue(result.attributionIncomplete)
        assertTrue(result.byResponder.isEmpty())
        assertTrue(0xA6 in result.supported)
        assertNull(result.firstUnresolvedPid)
        val measured = engine.readLiveData(result.supported)
        assertEquals("77655.10", measured.single { it.pid == 0xA6 }.value)
        assertTrue(measured.none { it.pid == 5 }) // anonymous conflicting values
        assertTrue(engine.discoverPidAvailability(primaryPowertrainResponderOnly = true).supported.isEmpty())
    }

    @Test
    fun taggedBitmapsKeepEmptyResponderAndRejectAnonymousMeasurement() = runBlocking {
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            when (command) {
                "0100" -> "7E8 06 41 00 08 00 00 00\r7EA 06 41 00 00 00 00 00"
                "0105" -> "410537"
                else -> "NO DATA"
            }
        })
        val result = engine.discoverPidAvailability()
        assertEquals(setOf(5), result.byResponder["7E8"])
        assertEquals(emptySet<Int>(), result.byResponder["7EA"])
        assertFalse(result.attributionIncomplete)
        assertTrue(engine.readLiveData(result.supported, result.byResponder).isEmpty())
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
    fun verifiesElmMultilineVinAcrossTransportFraming() = runBlocking {
        val reply = "014\r0: 490201314731\r1: 46413152585852\r2: 30313233343536"
        suspend fun vin(raw: String) =
            Obd2FeatureEngine(ObdCommandChannel { _, _ -> raw }).readVin()
        assertEquals("1G1FA1RXXR0123456", vin(reply))
        assertEquals("1G1FA1RXXR0123456", vin(reply.replace('\r', ' ')))
        assertNull(vin(reply.replace("1:", "2:")))
        assertNull(vin(reply.dropLast(2)))
        assertNull(vin(reply.replace("014", "013")))
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
    fun keepsEmissionRequirementsPidAsAnEncodedValue() = runBlocking {
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            if (command == "015F") "415F10" else "NO DATA"
        })

        val value = engine.readLiveData(setOf(0x5F)).single()

        assertEquals("Emission requirements to which vehicle is designed", value.name)
        assertEquals("10", value.value)
        assertEquals("raw", value.unit)
        assertEquals(listOf(0x10), value.raw)
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

    @Test
    fun readsStandardEcuIdentityAndKeepsResponderAddressesUnmapped() = runBlocking {
        val vin = "1G1FA1RXXR0123456"
        val calId = "ABCDEFGH12345678"
        val ecuName = "GM ECM MODULE".padEnd(20, ' ')
        val commands = mutableListOf<String>()
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            commands += command
            when (command) {
                "0100" -> "7E8 06 41 00 FF FF FF FF"
                "0902" -> "490201" + vin.toByteArray().joinToString("") { "%02X".format(it) }
                "0904" -> "490401" + calId.toByteArray().joinToString("") { "%02X".format(it) }
                "0906" -> "4906019F3E61C2"
                "090A" -> "490A01" + ecuName.toByteArray().joinToString("") { "%02X".format(it) }
                else -> "OK"
            }
        })

        val identity = engine.readEcuIdentification()

        assertEquals(vin, identity.vin)
        assertEquals(listOf(calId), identity.calibrationIds)
        assertEquals(listOf("9F3E61C2"), identity.calibrationVerificationNumbers)
        assertEquals(listOf("GM ECM MODULE"), identity.ecuNames)
        assertEquals(listOf("7E8"), identity.respondingEcuAddresses)
        assertTrue(commands.containsAll(listOf("0100", "0902", "0904", "0906", "090A")))
        assertTrue(commands.containsAll(listOf("ATH0", "ATS0")))
    }

    @Test
    fun retainsAvailableIdentityFieldsWhenOptionalMode09EcuNameIsUnsupported() = runBlocking {
        val vin = "1G1FA1RXXR0123456"
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            when (command) {
                "0902" -> "490201" + vin.toByteArray().joinToString("") { "%02X".format(it) }
                "0904" -> "49040141424344454647483132333435363738"
                "0906" -> "4906019F3E61C2"
                "090A" -> "NO DATA"
                else -> "NO DATA"
            }
        })

        val identity = engine.readEcuIdentification()

        assertEquals(vin, identity.vin)
        assertEquals(listOf("ABCDEFGH12345678"), identity.calibrationIds)
        assertEquals(listOf("9F3E61C2"), identity.calibrationVerificationNumbers)
        assertTrue(identity.ecuNames.isEmpty())
    }

    @Test
    fun discoversAndReadsEthanolPidOnlyWhenEcuAdvertisesIt() = runBlocking {
        val commands = mutableListOf<String>()
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            commands += command
            when (command) {
                "0100" -> "410000000001"
                "0120" -> "412000000001"
                "0140" -> "414000004000"
                "0152" -> "415280"
                else -> "NO DATA"
            }
        })
        val supported = engine.discoverSupportedPids()
        assertTrue(0x52 in supported)
        val response = engine.readMode01PidIfSupported(0x52, supported)
        assertEquals("415280", response)
        assertEquals(listOf("0100", "0120", "0140", "0152"), commands)
    }

    @Test
    fun absentEthanolBitmapBitNeverSendsPid52() = runBlocking {
        val commands = mutableListOf<String>()
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            commands += command
            when (command) {
                "0100" -> "410000000001"
                "0120" -> "412000000001"
                "0140" -> "414000000000"
                else -> "NO DATA"
            }
        })
        val supported = engine.discoverSupportedPids()
        assertTrue(0x52 !in supported)
        assertNull(engine.readMode01PidIfSupported(0x52, supported))
        assertTrue("0152" !in commands)
    }

    @Test
    fun fullScanEthanolValueRequiresExactlyOneRealPayloadByte() = runBlocking {
        val valid = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            if (command == "0152") "415280" else "NO DATA"
        })
        val actual = valid.readLiveData(setOf(0x52))
        assertEquals(1, actual.size)
        assertEquals(0x52, actual.single().pid)
        assertEquals("50.20", actual.single().value)

        val malformed = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            if (command == "0152") "41528000" else "NO DATA"
        })
        assertTrue(malformed.readLiveData(setOf(0x52)).isEmpty())
    }

    @Test
    fun mode09IdentityCountCannotIgnoreTrailingOrMissingCalibrationBytes() = runBlocking {
        suspend fun calibrations(reply: String): List<String> =
            Obd2FeatureEngine(ObdCommandChannel { command, _ ->
                if (command == "0904") reply else "NO DATA"
            }).readCalibrationIds()
        val bytes = "ABCDEFGH12345678".toByteArray(Charsets.US_ASCII)
            .joinToString("") { "%02X".format(java.util.Locale.ROOT, it.toInt() and 0xFF) }
        assertEquals(listOf("ABCDEFGH12345678"), calibrations("490401" + bytes))
        assertTrue(calibrations("490401" + bytes + "00").isEmpty())
        assertTrue(calibrations("490401" + bytes.dropLast(2)).isEmpty())
        // Even when the overall byte length looks like two records, a
        // declared count of one must not fall through to count-less parsing.
        assertTrue(calibrations("490401" + bytes + "00".repeat(15)).isEmpty())
    }

    @Test
    fun physicalVinReaderRejectsForbiddenLettersAndNonPrintableIdentityBytes() = runBlocking {
        suspend fun vin(response: String) =
            Obd2FeatureEngine(ObdCommandChannel { command, _ ->
                if (command == "0902") response else "NO DATA"
            }).readVin()
        fun encode(text: String) = "490201" + text.toByteArray(Charsets.US_ASCII)
            .joinToString("") { "%02X".format(java.util.Locale.ROOT, it.toInt() and 0xFF) }
        assertEquals("1G1FA1RXXR0123456", vin(encode("1G1FA1RXXR0123456")))
        for (invalid in listOf("1G1IA1RXXR0123456", "1G1OA1RXXR0123456",
            "1G1QA1RXXR0123456", "1G1FA1RXXR012345" + '\u0001')) {
            assertNull(vin(encode(invalid)))
        }
    }

    @Test
    fun mode09AsciiDecoderRejectsEmbeddedControlBytesRatherThanRemovingThem() = runBlocking {
        val corruptId = "AB" + '\u0001' + "DEFGH12345678"
        val reply = "490401" + corruptId.toByteArray(Charsets.US_ASCII).joinToString("") {
            "%02X".format(java.util.Locale.ROOT, it.toInt() and 0xFF)
        }
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            if (command == "0904") reply else "NO DATA"
        })
        assertTrue(engine.readCalibrationIds().isEmpty())
    }

    @Test
    fun acceptsCountedAndLegacyPaddedDtcRepliesWithoutInventingCodes() = runBlocking {
        suspend fun result(raw: String) =
            Obd2FeatureEngine(ObdCommandChannel { command, _ ->
                if (command == "03") raw else "NO DATA"
            }).readDtcsWithPositiveResponseProof()
        val counted = result("43010300")
        assertEquals("P0300", counted.first.first.single().code)
        assertTrue(counted.second)
        val legacy = result("4301710000")
        assertEquals("P0171", legacy.first.first.single().code)
        assertTrue(legacy.second)
        val clean = result("4300")
        assertTrue(clean.first.first.isEmpty())
        assertTrue(clean.second)
        for (invalid in listOf("43", "4301", "430103", "NO DATA", "7F0311")) {
            val parsed = result(invalid)
            assertTrue(invalid, parsed.first.first.isEmpty())
            assertFalse(invalid, parsed.second)
        }
    }

    @Test
    fun physicalObdScanAndLiveDisplayAgreeOnAbsoluteLoadPid43Scaling() = runBlocking {
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            if (command == "0143") "414301FE" else "NO DATA"
        })
        val values = engine.readLiveData(setOf(0x43))
        assertEquals("200.00", values.single().value)
        assertEquals(200.0, requireNotNull(CamaroUdsProtocol.decodeAbsoluteLoadPercent(
            intArrayOf(1, 254))), 0.001)
        assertEquals("%", values.single().unit)
    }

    @Test
    fun physicalDtcRepliesAreDecodedSeparatelyForMultipleCanResponders() = runBlocking {
        val commands = mutableListOf<String>()
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            commands += command
            when (command) {
                "03" -> "7E8 04 43 01 03 00\r7E9 04 43 01 01 71"
                "07" -> "18DAF110 04 47 01 01 33\r18DAF111 02 47 00"
                "0A" -> "NO DATA"
                else -> "NO DATA"
            }
        })
        val (codes, proved) = engine.readDtcsWithPositiveResponseProof()
        assertTrue(proved)
        assertEquals(setOf("P0300", "P0171"), codes.first.map { it.code }.toSet())
        assertEquals(listOf("P0133"), codes.second.map { it.code })
        assertTrue(codes.third.isEmpty())
        assertEquals(listOf("03", "07", "0A"), commands)
    }

    @Test
    fun malformedInterleavedDtcFramesCannotInventAdditionalCodes() = runBlocking {
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            if (command == "03") {
                "7E8 04 43 01 03 00\n7E9 03 43 02 01\nSEARCHING..."
            } else "NO DATA"
        })
        val (codes, proved) = engine.readDtcsWithPositiveResponseProof()
        assertTrue(proved)
        assertEquals(listOf("P0300"), codes.first.map { it.code })
    }

    @Test
    fun compactCanResponderHeadersCannotBeDecodedAsFaultData() = runBlocking {
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            when (command) {
                "03" -> "7E80443010300\r18DAF1100443010171"
                else -> "NO DATA"
            }
        })
        val (confirmed, _, _) = engine.readDtcs()
        assertEquals(setOf("P0300", "P0171"), confirmed.map { it.code }.toSet())
    }

    @Test
    fun singleDigitCanDlcStillDecodesExactDtcPayloadAndRejectsMismatch() = runBlocking {
        suspend fun confirmed(response: String): List<ObdDtc> =
            Obd2FeatureEngine(ObdCommandChannel { command, _ ->
                if (command == "03") response else "NO DATA"
            }).readDtcs().first
        assertEquals(listOf("P0300"), confirmed("7E8 4 43 01 03 00").map { it.code })
        assertEquals(listOf("P0171"), confirmed("18DAF110 4 43 01 01 71").map { it.code })
        assertTrue(confirmed("7E8 3 43 01 03 00").isEmpty())
        assertTrue(confirmed("18DAF110 5 43 01 01 71").isEmpty())
    }

    @Test
    fun physicalScannerKeepsWidebandVoltageAndCurrentBanksDistinct() = runBlocking {
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            when (command) {
                "0128" -> "412880008000"
                "0134" -> "413480008000"
                "0138" -> "413880008000"
                else -> "NO DATA"
            }
        })
        val values = engine.readLiveData(setOf(0x28, 0x34, 0x38))
        val bankTwoVoltage = values.filter { it.pid == 0x28 }
        assertEquals(setOf("lambda", "V"), bankTwoVoltage.map { it.unit }.toSet())
        assertTrue(bankTwoVoltage.all { it.name.contains("Bank 2 sensor 1") })
        val bankOneCurrent = values.filter { it.pid == 0x34 }
        assertEquals(setOf("lambda", "mA"), bankOneCurrent.map { it.unit }.toSet())
        assertTrue(bankOneCurrent.all { it.name.contains("Bank 1 sensor 1") })
        val bankTwoCurrent = values.filter { it.pid == 0x38 }
        assertEquals(setOf("lambda", "mA"), bankTwoCurrent.map { it.unit }.toSet())
        assertTrue(bankTwoCurrent.all { it.name.contains("Bank 2 sensor 1") })
        assertEquals("0.00", bankTwoCurrent.single { it.unit == "mA" }.value)
    }

    @Test
    fun physicalScannerNeverAcceptsExtraWidebandSensorBytes() = runBlocking {
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            when (command) {
                "0128" -> "41288000800000"
                "0138" -> "4138800080"
                else -> "NO DATA"
            }
        })
        assertTrue(engine.readLiveData(setOf(0x28, 0x38)).isEmpty())
    }

    @Test
    fun functionalAnonymousBitmapUnionDoesNotInventEcuAttribution() = runBlocking {
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            when (command) {
                "0100" -> "410008000000\r410000100000"
                else -> "NO DATA"
            }
        })
        val result = engine.discoverPidAvailability()
        assertEquals(setOf(5, 12), result.supported)
        assertTrue(result.byResponder.isEmpty())
        val primary = engine.discoverPidAvailability(primaryPowertrainResponderOnly = true)
        assertTrue(primary.supported.isEmpty())
        assertEquals(1, primary.firstUnresolvedPid)
    }


    @Test
    fun attributedPidInventoryRejectsAnonymousAndUnadvertisedMeasurements() = runBlocking {
        val engine = Obd2FeatureEngine(ObdCommandChannel { command, _ ->
            when (command) {
                "0100" -> "7E8 06 41 00 00 10 00 00\r7E9 06 41 00 00 00 00 00"
                "010C" -> "410C1AF8"
                else -> "NO DATA"
            }
        })
        val result = engine.discoverPidAvailability()
        assertEquals(setOf(12), result.byResponder["7E8"])
        assertEquals(emptySet<Int>(), result.byResponder["7E9"])
        assertTrue(engine.readLiveData(result.supported, result.byResponder).isEmpty())
    }



}
