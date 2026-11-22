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
