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
 * Provider terjemahan yang bisa diganti (Google gratis, Google Cloud,
 * OpenAI-compatible). Implementasi masuk pada Tahap 5.
 */
interface TranslationProvider {
    val id: String
    val displayName: String

    suspend fun translate(requests: List<TranslationRequest>): TranslateResult
}
