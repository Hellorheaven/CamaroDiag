package com.camarodiag.gen5.data.obd

import android.content.Context
import android.security.KeyChain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Socket
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Locale
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509ExtendedKeyManager

/** Only public identity metadata is retained in UI state. Android KeyChain owns the key. */
data class DoIpClientCertificateSelection internal constructor(
    val alias: String, val host: String, val vin: String, val fingerprint: String
) {
    internal fun requireTarget(target: DoIpDirectRead.Target) {
        if (!target.tls || target.expectedVin != vin || target.address.hostAddress != host)
            throw DoIpClientCertificateException("Client certificate belongs to a different DoIP target.")
    }
}

class DoIpClientCertificateException(message: String, cause: Throwable? = null) :
    java.io.IOException(message, cause)

class DoIpClientTls private constructor(
    private val selection: DoIpClientCertificateSelection,
    internal val factory: SSLSocketFactory,
    private val leaf: X509Certificate
) {
    internal fun requireTarget(target: DoIpDirectRead.Target) = selection.requireTarget(target)

    /** Explicit client-certificate mode must actually negotiate that certificate.
     * A server that never requested a certificate is not a successful mTLS session. */
    internal fun requirePresented(socket: SSLSocket) {
        requirePresented(socket, leaf)
    }

    companion object {
        suspend fun select(context: Context, alias: String, host: String, vin: String): DoIpClientCertificateSelection =
            withContext(Dispatchers.IO) {
                val canonical = DoIpDirectRead.canonicalHost(host)
                val expected = vin.trim().uppercase(Locale.ROOT)
                if (!expected.matches(Regex("[A-HJ-NPR-Z0-9]{17}")))
                    throw DoIpClientCertificateException("A valid VIN is required for the certificate binding.")
                val (_, chain) = load(context, alias)
                DoIpClientCertificateSelection(alias, canonical, expected, fingerprint(chain[0]))
            }

        suspend fun open(context: Context, selection: DoIpClientCertificateSelection,
            target: DoIpDirectRead.Target): DoIpClientTls = withContext(Dispatchers.IO) {
            selection.requireTarget(target)
            val (key, chain) = load(context, selection.alias)
            if (fingerprint(chain[0]) != selection.fingerprint)
                throw DoIpClientCertificateException("Selected client certificate changed; select it again.")
            try {
                val tls = SSLContext.getInstance("TLS")
                // Null trust managers use platform trust; no bypass or global trust changes.
                tls.init(arrayOf(DoIpFixedClientKeyManager(selection.alias, key, chain)), null, null)
                DoIpClientTls(selection, tls.socketFactory, chain[0])
            } catch (error: java.security.GeneralSecurityException) {
                throw DoIpClientCertificateException("Could not initialize client TLS.", error)
            }
        }

        private fun load(context: Context, alias: String): Pair<PrivateKey, Array<X509Certificate>> {
            if (alias.isBlank() || alias == "android:alias-selection-denied")
                throw DoIpClientCertificateException("No client certificate selected.")
            try {
                val key = KeyChain.getPrivateKey(context.applicationContext, alias)
                    ?: throw DoIpClientCertificateException("Client key is missing or access was revoked.")
                val chain = KeyChain.getCertificateChain(context.applicationContext, alias)
                    ?: throw DoIpClientCertificateException("Client certificate chain is missing.")
                validateChain(key, chain)
                return key to chain
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw CancellationException("Client certificate lookup interrupted.", interrupted)
            } catch (error: DoIpClientCertificateException) {
                throw error
            } catch (error: Exception) {
                throw DoIpClientCertificateException("Client certificate unavailable, invalid or expired.", error)
            }
        }

        internal fun requirePresented(socket: SSLSocket, expected: X509Certificate) {
            if (socket.session.localCertificates?.firstOrNull() != expected)
                throw SSLHandshakeException("Selected DoIP client certificate was not presented.")
        }

        internal fun fingerprint(certificate: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
                .joinToString("") { "%02X".format(Locale.ROOT, it.toInt() and 255) }

        internal fun validateChain(key: PrivateKey, chain: Array<X509Certificate>) {
            require(chain.isNotEmpty())
            chain.forEach { it.checkValidity() }
            val leaf = chain[0]
            require(key.algorithm.equals(leaf.publicKey.algorithm, true))
            require(leaf.keyUsage?.getOrNull(0) != false) { "Client key cannot sign." }
            require(leaf.extendedKeyUsage?.let {
                "1.3.6.1.5.5.7.3.2" in it || "2.5.29.37.0" in it
            } != false) { "Certificate is not valid for TLS client authentication." }
        }
    }
}

/** No fallback alias, no server key, no key export. */
internal class DoIpFixedClientKeyManager(
    private val alias: String, private val key: PrivateKey, certificates: Array<X509Certificate>
) : X509ExtendedKeyManager() {
    private val chain = certificates.copyOf()

    private fun matches(keyType: String?, issuers: Array<out Principal>?): Boolean {
        if (keyType == null || !keyType.substringBefore('_').equals(key.algorithm, true)) return false
        return issuers.isNullOrEmpty() || chain.any { cert ->
            issuers.any { it == cert.issuerX500Principal || it == cert.subjectX500Principal }
        }
    }

    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
        if (matches(keyType, issuers)) arrayOf(alias) else null

    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?,
        socket: Socket?): String? = if (keyType?.any { matches(it, issuers) } == true) alias else null

    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?,
        engine: SSLEngine?): String? = chooseClientAlias(keyType, issuers, null)

    override fun getCertificateChain(requested: String?): Array<X509Certificate>? =
        if (requested == alias) chain.copyOf() else null

    override fun getPrivateKey(requested: String?): PrivateKey? = if (requested == alias) key else null
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?): String? = null
}
