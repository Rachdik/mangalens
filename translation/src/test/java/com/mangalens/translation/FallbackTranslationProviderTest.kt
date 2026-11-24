package com.mangalens.translation

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fallback tidak boleh membiarkan halaman kosong saat provider utama gagal. */
class FallbackTranslationProviderTest {

    private class FakeProvider(
        override val id: String,
        override val displayName: String,
        private val ready: Boolean = true,
        private val result: TranslateResult
    ) : TranslationProvider {
        var calls = 0
        override suspend fun prepare(): Boolean = ready
        override suspend fun translate(requests: List<TranslationRequest>): TranslateResult {
            calls++
            return result
        }
    }

    private val request = TranslationRequest(id = "b1", text = "まって")

    @Test
    fun `uses primary when it succeeds`() = runBlocking {
        val primary = FakeProvider("llm", "LLM", result = TranslateResult.Success(mapOf("b1" to "Tunggu")))
        val fallback = FakeProvider("mlkit", "ML Kit", result = TranslateResult.Success(mapOf("b1" to "Menunggu")))
        val provider = FallbackTranslationProvider(primary, fallback)

        val result = provider.translate(listOf(request))

        assertTrue(result is TranslateResult.Success)
        assertEquals("Tunggu", (result as TranslateResult.Success).texts["b1"])
        assertEquals(1, primary.calls)
        assertEquals(0, fallback.calls)
    }

    @Test
    fun `falls back when primary fails`() = runBlocking {
        val primary = FakeProvider(
            "llm",
            "LLM",
            result = TranslateResult.Failure("HTTP 429 kuota habis")
        )
        val fallback = FakeProvider(
            "mlkit",
            "ML Kit",
            result = TranslateResult.Success(mapOf("b1" to "Tunggu"))
        )
        val provider = FallbackTranslationProvider(primary, fallback)

        val result = provider.translate(listOf(request))

        assertEquals("Tunggu", (result as TranslateResult.Success).texts["b1"])
        assertEquals(1, fallback.calls)
    }

    @Test
    fun `falls back when primary is not configured`() = runBlocking {
        val primary = FakeProvider(
            "llm",
            "LLM",
            ready = false,
            result = TranslateResult.Failure("kunci kosong")
        )
        val fallback = FakeProvider(
            "mlkit",
            "ML Kit",
            result = TranslateResult.Success(mapOf("b1" to "Tunggu"))
        )
        val provider = FallbackTranslationProvider(primary, fallback)

        val result = provider.translate(listOf(request))

        assertTrue(result is TranslateResult.Success)
        assertEquals(0, primary.calls)
        assertEquals(1, fallback.calls)
    }

    @Test
    fun `reports failure when both providers fail`() = runBlocking {
        val primary = FakeProvider("llm", "LLM", result = TranslateResult.Failure("gagal"))
        val fallback = FakeProvider("mlkit", "ML Kit", result = TranslateResult.Failure("gagal juga"))
        val provider = FallbackTranslationProvider(primary, fallback)

        val result = provider.translate(listOf(request))

        assertTrue(result is TranslateResult.Failure)
    }

    @Test
    fun `llm presets use distinct openai-compatible endpoints`() {
        assertEquals("https://api.openai.com/v1", LlmPreset.OPENAI.baseUrl)
        assertEquals("https://openrouter.ai/api/v1", LlmPreset.OPENROUTER.baseUrl)
        assertTrue(LlmPreset.DEEPSEEK.baseUrl.endsWith("/v1"))
        assertEquals(LlmPreset.OPENAI, LlmPreset.fromKey("tidak-dikenal"))
    }
}
