package com.camarodiag.gen5.ui

import android.content.ContentResolver
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest

/** User-selected SAF inputs only. Reference is hashed incrementally rather
 * than loaded into Android memory. Every input is reread before publication. */
internal object J1979CatalogueFiles {
    data class Inputs(val catalogue: Uri, val reference: Uri, val report: Uri, val referencePin: String) {
        fun validate() {
            require(listOf(catalogue, reference, report).all { it.scheme == "content" }) {
                "SAF document inputs required"
            }
            require(setOf(catalogue, reference, report).size == 3) { "Distinct input documents required" }
            J1979DocumentedCatalogue.digest(referencePin)
        }
    }
    data class Prepared(val bytes: ByteArray, val result: J1979CatalogueProjection.Result)
    private fun read(resolver: ContentResolver, uri: Uri, max: Int, current: () -> Unit): ByteArray {
        current()
        val input = resolver.openInputStream(uri) ?: throw IOException("Input unavailable")
        input.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                current()
                val n = stream.read(buffer)
                if (n < 0) break
                if (n == 0) throw IOException("Input made no progress")
                total += n
                require(total <= max) { "Input exceeds byte limit" }
                out.write(buffer, 0, n)
            }
            require(total > 0) { "Empty input" }
            current()
            return out.toByteArray()
        }
    }
    private fun hash(resolver: ContentResolver, uri: Uri, max: Int, current: () -> Unit): String {
        current()
        val input = resolver.openInputStream(uri) ?: throw IOException("Input unavailable")
        input.use { stream ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                current()
                val n = stream.read(buffer)
                if (n < 0) break
                if (n == 0) throw IOException("Input made no progress")
                total += n
                require(total <= max.toLong()) { "Input exceeds byte limit" }
                digest.update(buffer, 0, n)
            }
            require(total > 0) { "Empty input" }
            current()
            return J1979DocumentedCatalogue.digestText(digest.digest())
        }
    }
    fun prepare(resolver: ContentResolver, inputs: Inputs, expectedVin: String, current: () -> Unit): Prepared {
        inputs.validate()
        require(J1979CatalogueProjection.validVin(expectedVin))
        val cat = read(resolver, inputs.catalogue, J1979DocumentedCatalogue.MAX_CATALOGUE_BYTES, current)
        val source = read(resolver, inputs.report, J1979DocumentedCatalogue.MAX_REPORT_BYTES, current)
        val digest = hash(resolver, inputs.reference, J1979DocumentedCatalogue.MAX_REFERENCE_BYTES, current)
        require(digest == inputs.referencePin) { "Reference digest mismatch" }
        current()
        val result = J1979CatalogueProjection.build(cat, digest, source, expectedVin)
        val output = J1979CatalogueJson.encode(result.document)
        revalidate(resolver, inputs, result, current)
        return Prepared(output, result)
    }
    fun revalidate(resolver: ContentResolver, inputs: Inputs, result: J1979CatalogueProjection.Result,
                   current: () -> Unit) {
        inputs.validate()
        require(hash(resolver, inputs.catalogue, J1979DocumentedCatalogue.MAX_CATALOGUE_BYTES, current) == result.catalogueSha &&
            hash(resolver, inputs.report, J1979DocumentedCatalogue.MAX_REPORT_BYTES, current) == result.reportSha &&
            hash(resolver, inputs.reference, J1979DocumentedCatalogue.MAX_REFERENCE_BYTES, current) == result.referenceSha &&
            inputs.referencePin == result.referenceSha) { "Projection inputs changed" }
        current()
    }
}
