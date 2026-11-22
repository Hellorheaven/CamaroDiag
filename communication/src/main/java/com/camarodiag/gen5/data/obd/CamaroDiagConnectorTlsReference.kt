package com.camarodiag.gen5.data.obd

import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory

/**
 * Reference for a future provisioned, mutual TLS 1.3 IP discovery link.
 * Not the public USB CD/1 protocol, not the existing PC bridge pairing,
 * and not an ECU transport. No app UI or hardware capabilities are enabled.
 * Credentials and trust anchors must be provisioned locally by the owner.
 */
internal class CamaroDiagConnectorTlsReference(
    private val expectedDeviceName: String,
    deviceSha256: String,
    hostSha256: String
) {
    private val devicePin = decodePin(deviceSha256)
    private val hostPin = decodePin(hostSha256)

    init {
        require(expectedDeviceName.isNotBlank() && expectedDeviceName == expectedDeviceName.trim())
    }

    private fun decodePin(value: String): ByteArray {
        require(value.matches(Regex("[0-9a-f]{64}"))) { "Explicit provisioned certificate SHA-256 required" }
        return ByteArray(32) { i -> value.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    // Used only AFTER the standard PKIX/hostname handshake. A matching digest
    // alone must never be treated as a substitute for that handshake.
    internal fun checkNegotiatedIdentity(
        protocol: String, alpn: String?, peerDer: ByteArray?, localDer: ByteArray?
    ) {
        if (protocol != "TLSv1.3" || alpn != ALPN) throw SSLException("Unexpected connector TLS protocol")
        fun check(expected: ByteArray, der: ByteArray?) {
            if (der == null || der.isEmpty() ||
                !MessageDigest.isEqual(expected, MessageDigest.getInstance("SHA-256").digest(der))) {
                throw CertificateException("Unprovisioned connector or local host certificate")
            }
        }
        check(devicePin, peerDer)
        check(hostPin, localDer)
    }

    /**
     * Dedicated trusted CA and local client key managers (e.g. backed by
     * Android Keystore). Fresh TLSContext per connection, no fallback to
     * public trust stores or bearer token auth, no application data pre-check.
     */
    fun openIsolatedReferenceSocket(
        port: Int, deviceCa: X509Certificate, clientKeys: Array<KeyManager>
    ): SSLSocket {
        require(port in 1..65535)
        require(clientKeys.isNotEmpty()) { "Local client identity required" }
        deviceCa.checkValidity()
        require(deviceCa.basicConstraints >= 0) { "Dedicated device CA required" }
        val anchors = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("connector-ca", deviceCa)
        }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(anchors)
        }
        val context = SSLContext.getInstance("TLSv1.3").apply {
            init(clientKeys.copyOf(), trust.trustManagers, SecureRandom())
        }
        val plain = Socket()
        var secure: SSLSocket? = null
        try {
            plain.connect(InetSocketAddress(expectedDeviceName, port), 3000)
            plain.soTimeout = 3000
            val tls = context.socketFactory.createSocket(plain, expectedDeviceName, port, true) as SSLSocket
            secure = tls
            tls.useClientMode = true
            tls.enabledProtocols = arrayOf("TLSv1.3")
            tls.sslParameters = tls.sslParameters.apply {
                endpointIdentificationAlgorithm = "HTTPS"
                applicationProtocols = arrayOf(ALPN)
            }
            tls.startHandshake()
            val peer = tls.session.peerCertificates.firstOrNull() as? X509Certificate
            val local = tls.session.localCertificates?.firstOrNull() as? X509Certificate
            peer?.checkValidity()
            local?.checkValidity()
            checkNegotiatedIdentity(tls.session.protocol, tls.applicationProtocol, peer?.encoded, local?.encoded)
            return tls
        } catch (error: Exception) {
            runCatching { secure?.close() }
            runCatching { plain.close() }
            throw error
        }
    }

    companion object {
        const val ALPN = "camarodiag-discovery/1"
    }
}
