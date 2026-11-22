package com.camarodiag.gen5.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CamaroDiagReleaseAssetPolicyTest {
    private val base = "https://github.com/Hellorheaven/CamaroDiag/releases/download/v1.2.3/"

    @Test fun officialChannelArtifactsAreAccepted() {
        for (name in listOf("CamaroDiag-debug.apk", "CamaroDiag-release.apk")) {
            assertTrue(CamaroDiagReleaseAssetPolicy.accepts(base + name, name))
        }
    }

    @Test fun officialReleaseTagMustMatchTheApkAssetTag() {
        val name = "CamaroDiag-release.apk"
        assertTrue(CamaroDiagReleaseAssetPolicy.accepts(base + name, name, "v1.2.3"))
        assertFalse(CamaroDiagReleaseAssetPolicy.accepts(base + name, name, "v1.2.4"))
        assertFalse(CamaroDiagReleaseAssetPolicy.accepts(base + name, name, "../v1.2.3"))
        assertFalse(CamaroDiagReleaseAssetPolicy.accepts(base + name, name, ""))
    }

    @Test fun stagedApkCopyRequiresProgressAndHonorsConfiguredSizeLimit() {
        val source = byteArrayOf(1, 2, 3, 4)
        val copied = java.io.ByteArrayOutputStream()
        assertTrue(CamaroDiagReleaseAssetPolicy.copyBounded(
            source.inputStream(), copied, maxBytes = 4) == 4L)
        assertTrue(copied.toByteArray().contentEquals(source))
        val limited = java.io.ByteArrayOutputStream()
        assertTrue(runCatching {
            CamaroDiagReleaseAssetPolicy.copyBounded(source.inputStream(), limited, maxBytes = 3)
        }.isFailure)
        assertTrue(limited.size() == 0)
        assertTrue(runCatching {
            CamaroDiagReleaseAssetPolicy.copyBounded(byteArrayOf().inputStream(),
                java.io.ByteArrayOutputStream(), maxBytes = 8)
        }.isFailure)
    }
    @Test fun onlyOfficialRepositoryExactNameAndPlainTagAreAccepted() {
        val name = "CamaroDiag-release.apk"
        for (url in listOf(
            "https://github.com/attacker/CamaroDiag/releases/download/v1.2.3/$name",
            "https://github.com/Hellorheaven/CamaroDiagOther/releases/download/v1.2.3/$name",
            "https://github.com/Hellorheaven/CamaroDiag/releases/download/../$name",
            "https://github.com/Hellorheaven/CamaroDiag/releases/download/v1.2.3/other.apk",
            "https://github.com/Hellorheaven/CamaroDiag/releases/download/v1.2.3/$name?redirect=x",
            "https://github.com/Hellorheaven/CamaroDiag/releases/download/v1.2.3/$name#x",
            "https://github.com/Hellorheaven/CamaroDiag/releases/download/v1.2.3/$name/extra",
            "http://github.com/Hellorheaven/CamaroDiag/releases/download/v1.2.3/$name",
            "https://github.com.evil.example/Hellorheaven/CamaroDiag/releases/download/v1.2.3/$name"
        )) assertFalse(url, CamaroDiagReleaseAssetPolicy.accepts(url, name))
        assertFalse(CamaroDiagReleaseAssetPolicy.accepts(null, name))
        assertFalse(CamaroDiagReleaseAssetPolicy.accepts(base + name, "unknown.apk"))
        assertFalse(CamaroDiagReleaseAssetPolicy.accepts(base + name, "CamaroDiag-debug.apk"))
    }
}
