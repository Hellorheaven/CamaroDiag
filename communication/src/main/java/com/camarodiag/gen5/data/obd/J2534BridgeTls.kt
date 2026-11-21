package com.camarodiag.gen5.data.obd

import android.annotation.SuppressLint
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * A bridge pairing code binds the short-lived bearer token to the Windows
 * bridge's TLS leaf certificate SHA-256 fingerprint.
 *
 * The code is copied directly from the local Windows bridge console, rather
 * than learned over the network (TOFU would allow interception at first use).
 * Legacy token-only bridges and plaintext TCP are deliberately incompatible.
 */
/**
 * Public, transport-independent syntax check so Android cannot submit legacy
 * token-only codes. The security decision is still made by the TLS parser and
 * certificate pin check during the actual connection.
 */
object J2534BridgePairingCodeFormat {
    private val pattern = Regex("^[A-Za-z0-9_-]{43}\\.[0-9a-fA-F]{64}$")

    fun isValid(code: String): Boolean = pattern.matches(code.trim())
}

internal class J2534BridgeTlsPairing private constructor(
    val token: String,
    certificateSha256: ByteArray
) {
    private val pinnedDigest = certificateSha256.copyOf()
    val certificateSha256: ByteArray get() = pinnedDigest.copyOf()
    companion object {
        fun parse(pairingCode: String): J2534BridgeTlsPairing {
            val normalized = pairingCode.trim()
            require(J2534BridgePairingCodeFormat.isValid(normalized)) {
                "Appairage non sécurisé : recopier le code complet (jeton.empreinte TLS) affiché par le nouveau pont Windows."
            }
            val parts = normalized.split('.')
            val pin = ByteArray(32) { index ->
                parts[1].substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
            return J2534BridgeTlsPairing(parts[0], pin)
        }
    }

    internal fun verifyCertificate(chain: Array<out X509Certificate>?) {
        val certificate = chain?.firstOrNull()
            ?: throw CertificateException("Le pont J2534 n'a fourni aucun certificat TLS.")
        certificate.checkValidity()
        val received = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        if (!MessageDigest.isEqual(pinnedDigest, received)) {
            throw CertificateException("Empreinte TLS du pont J2534 incorrecte : connexion refusée.")
        }
    }

    // The bridge deliberately uses a fresh self-signed certificate, not a
    // public CA. Trust is restricted to an exact SHA-256 leaf fingerprint
    // supplied out of band by the local pairing code. The TLS handshake also
    // verifies proof of possession of the associated private key.
    // Android's general CA trust manager would reject the intended peer;
    // never fall back to accepting an arbitrary certificate or hostname.
    @SuppressLint("CustomX509TrustManager")
    internal fun pinnedTrustManager(): X509TrustManager =
        object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

            override fun checkClientTrusted(
                chain: Array<out X509Certificate>?, authType: String?
            ) {
                throw CertificateException("Les certificats clients ne sont pas acceptés ici.")
            }

            override fun checkServerTrusted(
                chain: Array<out X509Certificate>?, authType: String?
            ) {
                if (authType.isNullOrBlank()) {
                    throw CertificateException("Authentification TLS du serveur absente.")
                }
                verifyCertificate(chain)
            }
        }
    fun openSocket(host: String, port: Int): SSLSocket {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(pinnedTrustManager()), SecureRandom())

        val plain = Socket()
        try {
            plain.tcpNoDelay = true
            plain.connect(java.net.InetSocketAddress(host, port), 5000)
            plain.soTimeout = 12_000

            // The plain socket transports only encrypted TLS handshake records:
            // no token, JSON command or ECU payload is emitted before handshake.
            val secure = context.socketFactory.createSocket(plain, host, port, true) as SSLSocket
            val permitted = secure.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }
            if (permitted.isEmpty()) {
                secure.close()
                throw IllegalStateException("Aucun protocole TLS 1.2+ disponible.")
            }
            secure.enabledProtocols = permitted.toTypedArray()
            secure.useClientMode = true
            secure.startHandshake()
            return secure
        } catch (error: Exception) {
            runCatching { plain.close() }
            throw error
        }
    }
}
