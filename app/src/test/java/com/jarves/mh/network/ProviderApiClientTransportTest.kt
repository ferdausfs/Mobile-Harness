package com.jarves.mh.network

import com.jarves.mh.model.ProviderProtocol
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Transport-level tests against a tiny loopback HTTP server. They verify what
 * the app actually puts on the wire (User-Agent, Authorization) and how
 * 401/403 answers turn into user-facing validation results, without talking
 * to any real provider.
 */
class ProviderApiClientTransportTest {

    /**
     * Minimal HTTP/1.1 server: records the User-Agent and Authorization of
     * every request and answers each one with the configured status + body.
     * (com.sun.net.httpserver is not on the Android unit-test classpath, so
     * this speaks just enough HTTP over a raw socket.)
     */
    private class RecordingServer {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = server.localPort
        val receivedUserAgents = mutableListOf<String>()
        val receivedAuthorizations = mutableListOf<String>()

        @Volatile var statusLine: String = "200 OK"
        @Volatile var body: String = """{"data":[{"id":"model-a"}]}"""
        @Volatile var contentType: String = "application/json"

        fun start() = apply {
            Thread({
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    Thread({ socket.use(::handle) }).apply { isDaemon = true; start() }
                }
            }, "test-http-server").apply { isDaemon = true; start() }
        }

        fun stop() = runCatching { server.close() }

        private fun handle(socket: Socket) {
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            readLine(input) ?: return
            var userAgent: String? = null
            var authorization: String? = null
            var contentLength = 0
            while (true) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) break
                val split = line.indexOf(':')
                if (split <= 0) continue
                val name = line.substring(0, split).trim().lowercase(Locale.ROOT)
                val value = line.substring(split + 1).trim()
                when (name) {
                    "user-agent" -> userAgent = value
                    "authorization" -> authorization = value
                    "content-length" -> contentLength = value.toIntOrNull() ?: 0
                }
            }
            // Drain the request body so the client never blocks mid-write.
            var remaining = contentLength
            val buffer = ByteArray(8192)
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(remaining, buffer.size))
                if (count < 0) break
                remaining -= count
            }
            synchronized(this) {
                userAgent?.let { receivedUserAgents += it }
                authorization?.let { receivedAuthorizations += it }
            }
            val bytes = body.toByteArray()
            output.write(
                "HTTP/1.1 $statusLine\r\nContent-Type: $contentType\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(),
            )
            output.write(bytes)
            output.flush()
        }

        private fun readLine(input: BufferedInputStream): String? {
            val bytes = ArrayList<Byte>()
            while (bytes.size < 16 * 1024) {
                val value = input.read()
                if (value < 0) return if (bytes.isEmpty()) null else bytes.toByteArray().decodeToString()
                if (value == '\n'.code) return bytes.toByteArray().decodeToString().trimEnd('\r')
                bytes += value.toByte()
            }
            return null
        }
    }

    private lateinit var server: RecordingServer

    @Before
    fun setUp() {
        server = RecordingServer().start()
    }

    @After
    fun tearDown() {
        server.stop()
    }

    private val base: String get() = "http://127.0.0.1:${server.port}"

    @Test
    fun providerRequestsCarryTheAppUserAgentAndASanitizedKey() = runBlocking<Unit> {
        server.statusLine = "200 OK"
        server.body = """{"data":[{"id":"model-a"}]}"""

        val result = ProviderApiClient().discoverModels(
            baseUrl = base,
            // A paste with whitespace AND a copied "Bearer " prefix must arrive
            // at the provider as a clean single Bearer token.
            apiKey = "\n  Bearer test-key-123 \t",
            protocol = ProviderProtocol.OPENAI_CHAT,
        )

        assertTrue("Expected discovery to succeed, got: $result", result is ModelDiscoveryResult.Success)
        assertTrue("No request reached the server", server.receivedUserAgents.isNotEmpty())
        synchronized(server) {
            server.receivedUserAgents.forEach { userAgent ->
                assertEquals(ProviderHttp.APP_USER_AGENT, userAgent)
            }
            server.receivedAuthorizations.forEach { authorization ->
                assertEquals("Bearer test-key-123", authorization)
            }
        }
        assertFalse(ProviderHttp.APP_USER_AGENT.contains("Dalvik"))
    }

    @Test
    fun edge403HtmlIsAConnectionBlockNotAKeyRejection() = runBlocking<Unit> {
        // Exactly what ollama.com's Google-Frontend edge returned to the
        // Dalvik User-Agent during review: 403 + a tiny HTML page.
        server.statusLine = "403 Forbidden"
        server.contentType = "text/html"
        server.body = "<html><head><title>403</title></head><body>403 Forbidden</body></html>"

        val result = ProviderApiClient().validate(
            baseUrl = base,
            model = "test-model",
            apiKey = "test-key",
            protocol = ProviderProtocol.OPENAI_CHAT,
            discoveredModels = emptyList(),
        )

        assertTrue(result is ConnectionValidation.Failure)
        result as ConnectionValidation.Failure
        assertEquals("Blocked", result.label)
        assertTrue("Expected the connection-block message, got: ${result.message}", result.message.contains("refused the app's connection before checking the key"))
        assertFalse("A blocked request must not be called a key rejection", result.message.contains("rejected this key"))
    }

    @Test
    fun unauthorized401JsonIsAKeyRejection() = runBlocking<Unit> {
        server.statusLine = "401 Unauthorized"
        server.body = """{"error":{"message":"Unauthorized","type":"authentication_error"}}"""

        val result = ProviderApiClient().validate(
            baseUrl = base,
            model = "test-model",
            apiKey = "test-key",
            protocol = ProviderProtocol.OPENAI_CHAT,
            discoveredModels = emptyList(),
        )

        assertTrue(result is ConnectionValidation.Failure)
        result as ConnectionValidation.Failure
        assertEquals("Rejected", result.label)
        assertTrue("Expected the key-rejection message, got: ${result.message}", result.message.contains("rejected this key"))
        assertFalse(result.message.contains("connection block"))
    }

    @Test
    fun genuine403JsonAuthErrorStaysAKeyRejection() = runBlocking<Unit> {
        // Anthropic's genuine 403 for a bad key carries a JSON error body - it
        // must NOT be reclassified as an edge block.
        server.statusLine = "403 Forbidden"
        server.body = """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""

        val result = ProviderApiClient().validate(
            baseUrl = base,
            model = "test-model",
            apiKey = "test-key",
            protocol = ProviderProtocol.OPENAI_CHAT,
            discoveredModels = emptyList(),
        )

        assertTrue(result is ConnectionValidation.Failure)
        result as ConnectionValidation.Failure
        assertEquals("Rejected", result.label)
        assertTrue(result.message.contains("rejected this key"))
    }
}
