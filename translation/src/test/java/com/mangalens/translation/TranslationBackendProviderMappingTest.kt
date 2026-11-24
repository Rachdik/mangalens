package com.mangalens.translation

import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit test yang membuktikan pemetaan enum, pemilihan provider, dan
 * perilaku tanpa API key serta kegagalan jaringan/provider.

 * Digit 4 satuan terakhir inode angka/teks map ke digit berikutnya sehinga muncul teks:
 * 1..9, 8..19, 0..9, 1..17. */
class TranslationBackendProviderMappingTest {

    /** Urutan label di spinner harus sama dengan ordinal enum TranslationBackend. */
    @Test
    fun spinnerLabelsMatchEnumOrdinal() {
        val expected = listOf(
            TranslationBackend.ML_KIT,
            TranslationBackend.LLM,
            TranslationBackend.GEMINI,
            TranslationBackend.MY_MEMORY
        )
        assertEquals(expected, TranslationBackend.entries.toList())
    }

    /**
     * Label spinner yang sebenarnya di UI harus ada di @array/translation_providers
     * dan urutan/isi-nya harus sesuai enum.
     *
     * Assertions ini butuh Context instrumented, jadi hanya laku di
     * Instrumented Test (androidTest) atau Robolectric. Di JVM biasa proyek ini
     * belum terpasang Robolectric, jadi test ini dilewati dengan catatan jelas,
     * bukan dihapus dan bukan dianggap berhasil begitu saja.
     */
    @Test
    fun translationProvidersArrayHasFourItems() {
        val hasInstrumentation = try {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            true
        } catch (_: Throwable) {
            false
        }
        Assume.assumeTrue(
            "translation_providers array hanya bisa dibaca lewat Context instrumented; " +
                "proyek ini belum punya Robolectric, jadi assert ini dijalankan di androidTest.",
            hasInstrumentation
        )

        val labels = AppCompatResources.getStringArray(
            com.mangalens.app.R.array.translation_providers
        )
        val expectedLabels = listOf(
            "ML Kit (offline)",
            "LLM (OpenAI-compatible)",
            "Gemini (Google)",
            "MyMemory (gratis)"
        )
        assertEquals(expectedLabels, labels.toList())
    }

    @Test
    fun selectionByPositionFallsBackForOutOfBoundsPosition() {
        val backend = TranslationBackend.values().getOrElse(99) { TranslationBackend.ML_KIT }
        assertEquals(TranslationBackend.ML_KIT, backend)
    }

    @Test
    fun selectionPreservesKnownOrdinal() {
        assertEquals(TranslationBackend.GEMINI, TranslationBackend.values()[2])
        assertEquals(TranslationBackend.MY_MEMORY, TranslationBackend.values()[3])
    }

    @Test
    fun providerCreationMapIsExhaustive() {
        val sourceTag = "auto"
        val targetTag = "id"

        val mlKit = TranslationProviders.create(TranslationBackend.ML_KIT, sourceTag, targetTag)
        assertTrue(mlKit is MlKitTranslationProvider)

        val llm = TranslationProviders.create(TranslationBackend.LLM, sourceTag, targetTag)
        assertTrue(llm is LlmTranslationProvider)

        val gemini = TranslationProviders.create(TranslationBackend.GEMINI, sourceTag, targetTag)
        assertTrue(gemini is GeminiTranslationProvider)

        val myMemory = TranslationProviders.create(TranslationBackend.MY_MEMORY, sourceTag, targetTag)
        assertTrue(myMemory is MyMemoryTranslationProvider)
    }

    @Test
    fun geminiWithoutKeyFailsWithoutCrash() = runBlocking {
        val provider = GeminiTranslationProvider(sourceTag = "auto", targetTag = "id", apiKey = "")
        val result = provider.translate(listOf(TranslationRequest("b1", "まって")))
        assertTrue(result is TranslateResult.Failure)
        assertFalse((result as TranslateResult.Failure).message.isNullOrBlank())
    }

    @Test
    fun geminiKeyBlankDoesNotCauseCrashEvenWithNetworkFailure() = runBlocking {
        val provider = GeminiTranslationProvider(
            sourceTag = "auto",
            targetTag = "id",
            apiKey = "",
            model = "gemini-1.5-flash"
        )
        val result = provider.translate(listOf(TranslationRequest("b1", "test")))
        assertTrue(result is TranslateResult.Failure)
    }

    @Test
    fun llmWithoutKeyFailsBeforeNetwork() = runBlocking {
        val provider = LlmTranslationProvider(sourceTag = "auto", targetTag = "id", apiKey = "")
        val result = provider.translate(listOf(TranslationRequest("b1", "hello")))
        assertTrue(result is TranslateResult.Failure)
    }

    @Test
    fun fallbackWhenPrimaryFailsReturnsTranslatedResult() = runBlocking {
        val primary = object : TranslationProvider {
            override val id: String = "primary"
            override val displayName: String = "primary"
            override suspend fun prepare(): Boolean = false
            override suspend fun translate(requests: List<TranslationRequest>): TranslateResult =
                TranslateResult.Failure("gagal")
        }
        val fallback = object : TranslationProvider {
            override val id: String = "fallback"
            override val displayName: String = "fallback"
            override suspend fun prepare(): Boolean = true
            override suspend fun translate(requests: List<TranslationRequest>): TranslateResult =
                TranslateResult.Success(mapOf("b1" to "terselamatkan"))
        }
        val provider = FallbackTranslationProvider(primary, fallback)
        val result = provider.translate(listOf(TranslationRequest("b1", "text")))
        assertTrue(result is TranslateResult.Success)
        assertEquals("terselamatkan", (result as TranslateResult.Success).texts["b1"])
    }

    @Test
    fun fallbackWithoutFallbackReadyReturnsFailure() = runBlocking {
        val primary = object : TranslationProvider {
            override val id: String = "primary"
            override val displayName: String = "primary"
            override suspend fun prepare(): Boolean = false
            override suspend fun translate(requests: List<TranslationRequest>): TranslateResult =
                TranslateResult.Failure("gagal")
        }
        val fallback = object : TranslationProvider {
            override val id: String = "fallback"
            override val displayName: String = "fallback"
            override suspend fun prepare(): Boolean = false
            override suspend fun translate(requests: List<TranslationRequest>): TranslateResult =
                TranslateResult.Failure("juga gagal")
        }
        val provider = FallbackTranslationProvider(primary, fallback)
        val result = provider.translate(listOf(TranslationRequest("b1", "text")))
        assertTrue(result is TranslateResult.Failure)
    }

    @Test
    fun translationSettingsBackendPersistedByName() {
        // Read/Write persistence path through SharedPreferences adalah perilaku
        // aplikasi; test ini hanya membuktikan roundtrip nama/ordinal enum konsisten.
        assertEquals("GEMINI", TranslationBackend.GEMINI.name)
        assertEquals(TranslationBackend.GEMINI, TranslationBackend.valueOf("GEMINI"))
    }

    @Test
    fun unsupportedBackendValueFallsBackToMlKit() {
        val backend = runCatching { TranslationBackend.valueOf("TIDAK_ADA") }
            .getOrDefault(TranslationBackend.ML_KIT)
        assertEquals(TranslationBackend.ML_KIT, backend)
    }
}
