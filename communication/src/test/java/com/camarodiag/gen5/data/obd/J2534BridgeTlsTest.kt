package com.camarodiag.gen5.data.obd

import java.security.cert.CertificateException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
            "$token.$fingerprint.extra"
        )
        for (code in invalid) {
            assertThrows(IllegalArgumentException::class.java) {
                J2534BridgeTlsPairing.parse(code)
            }
        }
    }

    @Test fun pairingCodeSyntaxCheckRejectsLegacyTokensBeforeConnection() {
        assertTrue(J2534BridgePairingCodeFormat.isValid("$token.$fingerprint"))
        assertTrue(J2534BridgePairingCodeFormat.isValid("  $token.$fingerprint  "))
        assertFalse(J2534BridgePairingCodeFormat.isValid(token))
        assertFalse(J2534BridgePairingCodeFormat.isValid("$token.$fingerprint.extra"))
        assertFalse(J2534BridgePairingCodeFormat.isValid("$token.${fingerprint.dropLast(1)}"))
    }

    @Test fun tlsBridgeUsesNumericPrivateLanAddressWithoutDnsOrPublicFallback() {
        val pairing = J2534BridgeTlsPairing.parse("$token.$fingerprint")
        for (address in listOf("10.0.0.5", "172.16.0.7", "172.31.255.1",
            "192.168.1.24", "192.168.1.0", "192.168.1.255",
            "169.254.1.2")) {
            assertEquals(address, pairing.localBridgeIpv4(address).hostAddress)
        }
        for (host in listOf("", "localhost", "garage.example.com", "127.0.0.1",
            "8.8.8.8", "100.64.0.1", "172.32.0.1", "192.169.0.1",
            "192.168.001.24", "192.168.1.256",
            "192.168.1.2:35001", " 192.168.1.2",
            "2001:db8::1", "010.0.0.5")) {
            assertThrows(IllegalArgumentException::class.java) {
                pairing.localBridgeIpv4(host)
            }
        }
    }

    @Test fun certificatePinCannotBeModifiedByCaller() {
        val pairing = J2534BridgeTlsPairing.parse("$token.$fingerprint")
        val digest = pairing.certificateSha256
        digest.fill(0)
        assertFalse(pairing.certificateSha256.all { it == 0.toByte() })
    }

    @Test fun actualTrustManagerRejectsUnauthenticatedOrEmptyChains() {
        val manager = J2534BridgeTlsPairing.parse("$token.$fingerprint").pinnedTrustManager()
        assertEquals(0, manager.acceptedIssuers.size)
        assertThrows(CertificateException::class.java) { manager.checkServerTrusted(null, "RSA") }
        assertThrows(CertificateException::class.java) { manager.checkServerTrusted(emptyArray(), "RSA") }
        assertThrows(CertificateException::class.java) { manager.checkServerTrusted(emptyArray(), "") }
        assertThrows(CertificateException::class.java) { manager.checkClientTrusted(emptyArray(), "RSA") }
    }

    @Test fun noCertificateFailsBeforeAnyRequestCanBeSent() {
        val pairing = J2534BridgeTlsPairing.parse("$token.$fingerprint")
        assertThrows(CertificateException::class.java) {
            pairing.verifyCertificate(null)
        }
    }
}
