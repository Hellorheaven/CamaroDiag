package com.camarodiag.gen5.ui

/** Only fixed-name APK assets from the official CamaroDiag release path are installable. */
internal object CamaroDiagReleaseAssetPolicy {
    private val allowedTag = Regex("[A-Za-z0-9._-]+")
    private const val prefix = "https://github.com/Hellorheaven/CamaroDiag/releases/download/"

    fun accepts(url: String?, fileName: String): Boolean {
        if (fileName != "CamaroDiag-debug.apk" && fileName != "CamaroDiag-release.apk") return false
        if (url == null || !url.startsWith(prefix)) return false
        val remainder = url.removePrefix(prefix).split('/')
        return remainder.size == 2 && allowedTag.matches(remainder[0]) &&
            remainder[0] != "." && remainder[0] != ".." && remainder[1] == fileName
    }
}
