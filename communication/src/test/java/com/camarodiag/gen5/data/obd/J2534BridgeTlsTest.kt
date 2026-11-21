package com.camarodiag.gen5.data.obd

import java.security.cert.CertificateException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class J2534BridgeTlsTest {
    private val token = "A".repeat(43)
    private val fingerprint = "0123456789abcdef".repeat(4)

    @Test fun fullPairingCodeContainsPinnedCertificateAndSecretToken() {
        val pairing = J2534BridgeTlsPairing.parse("$token.$fingerprint")
        assertEquals(token, pairing.token)
        assertArrayEquals(
            fingerprint.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
            pairing.certificateSha256
        )
    }

    @Test fun allLegacyOrMalformedPairingCodesAreRejected() {
        val invalid = listOf(
            token, "", " ", "$token.GGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGGG",
            "$token.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA.$fingerprint",
            "$token.$fingerprint.extra",
            "$token.0000000000000000000000000000000000000000000000000000000000000000\n"
        )
        for (code in invalid.dropLast(1)) {
            assertThrows(IllegalArgumentException::class.java) {
                J2534BridgeTlsPairing.parse(code)
            }
        }
    }

    @Test fun noCertificateFailsBeforeAnyRequestCanBeSent() {
        val pairing = J2534BridgeTlsPairing.parse("$token.$fingerprint")
        assertThrows(CertificateException::class.java) {
            pairing.verifyCertificate(null)
        }
    }
}
