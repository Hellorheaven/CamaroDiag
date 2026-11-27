package com.camarodiag.gen5.ui

import android.content.ContentResolver
import android.net.Uri
import com.camarodiag.connectors.J1979LiveNativeReplay
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest

/** Offline inspection of a single explicitly selected interrupted CL/1 file.
 * The result has its own schema; it is not accepted by live CSV or catalogue
 * projection. Read the original twice, and again before publishing. */
internal object J1979NativePrefixFiles {
    data class Prepared(val document: Map<String,Any?>,val bytes: ByteArray,val sha256: String)

    private fun read(resolver: ContentResolver,uri: Uri,current:()->Unit,
                     consume:(ByteArray,Int)->Unit): Int {
        require(uri.scheme=="content") { "SAF document required" }
        current()
        val input=resolver.openInputStream(uri) ?: throw IOException("Capture unavailable")
        var total=0
        input.use { stream ->
            val buffer=ByteArray(65536)
            while(true) {
                current()
                val n=stream.read(buffer)
                if(n<0)break
                if(n==0)throw IOException("Input made no progress")
                require(n<=buffer.size&&n<=J1979LiveNativeReplay.MAX_CAPTURE-total) { "CL/1 input exceeds byte limit" }
                total+=n
                consume(buffer,n)
            }
        }
        require(total>0) { "Empty CL/1 capture" }
        current()
        return total
    }

    fun digest(resolver: ContentResolver,uri: Uri,current:()->Unit): String {
        val sha=MessageDigest.getInstance("SHA-256")
        read(resolver,uri,current) { buffer,n -> sha.update(buffer,0,n) }
        return J1979DocumentedCatalogue.digestText(sha.digest())
    }

    fun prepare(resolver: ContentResolver,uri: Uri,vin: String,current:()->Unit): Prepared {
        require(J1979CatalogueProjection.validVin(vin))
        val raw=ByteArrayOutputStream()
        read(resolver,uri,current) { buffer,n -> raw.write(buffer,0,n) }
        val bytes=raw.toByteArray()
        val hash=J1979DocumentedCatalogue.sha(bytes)
        current()
        val report=J1979LiveNativeReplay.inspectPrefix(bytes,vin).toMutableMap()
        report["capture_sha256"]=hash
        require(report["prefix_integrity_verified"]==true&&report["capture_integrity_verified"]==false&&
            report["execution_completed"]==false&&report["terminal_present"]==false)
        val output=J1979CatalogueJson.encode(report)
        check(digest(resolver,uri,current)==hash) { "CL/1 changed during prefix inspection" }
        current()
        return Prepared(report.toMap(),output,hash)
    }
}
