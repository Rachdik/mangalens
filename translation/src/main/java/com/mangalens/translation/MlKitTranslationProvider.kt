package com.mangalens.translation

import com.google.android.gms.tasks.Task
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Provider terjemahan on-device (ML Kit). Tidak butuh API key; model untuk
 * pasangan bahasa diunduh sekali lewat [prepare] lalu dipakai offline.
 *
 * Panggil [close] setelah selesai agar resource model dilepas.
 */
class MlKitTranslationProvider(
    sourceTag: String,
    targetTag: String = "id"
) : TranslationProvider {

    private val sourceLanguage: String =
        TranslateLanguage.fromLanguageTag(sourceTag) ?: TranslateLanguage.JAPANESE

    private val targetLanguage: String =
        TranslateLanguage.fromLanguageTag(targetTag) ?: TranslateLanguage.INDONESIAN

    private val translator: Translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(sourceLanguage)
            .setTargetLanguage(targetLanguage)
            .build()
    )

    override val id: String = "mlkit:$sourceLanguage>$targetLanguage"
    override val displayName: String = "ML Kit on-device"

    /** @return true bila model siap dipakai (terunduh atau sudah ada). */
    override suspend fun prepare(): Boolean = runCatching {
        translator.downloadModelIfNeeded().awaitResult()
        true
    }.getOrDefault(false)

    override suspend fun translate(requests: List<TranslationRequest>): TranslateResult {
        val results = LinkedHashMap<String, String>()
        return try {
            for (request in requests) {
                val text = request.text.trim()
                results[request.id] = if (text.isEmpty()) "" else translator.translate(text).awaitResult()
            }
            TranslateResult.Success(results)
        } catch (t: Throwable) {
            TranslateResult.Failure(t.message ?: "Terjemahan gagal")
        }
    }

    fun close() {
        runCatching { translator.close() }
    }
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { if (cont.isActive) cont.resume(it) }
    addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
}
