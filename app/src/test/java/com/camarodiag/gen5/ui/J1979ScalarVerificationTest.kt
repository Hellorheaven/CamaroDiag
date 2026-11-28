package com.camarodiag.gen5.ui

import org.junit.Assert.*
import org.junit.Test

class J1979ScalarVerificationTest {
    private fun parse(s:String)=J1979CatalogueJson.parse(s.toByteArray(),4096)

    @Test fun strictFieldsAndPortableNumericRepresentation() {
        val expected=linkedMapOf("count" to 2,"number" to 1726.0,"physical_vehicle_verified" to false,
            "samples" to listOf(mapOf("pid" to 12,"number" to 1726.0)))
        val reordered="""{"samples":[{"number":1726,"pid":12}],"physical_vehicle_verified":false,"number":1.726e3,"count":2}"""
        assertTrue(J1979ScalarVerification.matches(expected,parse(reordered)))
        for(bad in listOf(reordered.replace("\"count\":2","\"count\":2.0"),
            reordered.replace("\"pid\":12","\"pid\":13"),reordered.replace("1726","1727"),
            reordered.replace("false","true"),reordered.replace("\"count\":2","\"count\":2,\"extra\":false"))) {
            assertFalse(J1979ScalarVerification.matches(expected,parse(bad)))
        }
    }

    @Test fun duplicatesAndNonJsonRejected() {
        for(bad in listOf("""{"count":2,"count":2}""","""{"number":NaN}""","{}x")) {
            assertTrue(runCatching { parse(bad) }.isFailure)
        }
    }
}
