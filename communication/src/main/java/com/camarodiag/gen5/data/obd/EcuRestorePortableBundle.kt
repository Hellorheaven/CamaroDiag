package com.camarodiag.gen5.data.obd

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Portable single-file transport of already verified offline restoration images.
 *
 * The ZIP conveys physical addresses and exact original bytes via its enclosed
 * index and region images. Import validates the complete ZIP footer and every
 * region digest. It cannot prove ECU provenance or that an OEM restore driver
 * exists, and it sends no ECU command.
 */
object EcuRestorePortableBundle {
    private const val MAX_TOTAL_BYTES = 256L * 1024 * 1024 + 256L * 1024
    private const val MAX_REGION_FILE_BYTES = 64L * 1024 * 1024 + 2048L
    private const val MAX_INDEX_BYTES = 65536L
    private const val MAX_FILES = 129
    private val regionName = Regex("region-(0|[1-9][0-9]{0,2})\\.ecu-image")

    fun export(packageDirectory: File, output: OutputStream) {
        val verified = EcuRestorePackage.open(packageDirectory)
        val names = verified.regions.indices.map { "region-$it.ecu-image" } +
            "index.ecu-package"
        ZipOutputStream(output).use { zip ->
            names.forEach { name ->
                val source = File(packageDirectory, name)
                zip.putNextEntry(ZipEntry(name))
                source.inputStream().buffered().use { input ->
                    input.copyTo(zip, 8192)
                }
                zip.closeEntry()
            }
        }
    }

    /**
     * Extract only into a newly created directory. Failed or interrupted
     * extraction leaves a non-authoritative directory that open() rejects.
     */
    fun importNew(input: InputStream, directory: File): EcuRestorePackage.Contents {
        check(directory.mkdir()) { "A fresh ECU restore package directory is required" }
        val names = mutableListOf<String>()
        val source = EcuZipCompletionGuard(input, MAX_TOTAL_BYTES + 1024L * 1024)
        var uncompressed = 0L
        ZipInputStream(source).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                require(!entry.isDirectory &&
                    (name == "index.ecu-package" || regionName.matches(name)) &&
                    name !in names && names.size < MAX_FILES
                ) { "Invalid, duplicate or unexpected ECU image archive member" }
                val limit = if (name == "index.ecu-package") MAX_INDEX_BYTES
                    else MAX_REGION_FILE_BYTES
                var written = 0L
                val target = File(directory, name)
                check(target.createNewFile()) { "ECU image archive member already exists" }
                FileOutputStream(target).use { file ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        written += count
                        uncompressed += count
                        require(written <= limit && uncompressed <= MAX_TOTAL_BYTES) {
                            "ECU image archive decompressed size limit exceeded"
                        }
                        file.write(buffer, 0, count)
                    }
                    file.fd.sync()
                }
                names += name
                zip.closeEntry()
            }
            source.requireCompletedArchive(names)
        }
        return EcuRestorePackage.open(directory)
    }
}
