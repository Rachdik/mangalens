package com.mangalens.translation

data class TranslationRequest(
    val id: String,
    val text: String
)

sealed interface TranslateResult {
    data class Success(val texts: Map<String, String>) : TranslateResult
    data class Failure(val message: String) : TranslateResult
}

/**
 * Provider terjemahan yang bisa diganti (ML Kit on-device, Google Cloud,
 * OpenAI-compatible). Implementasi saat ini: [MlKitTranslationProvider].
 */
interface TranslationProvider {
    val id: String
    val displayName: String

    /**
     * Menyiapkan provider (mis. mengunduh model). Default: tidak ada
     * persiapan khusus.
     */
    suspend fun prepare(): Boolean = true

    suspend fun translate(requests: List<TranslationRequest>): TranslateResult
}
