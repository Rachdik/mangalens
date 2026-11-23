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
        targetTag: String
    ): TranslationProvider = when (backend) {
        TranslationBackend.ML_KIT -> MlKitTranslationProvider(sourceTag, targetTag)
        TranslationBackend.LLM -> LlmTranslationProvider(sourceTag, targetTag)
        TranslationBackend.MY_MEMORY -> MyMemoryTranslationProvider(sourceTag, targetTag)
    }
}
