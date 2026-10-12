package com.yarmiplaytv.syncplay

import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import kotlin.concurrent.thread

/**
 * Dialling an address but verifying the certificate as another name, against a real TLS server whose certificate (for
 * `test.example`) comes from a test CA the client trusts. Verification itself is never relaxed: only the name changes.
 */
class TlsNameTransportTest {
    @Test
    fun `dialling the IP and verifying as the certificate's name succeeds`() {
        val t = SocketTransport("127.0.0.1", server.localPort, sslSocketFactory = clientFactory, tlsName = "test.example")
        runBlocking {
            t.connect()
            t.startTls()
        }
        assertTrue(t.isTls)
        assertTrue(t.remoteAddress == "127.0.0.1")
        t.close()
    }

    @Test
    fun `verifying as another name fails`() = assertHandshakeFails(SocketTransport("127.0.0.1", server.localPort, sslSocketFactory = clientFactory, tlsName = "other.example"))

    @Test
    fun `without a name the IP itself is verified, and fails as before`() = assertHandshakeFails(SocketTransport("127.0.0.1", server.localPort, sslSocketFactory = clientFactory))

    private fun assertHandshakeFails(t: SocketTransport) {
        try {
            runBlocking {
                t.connect()
                t.startTls()
            }
            fail("the handshake should have failed")
        } catch (e: SSLHandshakeException) {
            // Expected: the certificate is only for test.example.
        } finally {
            t.close()
        }
    }

    companion object {
        private const val PASS = "testpass"
        private lateinit var dir: File
        private lateinit var server: ServerSocket
        private lateinit var clientFactory: SSLSocketFactory

        /** A CA, and a certificate for test.example signed by it, made with the JDK's keytool. */
        @BeforeClass
        @JvmStatic
        fun setUp() {
            dir = Files.createTempDirectory("tls-name").toFile()
            val ca = File(dir, "ca.p12").path
            val srv = File(dir, "server.p12").path
            val common = arrayOf("-storetype", "PKCS12", "-storepass", PASS, "-keypass", PASS)
            keytool("-genkeypair", "-alias", "ca", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=Test CA", "-ext", "bc:c", "-validity", "2", "-keystore", ca, *common)
            keytool("-genkeypair", "-alias", "server", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=test.example", "-validity", "2", "-keystore", srv, *common)
            keytool("-certreq", "-alias", "server", "-keystore", srv, "-file", File(dir, "server.csr").path, *common)
            keytool(
                "-gencert", "-alias", "ca", "-keystore", ca, "-infile", File(dir, "server.csr").path, "-outfile", File(dir, "server.cer").path,
                "-ext", "SAN=dns:test.example", "-ext", "EKU=serverAuth", "-validity", "2", *common,
            )
            keytool("-exportcert", "-alias", "ca", "-keystore", ca, "-rfc", "-file", File(dir, "ca.cer").path, *common)
            keytool("-importcert", "-alias", "ca", "-file", File(dir, "ca.cer").path, "-keystore", srv, "-noprompt", *common)
            keytool("-importcert", "-alias", "server", "-file", File(dir, "server.cer").path, "-keystore", srv, *common)

            val serverStore = KeyStore.getInstance("PKCS12").apply { File(srv).inputStream().use { load(it, PASS.toCharArray()) } }
            val serverContext = SSLContext.getInstance("TLS").apply {
                init(KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(serverStore, PASS.toCharArray()) }.keyManagers, null, null)
            }
            val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("ca", File(dir, "ca.cer").inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) })
            }
            clientFactory = SSLContext.getInstance("TLS").apply {
                init(null, TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }.trustManagers, null)
            }.socketFactory

            // Accepts plain TCP, then upgrades each connection to TLS (the server side of STARTTLS).
            server = ServerSocket(0)
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val plain = runCatching { server.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) {
                        runCatching {
                            val tls = serverContext.socketFactory.createSocket(plain, null, plain.port, true) as SSLSocket
                            tls.useClientMode = false
                            tls.startHandshake()
                            tls.inputStream.read()
                        }
                        runCatching { plain.close() }
                    }
                }
            }
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            runCatching { server.close() }
            dir.deleteRecursively()
        }

        private fun keytool(vararg args: String) {
            val exe = File(System.getProperty("java.home"), "bin/" + if (System.getProperty("os.name").startsWith("Windows")) "keytool.exe" else "keytool")
            val process = ProcessBuilder(listOf(exe.path) + args).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "keytool ${args.first()} failed: $output" }
        }
    }
}
