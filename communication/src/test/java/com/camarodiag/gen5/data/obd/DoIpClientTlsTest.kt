package com.camarodiag.gen5.data.obd

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.security.auth.x500.X500Principal

class DoIpClientTlsTest {
    private val vin = "1G1FA1RX0J0100001"
    private val password = "local-test-only".toCharArray()

    private fun createStore(directory: File, name: String, eku: String): KeyStore {
        val file = File(directory, "$name.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool" +
            if (System.getProperty("os.name")?.startsWith("Windows", true) == true) ".exe" else "")
        val process = ProcessBuilder(keytool.absolutePath, "-genkeypair", "-alias", name,
            "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=$name",
            "-validity", "365", "-ext", "EKU=$eku", "-ext", "SAN=IP:127.0.0.1",
            "-storetype", "PKCS12", "-keystore", file.absolutePath,
            "-storepass", String(password), "-keypass", String(password), "-noprompt")
            .redirectErrorStream(true).redirectOutput(File(directory, "$name.log")).start()
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("Test keytool timed out")
        }
        check(process.exitValue() == 0) { File(directory, "$name.log").readText() }
        return KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, password) } }
    }

    private fun cert(store: KeyStore, name: String) = store.getCertificate(name) as X509Certificate

    private fun trust(certificate: X509Certificate): Array<TrustManager> {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("test-peer", certificate)
        }
        return TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(store)
        }.trustManagers
    }

    private fun context(keys: Array<out KeyManager>?, trust: Array<TrustManager>) =
        SSLContext.getInstance("TLS").apply { init(keys, trust, null) }

    @Test fun selectionRejectsWrongHostVinOrPlaintext() {
        val selected = DoIpClientCertificateSelection("fixture", "127.0.0.1", vin, "A".repeat(64))
        selected.requireTarget(DoIpDirectRead.Target.parse("127.0.0.1", "0E80", "1234", vin, true))
        for (target in listOf(
            DoIpDirectRead.Target.parse("127.0.0.2", "0E80", "1234", vin, true),
            DoIpDirectRead.Target.parse("127.0.0.1", "0E80", "1234", vin.dropLast(1) + "2", true),
            DoIpDirectRead.Target.parse("127.0.0.1", "0E80", "1234", vin, false)
        )) assertTrue(runCatching { selected.requireTarget(target) }.exceptionOrNull() is DoIpClientCertificateException)
    }

    @Test fun mutualTlsUsesOnlySelectedClientAndStillChecksServerTrust() {
        val directory = Files.createTempDirectory("doip-mtls-test").toFile()
        try {
            val clientStore = createStore(directory, "client", "clientAuth")
            val serverStore = createStore(directory, "server", "serverAuth")
            val client = cert(clientStore, "client")
            val server = cert(serverStore, "server")
            val key = clientStore.getKey("client", password) as PrivateKey
            val serverKey = serverStore.getKey("server", password) as PrivateKey
            val chain = arrayOf(client)
            DoIpClientTls.validateChain(key, chain)
            assertTrue(runCatching { DoIpClientTls.validateChain(serverKey, arrayOf(server)) }.isFailure)
            assertEquals(64, DoIpClientTls.fingerprint(client).length)
            assertNotEquals(DoIpClientTls.fingerprint(client), DoIpClientTls.fingerprint(server))
            val manager = DoIpFixedClientKeyManager("client", key, chain)
            assertEquals("client", manager.chooseClientAlias(arrayOf("RSA"), null, null))
            assertNull(manager.chooseClientAlias(arrayOf("EC"), null, null))
            assertNull(manager.chooseClientAlias(arrayOf("RSA"), arrayOf(X500Principal("CN=unknown")), null))
            assertNull(manager.getPrivateKey("another"))
            assertNull(manager.getCertificateChain("another"))
            assertNull(manager.chooseServerAlias("RSA", null, null))

            val serverManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(serverStore, password)
            }.keyManagers
            val serverContext = context(serverManagers, trust(client))
            for (mode in listOf("mutual", "server-only", "wrong-trust")) {
                val executor = Executors.newSingleThreadExecutor()
                try {
                    (serverContext.serverSocketFactory.createServerSocket(0) as SSLServerSocket).use { listener ->
                        listener.soTimeout = 5000
                        listener.needClientAuth = mode != "server-only"
                        val peer = executor.submit<Boolean> {
                            try {
                                (listener.accept() as SSLSocket).use { socket ->
                                    socket.soTimeout = 5000
                                    socket.startHandshake()
                                    if (mode == "mutual") assertEquals(client, socket.session.peerCertificates[0])
                                    true
                                }
                            } catch (error: SSLHandshakeException) {
                                if (mode != "wrong-trust") throw error
                                false
                            }
                        }
                        val clientContext = context(arrayOf(manager), trust(if (mode == "wrong-trust") client else server))
                        (clientContext.socketFactory.createSocket("127.0.0.1", listener.localPort) as SSLSocket).use { socket ->
                            socket.soTimeout = 5000
                            socket.sslParameters = socket.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                            if (mode == "wrong-trust") {
                                assertTrue(runCatching { socket.startHandshake() }.exceptionOrNull() is SSLHandshakeException)
                            } else {
                                socket.startHandshake()
                                if (mode == "mutual") DoIpClientTls.requirePresented(socket, client)
                                else assertTrue(runCatching {
                                    DoIpClientTls.requirePresented(socket, client)
                                }.exceptionOrNull() is SSLHandshakeException)
                            }
                        }
                        assertEquals(mode != "wrong-trust", peer.get(10, TimeUnit.SECONDS))
                    }
                } finally { executor.shutdownNow() }
            }
        } finally { directory.deleteRecursively() }
    }
}
