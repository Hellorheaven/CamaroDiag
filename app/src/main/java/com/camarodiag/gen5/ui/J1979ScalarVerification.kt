package com.camarodiag.gen5.ui

import com.camarodiag.connectors.J1979LiveNativeReplay
import com.camarodiag.connectors.J1979LiveScalarProjection
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Portable scalar evidence, always rebuilt from a complete original CL/1
 * receipt. Never trusts a user-supplied numeric value or claims physical proof. */
internal object J1979ScalarVerification {
    const val MAX_REPORT_BYTES=64*1024*1024
    private val continuous=setOf("number","minimum","maximum","mean","last_verified_value")
    data class Verified(val bytes:ByteArray,val captureSha:String,val reportSha:String)

    internal fun matches(expected:Any?,actual:Any?,field:String=""):Boolean {
        if(expected==null||actual==null)return expected==null&&actual==null
        if(field in continuous&&expected is Number&&actual is Number) {
            val a=expected.toDouble();val b=actual.toDouble()
            return a.isFinite()&&b.isFinite()&&abs(a-b)<=max(1e-9,abs(a)*1e-9)
        }
        return when(expected) {
            is Int -> actual is BigInteger&&actual==BigInteger.valueOf(expected.toLong())
            is Long -> actual is BigInteger&&actual==BigInteger.valueOf(expected)
            is BigInteger -> actual is BigInteger&&actual==expected
            is BigDecimal -> actual is BigDecimal&&actual==expected
            is String -> actual is String&&actual==expected
            is Boolean -> actual is Boolean&&actual==expected
            is Map<*,*> -> actual is Map<*,*>&&actual.keys==expected.keys&&expected.all { (k,v) ->
                k is String&&matches(v,actual[k],k)
            }
            is List<*> -> actual is List<*>&&actual.size==expected.size&&
                expected.indices.all { matches(expected[it],actual[it],field) }
            else -> false
        }
    }

    suspend fun verify(capture:ByteArray,report:ByteArray,vin:String):Verified {
        require(capture.isNotEmpty()&&capture.size<=J1979LiveNativeReplay.MAX_CAPTURE)
        require(report.isNotEmpty()&&report.size<=MAX_REPORT_BYTES)
        require(J1979CatalogueProjection.validVin(vin))
        currentCoroutineContext().ensureActive()
        val captureSha=J1979DocumentedCatalogue.sha(capture)
        val reportSha=J1979DocumentedCatalogue.sha(report)
        val replay=J1979LiveNativeReplay.decode(ByteArrayInputStream(capture),null)
        val projected=J1979LiveScalarProjection.project(replay,vin)
        val expected=projected.report().toMutableMap().apply {
            put("capture_sha256",captureSha)
            put("expected_vin_independently_supplied",true)
        }
        val actual=J1979CatalogueJson.parse(report,MAX_REPORT_BYTES)
        require(matches(expected,actual)) { "CL/1 projection differs from independent replay" }
        currentCoroutineContext().ensureActive()
        val proof=linkedMapOf<String,Any?>(
            "schema" to "camarodiag-j1979-cl1-projection-verification-v1",
            "consistent" to true,
            "capture_sha256" to captureSha,
            "projection_sha256" to reportSha,
            "numeric_projection_complete" to projected.numericProjectionComplete,
            "physical_vehicle_verified" to false,
            "authenticated" to false,
            "permits_programming" to false
        )
        return Verified(J1979CatalogueJson.encode(proof),captureSha,reportSha)
    }
}
