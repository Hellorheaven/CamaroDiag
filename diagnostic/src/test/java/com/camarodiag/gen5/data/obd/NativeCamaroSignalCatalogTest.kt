package com.camarodiag.gen5.data.obd

import org.junit.Assert.*
import org.junit.Test

class NativeCamaroSignalCatalogTest {

    @Test
    fun firstPartyCatalogueStartsEmptyRatherThanPromotingCommunityData() {
        val catalog = NativeCamaroSignalCatalog.fromJson(
            """{"schemaVersion":1,"signals":[]}"""
        )

        assertTrue(catalog.signals.isEmpty())
        assertTrue(catalog.executableFor(2012, "LFX").isEmpty())
    }

    @Test
    fun proposedDefinitionCannotBeExecutedEvenWithPhysicalCanIds() {
        val catalog = NativeCamaroSignalCatalog.fromJson(
            """
            {
              "schemaVersion":1,
              "signals":[{
                "id":"CAMARO_SAMPLE",
                "name":"Sample only",
                "category":"Engine",
                "service":"0x22",
                "identifier":"0x1234",
                "requestCanId":"7E0",
                "responseCanId":"7E8",
                "bitLength":8,
                "scale":1.0,
                "unit":"raw",
                "verificationStatus":"PROPOSED",
                "evidenceReferences":["https://example.invalid/spec"],
                "verifiedVehicleConfigurations":[]
              }]
            }
            """.trimIndent()
        )

        val signal = catalog.find("CAMARO_SAMPLE")!!
        assertFalse(signal.isExecutable)
        assertTrue(catalog.executableFor(2012, "LFX").isEmpty())
    }

    @Test
    fun vehicleVerifiedDefinitionMustHaveEvidenceAndValidAddressing() {
        val catalog = NativeCamaroSignalCatalog.fromJson(
            """
            {
              "schemaVersion":1,
              "signals":[{
                "id":"CAMARO_VERIFIED_SAMPLE",
                "name":"Verified test vector",
                "category":"Test",
                "modelYears":[2012],
                "engineCodes":["LFX"],
                "service":"22",
                "identifier":"1234",
                "requestCanId":"7E0",
                "responseCanId":"7E8",
                "bitOffset":4,
                "bitLength":8,
                "scale":0.5,
                "offset":1.0,
                "unit":"unit",
                "verificationStatus":"VEHICLE_VERIFIED",
                "evidenceReferences":["https://example.invalid/test-record"],
                "verifiedVehicleConfigurations":["2012 Camaro LFX test fixture"]
              }]
            }
            """.trimIndent()
        )

        val signal = catalog.executableFor(2012, "lfx").single()
        assertTrue(signal.isExecutable)
        assertEquals(18.5, signal.decode(byteArrayOf(0x12, 0x34)), 0.0)
        assertTrue(catalog.executableFor(2013, "LFX").isEmpty())
        assertTrue(catalog.executableFor(2012, "LLT").isEmpty())
    }

    @Test
    fun malformedDidAndDuplicateIdsAreRejected() {
        val malformed = """
            {"schemaVersion":1,"signals":[{
              "id":"BAD","name":"Bad DID","identifier":"123","bitLength":8
            }]}
        """.trimIndent()
        assertThrows(IllegalArgumentException::class.java) {
            NativeCamaroSignalCatalog.fromJson(malformed)
        }

        val duplicate = """
            {"schemaVersion":1,"signals":[
              {"id":"SAME","name":"One","identifier":"1234","bitLength":8},
              {"id":"same","name":"Two","identifier":"1235","bitLength":8}
            ]}
        """.trimIndent()
        assertThrows(IllegalArgumentException::class.java) {
            NativeCamaroSignalCatalog.fromJson(duplicate)
        }
    }

    @Test
    fun unsupportedSchemaVersionIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            NativeCamaroSignalCatalog.fromJson("""{"schemaVersion":2,"signals":[]}""")
        }
    }
}
