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
