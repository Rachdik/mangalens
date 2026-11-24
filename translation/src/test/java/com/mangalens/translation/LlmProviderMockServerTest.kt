package com.mangalens.translation

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Uji provider LLM tanpa kunci asli: server HTTP lokal (ServerSocket) meniru
 * endpoint OpenAI-compatible. Membuktikan request, parsing, dan error handling.
 *
 * `com.sun.net.httpserver` tidak tersedia di classpath unit test Android,
 * jadi server minimal ditulis langsung di atas socket.
 */
class LlmProviderMockServerTest {

    private lateinit var socket: ServerSocket
    private lateinit var serverThread: Thread
    private var port = 0
    private val authorization = AtomicReference("")
    private val body = AtomicReference("")

    @Before
    fun startServer() {
        socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        port = socket.localPort
    }

    @After
    fun stopServer() {
        runCatching { socket.close() }
        runCatching { serverThread.join(500) }
    }

    private fun respond(status: Int, content: String) {
        serverThread = thread(isDaemon = true) {
            runCatching {
                socket.accept().use { client ->
                    // Baca per byte: Content-Length adalah jumlah byte, bukan
                    // jumlah karakter (body Jepang multi-byte).
                    val input = BufferedInputStream(client.getInputStream())
                    fun readLine(): String {
                        val out = ByteArrayOutputStream()
                        var previous = -1
                        while (true) {
                            val value = input.read()
                            if (value < 0) break
                            if (value == '\n'.code) break
                            if (previous == '\r'.code && value == '\n'.code) break
                            out.write(value)
                            previous = value
                        }
                        return out.toString(Charsets.UTF_8.name()).trimEnd('\r')
                    }

                    val requestLine = readLine()
                    var contentLength = 0
                    while (true) {
                        val line = readLine()
                        if (line.isEmpty()) break
                        if (line.startsWith("Authorization:", ignoreCase = true)) {
                            authorization.set(line.substringAfter(":").trim())
                        }
                        if (line.startsWith("Content-Length:", ignoreCase = true)) {
                            contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                        }
                    }
                    val bodyBytes = ByteArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val count = input.read(bodyBytes, read, contentLength - read)
                        if (count <= 0) break
                        read += count
                    }
                    body.set(String(bodyBytes, 0, read, Charsets.UTF_8))
                    check(requestLine.contains("/chat/completions"))

                    val payload = """{"choices":[{"message":{"content":${JSONObject.quote(content)}}}]}"""
                        .toByteArray(Charsets.UTF_8)
                    val header = "HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\n" +
                        "Content-Type: application/json; charset=utf-8\r\n" +
                        "Content-Length: ${payload.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    val out = client.getOutputStream()
                    out.write(header.toByteArray(Charsets.UTF_8))
                    out.write(payload)
                    out.flush()
                }
            }
        }
    }

    private fun provider() = LlmTranslationProvider(
        sourceTag = "auto",
        targetTag = "id",
        apiKey = "test-key",
        baseUrl = "http://127.0.0.1:$port",
        model = "mock-model"
    )

    @Test
    fun success_mapsTranslationsByRequestIdAndSendsContext() = runBlocking {
        respond(200, "[\"Tunggu!\",\"Jangan!\"]")

        val result = provider().translate(
            listOf(
                TranslationRequest("a", "まって", context = null),
                TranslationRequest("b", "だめだ", context = "まって")
            )
        )

        assertTrue("hasil bukan Success: $result", result is TranslateResult.Success)
        val texts = (result as TranslateResult.Success).texts
        assertEquals("Tunggu!", texts["a"])
        assertEquals("Jangan!", texts["b"])
        assertEquals("Bearer test-key", authorization.get())
        assertTrue("prompt tidak memuat konteks", body.get().contains("まって"))
        assertTrue("prompt tidak memuat item kedua", body.get().contains("だめだ"))
    }

    @Test
    fun markdownWrappedArray_isParsed() = runBlocking {
        respond(200, "```json\n[\"Satu\",\"Dua\"]\n```")

        val result = provider().translate(
            listOf(TranslationRequest("a", "one"), TranslationRequest("b", "two"))
        )

        val texts = (result as TranslateResult.Success).texts
        assertEquals("Satu", texts["a"])
        assertEquals("Dua", texts["b"])
    }

    @Test
    fun httpError_returnsFailureWithoutCrash() = runBlocking {
        respond(401, "unauthorized")

        val result = provider().translate(listOf(TranslationRequest("a", "hello")))

        assertTrue("harus Failure, bukan Success", result is TranslateResult.Failure)
    }

    @Test
    fun emptyKey_failsBeforeNetwork() = runBlocking {
        val result = LlmTranslationProvider(
            sourceTag = "auto",
            targetTag = "id",
            apiKey = ""
        ).translate(listOf(TranslationRequest("a", "hello")))

        assertTrue(result is TranslateResult.Failure)
        assertTrue(authorization.get().isEmpty())
    }
}
