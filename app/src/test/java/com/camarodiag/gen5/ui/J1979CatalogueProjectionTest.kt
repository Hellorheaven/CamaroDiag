package com.camarodiag.gen5.ui

import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.util.Base64

class J1979CatalogueProjectionTest {
    private val vin = "1G1FA1RXXR0123456"
    private val pin = J1979DocumentedCatalogue.sha("synthetic test reference".toByteArray())
    private fun catalogue() = """
        {
          "schema":"camarodiag-j1979-pid-catalogue-v1",
          "edition":"synthetic regression only",
          "reference_name":"synthetic test reference",
          "reference_sha256":"$pin",
          "definitions":[{
            "pid":241,"payload_bytes":3,"protocols":["CAN","LEGACY"],"reference":"fixture",
            "signals":[{
              "name":"signed sample","unit":"fixture unit","bits":{"offset":8,"width":16,"order":"LE_BYTES"},
              "signed":true,"scale":[1,3],"offset":[1,2],"reference":"fixture section 1",
              "presence":{"bits":{"offset":0,"width":8,"order":"MSB0"},"mask":128,"equals":128},
              "unavailable":[65535],"range":[[-100,1],[100,1]]
            },{
              "name":"state","unit":"state","bits":{"offset":6,"width":2,"order":"MSB0"},
              "signed":false,"scale":[1,1],"offset":[0,1],"reference":"fixture section 2",
              "enumeration":[{"raw":1,"label":"fixture state"}]
            }]
          },{
            "pid":243,"payload_bytes":8,"protocols":["CAN"],"reference":"fixture unsigned 64-bit",
            "signals":[{
              "name":"counter","unit":"fixture unit","bits":{"offset":0,"width":64,"order":"MSB0"},
              "signed":false,"scale":[1,1],"offset":[0,1],"reference":"fixture counter"
            }]
          }]
        }
    """.trimIndent().toByteArray()

    private fun hex(n: Int) = n.toString(16).uppercase().padStart(2, '0')
    private fun vinHex() = "490201" + vin.toByteArray(Charsets.US_ASCII).joinToString("") { hex(it.toInt() and 255) }
    private fun source(zeroDuration: Boolean = false, legacy: Boolean = false): MutableMap<String, Any?> {
        var now = 0
        val admission = mutableListOf<MutableMap<String, Any?>>()
        val observations = mutableListOf<MutableMap<String, Any?>>()
        val pids = if (legacy) listOf(241, 242) else listOf(241, 242, 243)
        fun row(phase: String, request: String, response: String?, status: String,
                cycle: Int? = null, pid: Int? = null): MutableMap<String, Any?> {
            val start = now
            if (!zeroDuration) now += 10
            return linkedMapOf(
                "phase" to phase, "cycle" to cycle, "pid" to pid, "requestHex" to request,
                "responseHex" to response, "startedElapsedMs" to start, "elapsedMs" to now,
                "durationMs" to now - start, "status" to status,
                "detail" to if (status == "RAW") "RAW_UNINTERPRETED" else null,
                "measurement" to null, "multiSignals" to null
            )
        }
        admission += row("INITIAL_VIN", "0902", vinHex(), "PASSED")
        for (base in 0..224 step 32) {
            val code = hex(base)
            val bits = if (base != 224) "00000001" else if (legacy) "0000C000" else "0000E000"
            admission += row("INVENTORY", "01$code", "41$code$bits", "PASSED")
        }
        val responses = listOf("41F181FEFF", "41F1010000", "41F180FFFF", "41F18000")
        var lastStart = now
        for (cycle in 1..4) {
            if (cycle > 1) now = maxOf(now, lastStart + 100)
            lastStart = now
            for (pid in pids) {
                val response = when (pid) {
                    241 -> responses[cycle - 1]
                    242 -> "41F200"
                    else -> "41F3FFFFFFFFFFFFFFFF"
                }
                observations += row("SAMPLE", "01" + hex(pid), response, "RAW", cycle, pid)
            }
        }
        admission += row("FINAL_VIN", "0902", vinHex(), "PASSED")
        val root = linkedMapOf<String, Any?>(
            "schema" to if (legacy) "camarodiag-j1979-legacy-live-v1" else "camarodiag-j1979-live-v1",
            "declaredVin" to vin, "observedVin" to vin, "rawOnly" to true,
            "selectedPids" to pids, "advertisedPids" to pids, "inventoryComplete" to true,
            "requestedCycles" to 4, "completedCycles" to 4, "intervalMs" to 100,
            "budgetMs" to 30_000, "durationMs" to now, "admission" to admission,
            "observations" to observations, "completed" to true, "stopReason" to null,
            "physicalVehicleVerified" to false, "wholeVehicleCoverageVerified" to false
        )
        if (legacy) {
            root["expectedProtocol"] = 2; root["selectedResponseSource"] = "10"
            root["physicallyAddressed"] = false
        } else {
            root["txCanId"] = "7E0"; root["rxCanId"] = "7E8"
        }
        return root
    }
    @Suppress("UNCHECKED_CAST")
    private fun rows(root: MutableMap<String, Any?>, key: String): MutableList<MutableMap<String, Any?>> =
        root.getValue(key) as MutableList<MutableMap<String, Any?>>
    private fun bytes(source: Map<String, Any?>) = J1979CatalogueJson.encode(source)
    private fun reject(block: () -> Unit) {
        assertTrue("Expected rejection", runCatching(block).isFailure)
    }
    private fun projected(result: J1979CatalogueProjection.Result): List<Any?> =
        J1979CatalogueJson.array(result.document["rows"], 1, 100)
    private fun signal(row: Any?, index: Int) =
        J1979CatalogueJson.obj(J1979CatalogueJson.array(J1979CatalogueJson.obj(row)["signals"], 1, 64)[index])

    @Test fun rawProjectionRetainsUnknownValuesAndExactRationals() {
        val source = source()
        val raw = bytes(source)
        val result = J1979CatalogueProjection.build(catalogue(), pin, raw, vin)
        val rows = projected(result)
        assertEquals(12, rows.size)
        assertEquals(mapOf("numerator" to "-1", "denominator" to "6"), signal(rows[0], 0)["value"])
        assertEquals("fixture state", signal(rows[0], 1)["enumeration"])
        assertEquals("NO_DOCUMENTED_DEFINITION", J1979CatalogueJson.obj(rows[1])["projection_status"])
        assertEquals(mapOf("numerator" to "18446744073709551615", "denominator" to "1"), signal(rows[2], 0)["value"])
        assertEquals("NOT_PRESENT", signal(rows[3], 0)["status"])
        assertEquals("UNAVAILABLE", signal(rows[6], 0)["status"])
        assertEquals("DOCUMENTED_WIDTH_MISMATCH", J1979CatalogueJson.obj(rows[9])["projection_status"])
        assertEquals(false, result.document["source_replay_verified"])
        assertEquals(true, result.document["source_application_replay_verified"])
        assertEquals(false, result.document["physical_vehicle_verified"])
        assertEquals(false, result.document["permits_programming"])
        assertEquals(J1979DocumentedCatalogue.sha(raw), result.reportSha)
    }

    @Test fun wrappersAndCoarseClockAreHandledWithoutReorderingTheCapture() {
        val direct = source(true)
        assertTrue(J1979CatalogueProjection.replay(bytes(direct), vin).completed)
        val wrapped = mapOf("schema" to "functional-test-export", "live" to direct)
        assertTrue(J1979CatalogueProjection.replay(bytes(wrapped), vin).completed)
        reject { J1979CatalogueProjection.replay(bytes(wrapped + ("liveReport" to direct)), vin) }
        val desktop = source()
        val result = desktop.toMutableMap()
        result.remove("schema")
        result["selectedPids"] = Base64.getEncoder().encodeToString(byteArrayOf(0xF1.toByte(), 0xF2.toByte(), 0xF3.toByte()))
        val wrapper = mapOf("schema" to "camarodiag-j1979-live-v1", "txCanId" to "7E0", "rxCanId" to "7E8",
            "completed" to false, "result" to result)
        val replayed = J1979CatalogueProjection.replay(bytes(wrapper), vin)
        assertFalse(replayed.completed)
        assertTrue(replayed.finalIdentityVerified)
        result["selectedPids"] = "8fLz="
        reject { J1979CatalogueProjection.replay(bytes(wrapper), vin) }
    }

    @Test fun legacyScopeIsExplicitAndWidePayloadsAreRejected() {
        val legacy = source(legacy = true)
        val result = J1979CatalogueProjection.build(catalogue(), pin, bytes(legacy), vin)
        assertEquals(mapOf("kind" to "legacy", "protocol" to 2, "source" to 16), result.document["channel"])
        assertEquals(8, projected(result).size)
        val wide = source(legacy = true)
        rows(wide, "observations")[0]["responseHex"] = "41F1000000000000"
        reject { J1979CatalogueProjection.replay(bytes(wide), vin) }
        legacy["physicallyAddressed"] = true
        reject { J1979CatalogueProjection.replay(bytes(legacy), vin) }
    }

    @Test fun rawApplicationAdmissionAndProvenanceAreNotEditableClaims() {
        val mutations: List<(MutableMap<String, Any?>) -> Unit> = listOf(
            { it["rxCanId"] = "7E9" },
            { it["rawOnly"] = false },
            { it["raw_only"] = true },
            { it["observedVin"] = "1FA6P8TH0L5100000" },
            { it["physicalVehicleVerified"] = true },
            { rows(it, "observations")[0]["responseHex"] = "41F281FEFF" },
            { rows(it, "observations")[0]["measurement"] = emptyMap<String, Any>() },
            { rows(it, "observations").reverse() },
            { rows(it, "admission").removeAt(1) },
            { rows(it, "admission").removeAt(9) },
            { it["advertisedPids"] = listOf(241, 242) },
            { it["inventoryComplete"] = false }
        )
        for (change in mutations) {
            val value = source()
            change(value)
            reject { J1979CatalogueProjection.replay(bytes(value), vin) }
        }
        reject { J1979CatalogueProjection.replay(bytes(source()), "1FA6P8TH0L5100000") }
    }

    // Synthetic fixture with three selected PIDs across all eight support
    // pages. The same wire bytes are used by the Windows projection test.
    private fun nativeCapture(): ByteArray {
        val wire = "434c010100004300e0070000e807000000314731464131525858523031323334353603f1f2f3000000000000000000000000" +
            "000000000000000000000000000000000001006400e80300006f021499434c010201002e0001010100000000000000000005" +
            "0000006400140005000000090249020131473146413152585852303132333435367d8eac1e434c0102020020000201010000" +
            "000000050000000a00000064000600050000000100410000000001284d533a434c01020300200002010100000000000a0000" +
            "000f00000064000600050000000120412000000001f354c069434c01020400200002010100000000000f0000001400000064" +
            "0006000500000001404140000000011787631d434c0102050020000201010000000000140000001900000064000600050000" +
            "0001604160000000011ca946c5434c0102060020000201010000000000190000001e00000064000600050000000180418000" +
            "0000017fd2b851434c01020700200002010100000000001e00000023000000640006000500000001a041a00000000186dac7" +
            "b7434c01020800200002010100000000002300000028000000640006000500000001c041c00000000190aeb200434c010209" +
            "0020000201010000000000280000002d000000640006000500000001e041e00000e0006eea5e13434c01020a001f00040201" +
            "000100f1002d00000032000000640005000500000001f141f181feff01c3c00d434c01020b001d00040201000100f2003200" +
            "000037000000640003000500000001f241f20001dd414e434c01020c002400040201000100f300370000003c00000064000a" +
            "000500000001f341f3fffffffffffffffff5ce1095434c01020d002e0005010100000000003c000000410000006400140005" +
            "000000090249020131473146413152585852303132333435360667a450434c01030e008000000f01000d0000000300000003" +
            "0000000000000000000000410000006a0000000000000000000000000000000000000000000000000000000000000000000e" +
            "0001000100010000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000009e60ab59"
        return wire.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    @Test fun nativeCaptureProjectionAndCsvAreDerivedFromOriginalBytes() {
        val raw=nativeCapture()
        val report=J1979CatalogueProjection.build(catalogue(),pin,raw,vin)
        assertEquals(J1979CatalogueProjection.NATIVE_SCHEMA,report.document["schema"])
        assertEquals(true,report.document["source_replay_verified"])
        assertEquals(false,report.document["transport_frames_replayed"])
        assertEquals(J1979DocumentedCatalogue.sha(raw),report.document["source_capture_sha256"])
        val projected=projected(report)
        assertEquals(3,projected.size)
        assertEquals(mapOf("numerator" to "-1","denominator" to "6"),signal(projected[0],0)["value"])
        assertEquals("NO_DOCUMENTED_DEFINITION",J1979CatalogueJson.obj(projected[1])["projection_status"])
        assertEquals(mapOf("numerator" to "18446744073709551615","denominator" to "1"),
            signal(projected[2],0)["value"])
        val replay=com.camarodiag.connectors.J1979LiveNativeReplay.decode(java.io.ByteArrayInputStream(raw),null)
        val csv=J1979NativeLiveCsv.encode(replay).toString(Charsets.US_ASCII)
        assertTrue(csv.contains("1,F1,50,RAW,41F181FEFF,7E0,7E8,$vin,true"))
        assertEquals(4,csv.lineSequence().filter { it.isNotEmpty() }.count())
        reject { J1979CatalogueProjection.build(catalogue(),pin,raw.copyOf(raw.size-1),vin) }
        reject { J1979CatalogueProjection.build(catalogue(),pin,raw,"1FA6P8TH0L5100000") }
    }

    /** Rewrite a synthetic CF/1 exchange and its terminal verdict with
     * valid CRCs: this proves the replay checks semantics, not only framing. */
    private fun alteredFreezeCapture(original: ByteArray, changeTrigger: Boolean): ByteArray {
        val bytes = original.copyOf()
        var offset = 0
        var changed = false
        var terminal = false
        fun writeCrc(start: Int, bodyLength: Int) {
            val crc = java.util.zip.CRC32().apply { update(bytes, start, 8 + bodyLength) }.value
            for (index in 0..3)
                bytes[start + 8 + bodyLength + index] = ((crc ushr (8 * index)) and 255).toByte()
        }
        while (offset < bytes.size) {
            require(offset + 12 <= bytes.size && bytes[offset] == 'C'.code.toByte() &&
                bytes[offset+1] == 'F'.code.toByte() && bytes[offset+2] == 1.toByte())
            val kind = bytes[offset + 3].toInt() and 255
            val sequence = (bytes[offset + 4].toInt() and 255) +
                ((bytes[offset + 5].toInt() and 255) shl 8)
            val length = (bytes[offset + 6].toInt() and 255) +
                ((bytes[offset + 7].toInt() and 255) shl 8)
            val body = offset + 8
            require(length > 0 && body + length + 4 <= bytes.size)
            if (kind == 2 && sequence == if (changeTrigger) 6 else 12) {
                val requestSize = bytes[body + 2].toInt() and 255
                val response = body + 10 + requestSize
                if (changeTrigger) {
                    require(requestSize == 3 && bytes[response] == 0x42.toByte() &&
                        bytes[response+1] == 2.toByte() && bytes[response+2] == 0.toByte())
                    bytes[response + 4] = 2.toByte()
                } else {
                    require(requestSize == 2 && bytes[response] == 0x49.toByte() &&
                        bytes[response+1] == 2.toByte())
                    bytes[response + 3] = '2'.code.toByte()
                }
                writeCrc(offset, length)
                changed = true
            } else if (kind == 3) {
                require(changed && length == 20)
                if (changeTrigger) {
                    bytes[body + 2] = 3.toByte()  // completed + identity, no stable-frame claim
                    bytes[body + 12] = 4.toByte() // frame 00 changed trigger
                } else {
                    bytes[body] = 7.toByte()      // final VIN mismatch
                    bytes[body + 2] = 0.toByte()  // cannot claim completion or identity
                }
                writeCrc(offset, length)
                terminal = true
            }
            offset = body + length + 4
        }
        require(changed && terminal && offset == bytes.size)
        return bytes
    }

    @Test fun freezeCatalogueUsesFrameSpecificDefinitionsAndOriginalCapture() {
        kotlinx.coroutines.runBlocking {
            val freezeVin = "1G1FA1E30D1234567"
            val wire = "4346010100006800e0070000e80700000060ea00003147314641314533304431323334353637020001000000000000020c0d" +
            "0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
            "0000000000000000000000001be52aa643460102010020000101020005000000140009024902013147314641314533304431" +
            "3233343536370fee793343460102020014000101030005000000070002000042000040180000a518bfa24346010203001200" +
            "0101030005000000050002020042020003014bdf4814434601020400120001010300050000000500020c00420c001f403ec1" +
            "dd26434601020500110001010300050000000400020d00420d0050b116910c43460102060012000101030005000000050002" +
            "020042020003018b28c15f43460102070014000101030005000000070002000142000140180000f3ca56bb43460102080012" +
            "0001010300050000000500020201420201030199eded5e434601020900120001010300050000000500020c01420c011f406c" +
            "a9891e434601020a00110001010300050000000400020d01420d01506e81ff64434601020b00120001010300050000000500" +
            "0202014202010301d9409567434601020c002000010102000500000014000902490201314731464131453330443132333435" +
            "363723d3f9ee434601030d001400000207000c0000003c0000000202000000000000937d821a"
            val capture = wire.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            // Same CF/1 bytes as the Windows fixture. The prefix path
            // explicitly refuses an intact terminal, even for a good capture.
            val withoutTerminal=capture.copyOfRange(0,capture.size-32)
            val partial=com.camarodiag.gen5.data.obd.J1979FreezePrefix.inspect(withoutTerminal,freezeVin)
            assertEquals("camarodiag-j1979-native-freeze-prefix-v1",partial["schema"])
            assertEquals(true,partial["prefix_integrity_verified"])
            assertEquals(false,partial["capture_integrity_verified"])
            assertEquals(false,partial["execution_completed"])
            assertEquals(false,partial["terminal_present"])
            assertEquals(false,partial["physical_vehicle_verified"])
            assertEquals(false,partial["permits_programming"])
            assertEquals("MISSING_TERMINAL",partial["interruption"])
            val pinnedReport=partial.toMutableMap()
            pinnedReport["capture_sha256"]=J1979DocumentedCatalogue.sha(withoutTerminal)
            val encodedPrefix=J1979CatalogueJson.encode(pinnedReport)
            val verified=J1979FreezePrefixVerification.verify(withoutTerminal,encodedPrefix,freezeVin)
            assertEquals(J1979FreezePrefixVerification.SCHEMA,verified.document["schema"])
            assertEquals(true,verified.document["consistent"])
            assertEquals(withoutTerminal.size,verified.document["last_complete_offset"])
            assertEquals(0,verified.document["discarded_tail_bytes"])
            assertEquals(false,verified.document["capture_integrity_verified"])
            assertEquals(false,verified.document["execution_completed"])
            assertEquals(false,verified.document["permits_programming"])
            assertEquals(J1979DocumentedCatalogue.sha(withoutTerminal),verified.captureSha)
            assertEquals(J1979DocumentedCatalogue.sha(encodedPrefix),verified.reportSha)
            val promoted=pinnedReport.toMutableMap()
            promoted["execution_completed"]=true
            assertTrue(runCatching { J1979FreezePrefixVerification.verify(withoutTerminal,
                J1979CatalogueJson.encode(promoted),freezeVin) }.isFailure)
            val unbound=pinnedReport.toMutableMap()
            unbound["capture_sha256"]="0".repeat(64)
            assertTrue(runCatching { J1979FreezePrefixVerification.verify(withoutTerminal,
                J1979CatalogueJson.encode(unbound),freezeVin) }.isFailure)
            val otherRaw=withoutTerminal.copyOfRange(0,withoutTerminal.size-1)
            assertTrue(runCatching { J1979FreezePrefixVerification.verify(otherRaw,
                encodedPrefix,freezeVin) }.isFailure)
            assertTrue(runCatching { J1979FreezePrefixVerification.verify(withoutTerminal,
                encodedPrefix,"1G1FA1E30D7654321") }.isFailure)

            assertEquals(0,partial["discarded_tail_bytes"])
            assertEquals(withoutTerminal.size,partial["last_complete_offset"])
            assertEquals(freezeVin,partial["observed_final_vin_in_prefix"])
            val frameProgress=J1979CatalogueJson.array(partial["observed_frame_progress"],2,2)
            assertEquals(listOf(0,1),frameProgress.map { J1979CatalogueJson.obj(it)["frame"] })
            frameProgress.forEach { assertEquals(false,J1979CatalogueJson.obj(it)["status_finalized"]) }
            val firstProgress=J1979CatalogueJson.obj(frameProgress[0])
            assertEquals("COMPLETE",firstProgress["provisional_status"])
            assertEquals(listOf(12,13),firstProgress["advertised_measurement_pids"])
            assertEquals(listOf(12,13),firstProgress["read_measurement_pids"])
            assertEquals(2,firstProgress["measurement_observation_count"])
            assertEquals("P0301",firstProgress["trigger_dtc_in_prefix"])
            assertEquals(true,firstProgress["trigger_stable_in_prefix"])
            val rowsPartial=J1979CatalogueJson.array(partial["independently_replayed_steps"],1,100)
            assertEquals(12,rowsPartial.size)
            assertEquals(12,partial["complete_observations"])
            assertEquals(13,partial["complete_records"])
            val exported=J1979CatalogueJson.encode(partial)
            assertEquals(false,J1979CatalogueJson.obj(
                J1979CatalogueJson.parse(exported,1024*1024))["execution_completed"])
            val truncated=com.camarodiag.gen5.data.obd.J1979FreezePrefix.inspect(capture.copyOf(capture.size-1),freezeVin)
            assertEquals("TRUNCATED_RECORD",truncated["interruption"])
            assertEquals(false,truncated["terminal_present"])
            assertEquals(31,truncated["discarded_tail_bytes"])
            val planOnly=capture.copyOfRange(0,116)
            val planPartial=com.camarodiag.gen5.data.obd.J1979FreezePrefix.inspect(planOnly,freezeVin)
            assertEquals(0,planPartial["complete_observations"])
            assertEquals(true,planPartial["replay_terminated_by_missing_observation"])
            assertTrue(runCatching {
                com.camarodiag.gen5.data.obd.J1979FreezePrefix.inspect(capture,freezeVin)
            }.isFailure)
            assertTrue(runCatching {
                com.camarodiag.gen5.data.obd.J1979FreezePrefix.inspect(withoutTerminal,vin)
            }.isFailure)
            val corruptedPrefix=withoutTerminal.copyOf()
            corruptedPrefix[corruptedPrefix.size-8]=(corruptedPrefix[corruptedPrefix.size-8].toInt() xor 1).toByte()
            assertTrue(runCatching {
                com.camarodiag.gen5.data.obd.J1979FreezePrefix.inspect(corruptedPrefix,freezeVin)
            }.isFailure)
            val definition = """{"schema":"camarodiag-j1979-freeze-catalogue-v1","edition":"synthetic","reference_name":"synthetic","reference_sha256":"PIN","frames":[{"frame":0,"definitions":[{"pid":12,"payload_bytes":2,"protocols":["CAN"],"reference":"synthetic","signals":[{"name":"sample","unit":"fixture","bits":{"offset":0,"width":16,"order":"MSB0"},"signed":false,"scale":[1,2],"offset":[0,1],"reference":"test only"}]},{"pid":13,"payload_bytes":1,"protocols":["CAN"],"reference":"synthetic","signals":[{"name":"sample","unit":"fixture","bits":{"offset":0,"width":8,"order":"MSB0"},"signed":false,"scale":[1,2],"offset":[0,1],"reference":"test only"}]}]},{"frame":1,"definitions":[{"pid":12,"payload_bytes":2,"protocols":["CAN"],"reference":"synthetic","signals":[{"name":"sample","unit":"fixture","bits":{"offset":0,"width":16,"order":"MSB0"},"signed":false,"scale":[1,2],"offset":[100,1],"reference":"test only"}]}]}]}""".replace("PIN",pin)
            val result = J1979FreezeCatalogue.build(definition.toByteArray(),pin,capture,freezeVin)
            assertEquals(J1979FreezeCatalogue.PROJECTION,result.document["schema"])
            assertEquals(true,result.document["final_identity_verified"])
            assertEquals(false,result.document["permits_programming"])
            assertEquals(false,result.document["physical_vehicle_verified"])
            assertEquals(J1979DocumentedCatalogue.sha(capture),result.document["source_capture_sha256"])
            val entries = projected(result)
            assertEquals(4,entries.size)
            assertEquals(mapOf("numerator" to "4000","denominator" to "1"),signal(entries[0],0)["value"])
            assertEquals(mapOf("numerator" to "4100","denominator" to "1"),signal(entries[2],0)["value"])
            assertEquals("NO_DOCUMENTED_DEFINITION",J1979CatalogueJson.obj(entries[3])["projection_status"])
            val audit = J1979CatalogueJson.obj(result.document["catalogue_coverage"])
            assertEquals("EXPLICIT_FREEZE_FRAMES_ONLY",audit["scope"])
            assertEquals(false,audit["normative_compliance_verified"])
            assertEquals(false,audit["hardware_provenance_verified"])
            val audited = J1979CatalogueJson.array(audit["frames"],2,2)
            val zero = J1979CatalogueJson.obj(audited[0])
            val one = J1979CatalogueJson.obj(audited[1])
            assertEquals(listOf(12,13),zero["advertised_measurement_pids"])
            assertEquals(listOf(12,13),zero["documented_pids"])
            assertEquals(emptyList<Int>(),zero["advertised_without_documentation"])
            assertEquals(listOf(12),one["documented_pids"])
            assertEquals(listOf(12,13),one["responded_measurement_pids"])
            assertEquals(listOf(13),one["advertised_without_documentation"])
            assertEquals(listOf(12),one["projected_measurement_pids"])
            assertEquals(emptyList<Int>(),one["documented_without_advertisement"])
            val triggerChanged = J1979FreezeCatalogue.build(definition.toByteArray(),pin,
                alteredFreezeCapture(capture,true),freezeVin)
            assertEquals(true,triggerChanged.document["final_identity_verified"])
            val triggerFrames = J1979CatalogueJson.array(triggerChanged.document["frames"],2,2)
            assertEquals(false,J1979CatalogueJson.obj(triggerFrames[0])["trigger_stable"])
            assertEquals("CHANGED",J1979CatalogueJson.obj(triggerFrames[0])["source_status"])
            val triggerRows = projected(triggerChanged)
            assertTrue(triggerRows.filter { J1979CatalogueJson.obj(it)["frame"] == 0 }
                .all { J1979CatalogueJson.obj(it)["projection_status"] == "TRIGGER_NOT_STABLE" })
            assertTrue(triggerRows.filter { J1979CatalogueJson.obj(it)["frame"] == 1 }
                .any { J1979CatalogueJson.obj(it)["projection_status"] == "PROJECTED" })

            val identityChanged = J1979FreezeCatalogue.build(definition.toByteArray(),pin,
                alteredFreezeCapture(capture,false),freezeVin)
            assertEquals(false,identityChanged.document["final_identity_verified"])
            assertEquals(false,identityChanged.document["source_completed"])
            assertTrue(projected(identityChanged).all {
                J1979CatalogueJson.obj(it)["projection_status"] == "IDENTITY_NOT_RECHECKED"
            })
            assertTrue(projected(identityChanged).all {
                J1979CatalogueJson.array(J1979CatalogueJson.obj(it)["signals"],0,128).isEmpty()
            })
            assertTrue(runCatching { J1979FreezeCatalogue.build(definition.toByteArray(),pin,capture.copyOf(capture.size-1),freezeVin) }.isFailure)
            assertTrue(runCatching { J1979FreezeCatalogue.build(definition.toByteArray(),pin,capture,vin) }.isFailure)
            assertTrue(runCatching { J1979FreezeCatalogue.parse(catalogue(),pin) }.isFailure)
            assertTrue(runCatching { J1979FreezeCatalogue.parse(definition.toByteArray(),"b".repeat(64)) }.isFailure)
        }
    }

    @Test fun strictJsonAndCataloguePreventAmbiguousOrExecutableDefinitions() {
        val raw = catalogue().toString(Charsets.UTF_8)
        for (value in listOf(
            raw.replace("\"pid\":241", "\"pid\":241,\"pid\":242"),
            raw.replace("\"width\":16", "\"width\":65"),
            raw.replace("\"scale\":[1,3]", "\"scale\":[1,0]"),
            raw.replace("\"payload_bytes\":3", "\"payload_bytes\":6"),
            raw.replace("\"offset\":8", "\"offset\":9"),
            raw.replace("\"equals\":128", "\"equals\":129"),
            raw.replace("\"pid\":241", "\"pid\":32"),
            raw.replace("\"pid\":241", "\"pid\":241,\"formula\":\"eval(data)\"")
        )) reject { J1979DocumentedCatalogue.parse(value.toByteArray(), pin) }
        reject { J1979DocumentedCatalogue.parse(catalogue(), "b".repeat(64)) }
        for (invalid in listOf("{\"a\":1,\"a\":2}", "[01]", "[1,]", "{\"a\":NaN}", "true false",
            "\"\\uD800\"", "\"\\uZZZZ\"", "\"\\x20\""))
            reject { J1979CatalogueJson.parse(invalid.toByteArray(), 1024) }
        val exact = J1979CatalogueJson.parse("18446744073709551615".toByteArray(), 1024)
        assertEquals(BigInteger("18446744073709551615"), exact)
        val unicode = mapOf("text" to "État \n \"\\ 😀", "missing" to null)
        assertEquals(unicode, J1979CatalogueJson.parse(J1979CatalogueJson.encode(unicode), 1024))
    }
}
