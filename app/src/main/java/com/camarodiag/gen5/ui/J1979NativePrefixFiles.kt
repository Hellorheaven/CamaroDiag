package com.camarodiag.gen5.ui

import android.content.ContentResolver
import android.net.Uri
import com.camarodiag.connectors.J1979LiveNativeReplay
import com.camarodiag.connectors.J1979MaintenanceJournal
import com.camarodiag.connectors.J1979FreezeUsbCapture
import com.camarodiag.gen5.data.obd.J1979FreezePrefix
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest

/** Offline inspection of a single explicitly selected interrupted CL/1 file.
 * The result has its own schema; it is not accepted by live CSV or catalogue
 * projection. Read the original twice, and again before publishing. */
internal object J1979NativePrefixFiles {
    data class Prepared(val document: Map<String,Any?>,val bytes: ByteArray,val sha256: String)

    private fun read(resolver: ContentResolver,uri: Uri,current:()->Unit,max: Int,
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
                require(n<=buffer.size&&n<=max-total) { "CL/1 input exceeds byte limit" }
                total+=n
                consume(buffer,n)
            }
        }
        require(total>0) { "Empty CL/1 capture" }
        current()
        return total
    }

    fun digest(resolver: ContentResolver,uri: Uri,current:()->Unit,max: Int=J1979LiveNativeReplay.MAX_CAPTURE): String {
        val sha=MessageDigest.getInstance("SHA-256")
        read(resolver,uri,current,max) { buffer,n -> sha.update(buffer,0,n) }
        return J1979DocumentedCatalogue.digestText(sha.digest())
    }

    fun prepareMaintenance(resolver: ContentResolver,uri: Uri,vin: String,current:()->Unit,prefix:Boolean=false): Prepared {
        require(J1979CatalogueProjection.validVin(vin))
        val raw=ByteArrayOutputStream()
        read(resolver,uri,current,J1979MaintenanceJournal.MAX_JOURNAL) { buffer,n -> raw.write(buffer,0,n) }
        val bytes=raw.toByteArray()
        val hash=J1979DocumentedCatalogue.sha(bytes)
        current()
        val report=(if(prefix) J1979MaintenanceJournal.inspectPrefix(bytes,vin)
            else J1979MaintenanceJournal.inspect(bytes,vin)).toMutableMap()
        report["journal_sha256"]=hash
        require((if(prefix) report["prefix_integrity_verified"]==true&&report["record_consistency_verified"]==false&&
            report["terminal_present"]==false&&report["recovery_required"]==true else report["record_consistency_verified"]==true)&&
            report["permits_automatic_retry"]==false&&
            report["physical_vehicle_verified"]==false&&report["permits_programming"]==false)
        val output=J1979CatalogueJson.encode(report)
        check(digest(resolver,uri,current,J1979MaintenanceJournal.MAX_JOURNAL)==hash) { "CM/1 changed during inspection" }
        current()
        return Prepared(report.toMap(),output,hash)
    }

    /** Keep the independently selected raw evidence and prior JSON report
     * as separate immutable inputs. File providers can replace either URI
     * between reads; both are hashed and reread before publishing proof. */
    data class FreezeVerificationPrepared(
        val bytes: ByteArray,
        val document: Map<String,Any?>,
        val captureSha: String,
        val reportSha: String
    )

    suspend fun prepareScalarVerification(resolver:ContentResolver,capture:Uri,report:Uri,
                                          vin:String,current:()->Unit):J1979ScalarVerification.Verified {
        require(capture!=report&&capture.scheme=="content"&&report.scheme=="content")
        val original=ByteArrayOutputStream()
        val candidate=ByteArrayOutputStream()
        read(resolver,capture,current,J1979LiveNativeReplay.MAX_CAPTURE) { b,n -> original.write(b,0,n) }
        read(resolver,report,current,J1979ScalarVerification.MAX_REPORT_BYTES) { b,n -> candidate.write(b,0,n) }
        val checked=J1979ScalarVerification.verify(original.toByteArray(),candidate.toByteArray(),vin)
        revalidateScalarVerification(resolver,capture,report,checked.captureSha,checked.reportSha,current)
        return checked
    }

    fun revalidateScalarVerification(resolver:ContentResolver,capture:Uri,report:Uri,
                                     captureSha:String,reportSha:String,current:()->Unit) {
        require(capture!=report)
        check(digest(resolver,capture,current)==captureSha) { "CL/1 source changed" }
        check(digest(resolver,report,current,J1979ScalarVerification.MAX_REPORT_BYTES)==reportSha) {
            "CL/1 scalar report changed"
        }
        current()
    }

    suspend fun prepareFreezeVerification(resolver: ContentResolver,capture: Uri,report: Uri,
                                          vin: String,current:()->Unit): FreezeVerificationPrepared {
        require(capture != report && capture.scheme=="content" && report.scheme=="content") {
            "Select distinct CF/1 capture and JSON evidence"
        }
        require(J1979CatalogueProjection.validVin(vin))
        val captured=ByteArrayOutputStream()
        val saved=ByteArrayOutputStream()
        val maxCapture=J1979FreezeUsbCapture.MAX_CAPTURE.toInt()
        read(resolver,capture,current,maxCapture) { block,n -> captured.write(block,0,n) }
        read(resolver,report,current,J1979FreezePrefixVerification.MAX_REPORT_BYTES) {
            block,n -> saved.write(block,0,n)
        }
        current()
        val verified=J1979FreezePrefixVerification.verify(captured.toByteArray(),
            saved.toByteArray(),vin)
        revalidateFreezeVerification(resolver,capture,report,verified.captureSha,verified.reportSha,current)
        return FreezeVerificationPrepared(verified.bytes,verified.document,
            verified.captureSha,verified.reportSha)
    }

    fun revalidateFreezeVerification(resolver: ContentResolver,capture: Uri,report: Uri,
                                     captureSha: String,reportSha: String,current:()->Unit) {
        require(capture!=report && capture.scheme=="content" && report.scheme=="content")
        check(digest(resolver,capture,current,J1979FreezeUsbCapture.MAX_CAPTURE.toInt())==captureSha) {
            "CF/1 source changed before publication"
        }
        check(digest(resolver,report,current,J1979FreezePrefixVerification.MAX_REPORT_BYTES)==reportSha) {
            "CF/1 report changed before publication"
        }
        current()
    }

    suspend fun prepareFreeze(resolver: ContentResolver,uri: Uri,vin: String,
                              current:()->Unit): Prepared {
        require(J1979CatalogueProjection.validVin(vin))
        val max=J1979FreezeUsbCapture.MAX_CAPTURE.toInt()
        val source=ByteArrayOutputStream()
        read(resolver,uri,current,max) { bytes,n -> source.write(bytes,0,n) }
        val raw=source.toByteArray()
        val hash=J1979DocumentedCatalogue.sha(raw)
        current()
        val report=J1979FreezePrefix.inspect(raw,vin).toMutableMap()
        report["capture_sha256"]=hash
        require(report["prefix_integrity_verified"]==true&&
            report["capture_integrity_verified"]==false&&
            report["terminal_present"]==false&&report["execution_completed"]==false&&
            report["physical_vehicle_verified"]==false&&report["permits_programming"]==false)
        val bytes=J1979CatalogueJson.encode(report)
        check(digest(resolver,uri,current,max)==hash) { "CF/1 source changed during inspection" }
        current()
        return Prepared(report.toMap(),bytes,hash)
    }

    fun prepare(resolver: ContentResolver,uri: Uri,vin: String,current:()->Unit): Prepared {
        require(J1979CatalogueProjection.validVin(vin))
        val raw=ByteArrayOutputStream()
        read(resolver,uri,current,J1979LiveNativeReplay.MAX_CAPTURE) { buffer,n -> raw.write(buffer,0,n) }
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
