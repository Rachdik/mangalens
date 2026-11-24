package com.mangalens.translation

/**
 * Titik tunggal pembuatan provider terjemahan.
 *
 * Reader dan Share Receiver sama-sama memakai factory ini supaya:
 * - pemetaan [TranslationBackend] → implementasi tidak pernah berbeda antar layar,
 * - penambahan provider baru cukup diubah di satu tempat,
 * - tidak ada jalur OCR/terjemahan kedua yang "hampir sama" tetapi menyimpang.
 */
object TranslationProviders {

    fun create(
        backend: TranslationBackend,
        sourceTag: String,
        targetTag: String,
        llmConfig: LlmConfig = LlmConfig(),
        autoFallback: Boolean = false
    ): TranslationProvider {
        val primary: TranslationProvider = when (backend) {
            TranslationBackend.ML_KIT -> MlKitTranslationProvider(sourceTag, targetTag)
            TranslationBackend.LLM -> LlmTranslationProvider(
                sourceTag,
                targetTag,
                apiKey = llmConfig.apiKey,
                baseUrl = llmConfig.baseUrl,
                model = llmConfig.model,
                timeoutMs = llmConfig.timeoutMs
            )

            TranslationBackend.GEMINI -> GeminiTranslationProvider(
                sourceTag,
                targetTag,
                apiKey = llmConfig.apiKey,
                model = llmConfig.model.ifBlank { "gemini-1.5-flash" },
                timeoutMs = llmConfig.timeoutMs
            )

            TranslationBackend.MY_MEMORY -> MyMemoryTranslationProvider(sourceTag, targetTag)
        }

        if (!autoFallback || backend == TranslationBackend.ML_KIT) return primary
        return FallbackTranslationProvider(
            primary = primary,
            fallback = MlKitTranslationProvider(sourceTag, targetTag)
        )
    }
}
