package com.camarodiag.gen5.ui

/** Only fixed-name APK assets from the official CamaroDiag release path are installable. */
internal object CamaroDiagReleaseAssetPolicy {
    private val allowedTag = Regex("[A-Za-z0-9._+-]+")
    const val MAX_APK_BYTES = 512L * 1024L * 1024L
    private const val prefix = "https://github.com/Hellorheaven/CamaroDiag/releases/download/"

    fun accepts(url: String?, fileName: String, expectedTag: String): Boolean {
        // A release tag is mandatory: allowing it to be omitted would accept
        // any older or attacker-selected asset under the same repository.
        if (!allowedTag.matches(expectedTag) ||
            expectedTag == "." || expectedTag == "..") return false
        if (fileName != "CamaroDiag-debug.apk" && fileName != "CamaroDiag-release.apk") return false
        if (url == null || !url.startsWith(prefix)) return false
        val remainder = url.removePrefix(prefix).split('/')
        return remainder.size == 2 && remainder[0] == expectedTag &&
            remainder[1] == fileName
    }
    /** Refuse truncated or excessively large APKs before writing past the limit. */
    fun copyBounded(input: java.io.InputStream, output: java.io.OutputStream,
                    maxBytes: Long = MAX_APK_BYTES): Long {
        require(maxBytes > 0)
        val buffer = ByteArray(64 * 1024)
        var copied = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) throw java.io.IOException("APK download made no progress")
            if (count.toLong() > maxBytes - copied)
                throw java.io.IOException("Downloaded APK exceeds the allowed size")
            output.write(buffer, 0, count)
            copied += count
        }
        if (copied == 0L) throw java.io.IOException("Downloaded APK is empty")
        return copied
    }
}
