package com.mangalens.translation

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Matriks provider terjemahan pada perangkat (Phase 9J, Task C).
 *
 * Yang diuji: MyMemory (jaringan nyata), ML Kit on-device (model nyata), Gemini
 * dan LLM tanpa kunci (harus gagal dengan pesan, bukan crash), dan fallback ke
 * ML Kit saat provider utama tidak siap.
 *
 * Tidak ada kunci API yang dibaca/ditulis di sini: Gemini/LLM diuji pada kondisi
 * "belum dikonfigurasi", yang justru jalur yang paling sering dialami pengguna.
 */
@RunWith(AndroidJUnit4::class)
class TranslationProviderMatrixTest {

    private val japanese = listOf(
        TranslationRequest("a", "まって"),
        TranslationRequest("b", "どこ？"),
        TranslationRequest("c", "このかわいい人形はなにかしら。")
    )

    @Test
    fun myMemoryRealNetworkJapaneseToIndonesian() = runBlocking {
        val provider = MyMemoryTranslationProvider("ja", "id")
        val ready = provider.prepare()
        val result = provider.translate(japanese)
        println("PROVIDER mymemory ready=$ready result=$result")
        assertTrue("MyMemory harus siap tanpa kunci", ready)
        assertTrue("respons harus Success", result is TranslateResult.Success)
        val texts = (result as TranslateResult.Success).texts
        val filled = texts.values.count { it.isNotBlank() }
        println("PROVIDER mymemory filled=$filled/${texts.size} texts=$texts")
        assertTrue("minimal satu dialog harus diterjemahkan", filled >= 1)
        assertTrue(
            "hasil tidak boleh sama persis dengan sumber",
            texts.values.any { it.isNotBlank() && japanese.none { req -> req.text == it } }
        )
    }

    @Test
    fun myMemoryEmptyInputIsHandled() = runBlocking {
        val provider = MyMemoryTranslationProvider("ja", "id")
        val result = provider.translate(emptyList())
        println("PROVIDER mymemory empty=$result")
        assertTrue(result is TranslateResult.Success)
        assertTrue((result as TranslateResult.Success).texts.isEmpty())
    }

    @Test
    fun mlKitOnDeviceJapaneseToIndonesian() = runBlocking {
        val provider = MlKitTranslationProvider("ja", "id")
        try {
            val ready = provider.prepare()
            println("PROVIDER mlkit ready=$ready")
            assertTrue("model ML Kit ja harus siap (butuh internet sekali)", ready)
            val result = provider.translate(japanese)
            assertTrue("ML Kit harus mengembalikan Success: $result", result is TranslateResult.Success)
            val texts = (result as TranslateResult.Success).texts
            println("PROVIDER mlkit texts=$texts")
            assertTrue("semua dialog harus punya terjemahan", texts.values.all { it.isNotBlank() })
        } finally {
            provider.close()
        }
    }

    @Test
    fun geminiWithoutKeyFailsGracefully() = runBlocking {
        val provider = GeminiTranslationProvider("ja", "id", apiKey = "")
        println("PROVIDER gemini displayName=${provider.displayName} ready=${provider.prepare()}")
        assertTrue("Gemini tanpa kunci tidak boleh siap", !provider.prepare())
        val result = provider.translate(japanese)
        assertTrue("harus Failure, bukan exception", result is TranslateResult.Failure)
        val message = (result as TranslateResult.Failure).message
        println("PROVIDER gemini failure='$message'")
        assertTrue("pesan harus menyebut kunci API", message.contains("kunci", ignoreCase = true))
    }

    @Test
    fun llmWithoutKeyFailsGracefully() = runBlocking {
        val provider = LlmTranslationProvider("ja", "id", apiKey = "")
        println("PROVIDER llm ready=${provider.prepare()}")
        assertTrue("LLM tanpa kunci tidak boleh siap", !provider.prepare())
        val result = provider.translate(japanese)
        assertTrue(result is TranslateResult.Failure)
        val message = (result as TranslateResult.Failure).message
        println("PROVIDER llm failure='$message'")
        assertTrue(message.contains("kunci", ignoreCase = true))
    }

    @Test
    fun fallbackUsesMlKitWhenPrimaryHasNoKey() = runBlocking {
        val primary = GeminiTranslationProvider("ja", "id", apiKey = "")
        val fallback = MlKitTranslationProvider("ja", "id")
        val provider = FallbackTranslationProvider(primary, fallback)
        try {
            val result = provider.translate(japanese)
            println("PROVIDER fallback result=$result")
            assertTrue("fallback harus menghasilkan terjemahan", result is TranslateResult.Success)
            val texts = (result as TranslateResult.Success).texts
            assertTrue(texts.values.all { it.isNotBlank() })
        } finally {
            fallback.close()
        }
    }
}
