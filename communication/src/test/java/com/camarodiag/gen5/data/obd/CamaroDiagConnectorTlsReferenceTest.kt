package com.camarodiag.gen5.data.obd

import java.security.MessageDigest
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import org.junit.Assert.assertThrows
import org.junit.Test

class CamaroDiagConnectorTlsReferenceTest {
    // Synthetic byte strings for the post-handshake pin policy only, not certificates.
    private val peer = byteArrayOf(1, 2, 3)
    private val host = byteArrayOf(4, 5, 6)
    private fun pin(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun policy() = CamaroDiagConnectorTlsReference("connector", pin(peer), pin(host))

    @Test fun exactDeviceAndHostIdentityAfterTls13Handshake() {
        policy().checkNegotiatedIdentity("TLSv1.3", CamaroDiagConnectorTlsReference.ALPN, peer, host)
    }

    @Test fun missingOrWrongCertificateAndSwappedRolesAreRejected() {
        val policy = policy()
        for ((device, client) in listOf(null to host, peer to null, host to peer,
                peer to byteArrayOf(), byteArrayOf(5) to host)) {
            assertThrows(CertificateException::class.java) {
                policy.checkNegotiatedIdentity("TLSv1.3", CamaroDiagConnectorTlsReference.ALPN, device, client)
            }
        }
    }

    @Test fun refusesDowngradeAndDifferentApplicationProtocol() {
        for ((version, alpn) in listOf("TLSv1.2" to CamaroDiagConnectorTlsReference.ALPN,
                "TLSv1.3" to null, "TLSv1.3" to "other/1")) {
            assertThrows(SSLException::class.java) {
                policy().checkNegotiatedIdentity(version, alpn, peer, host)
            }
        }
    }

    @Test fun requiresExplicitProvisionedFingerprintsAndName() {
        for (bad in listOf("", "123", "g".repeat(64), "A".repeat(64), " " + pin(peer))) {
            assertThrows(IllegalArgumentException::class.java) {
                CamaroDiagConnectorTlsReference("connector", bad, pin(host))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            CamaroDiagConnectorTlsReference("", pin(peer), pin(host))
        }
    }
}
