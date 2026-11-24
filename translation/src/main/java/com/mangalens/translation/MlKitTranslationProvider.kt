package com.mangalens.translation

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Provider terjemahan on-device (ML Kit). Tidak butuh API key; model untuk
 * pasangan bahasa diunduh sekali lewat [prepare] atau saat pertama dipakai.
 *
 * Penting: ML Kit tidak menerima "auto". Versi lama memetakan "auto" ke Inggris,
 * sehingga dialog Jepang diterjemahkan memakai model Inggris. Sekarang setiap
 * dialog dideteksi per skrip dan memakai model sumber yang sesuai.
 *
 * Panggil [close] setelah selesai agar resource model dilepas.
 */
class MlKitTranslationProvider(
    sourceTag: String,
    targetTag: String = "id"
) : TranslationProvider {

    private val explicitSource: String? =
        if (SourceLanguage.isAuto(sourceTag)) {
            null
        } else {
            TranslateLanguage.fromLanguageTag(SourceLanguage.normalize(sourceTag))
        }

    private val targetLanguage: String =
        TranslateLanguage.fromLanguageTag(SourceLanguage.normalize(targetTag))
            ?: TranslateLanguage.INDONESIAN

    private val translators = ConcurrentHashMap<String, Translator>()
    private val modelsReady = ConcurrentHashMap.newKeySet<String>()
    private val loggedSources = ConcurrentHashMap.newKeySet<String>()

    /** Bahasa yang benar-benar dipakai per request, untuk diagnostik UI/log. */
    private val usedSources = ConcurrentHashMap<String, String>()

    override val id: String =
        "mlkit:${explicitSource ?: SourceLanguage.AUTO}>$targetLanguage"
    override val displayName: String = "ML Kit on-device"

    override suspend fun prepare(): Boolean = runCatching {
        val source = explicitSource ?: return@runCatching true
        translatorFor(source).downloadModelIfNeeded().awaitResult()
        modelsReady.add(source)
        true
    }.getOrDefault(false)

    override suspend fun translate(requests: List<TranslationRequest>): TranslateResult {
        val results = LinkedHashMap<String, String>()
        return try {
            for (request in requests) {
                val text = request.text.trim()
                if (text.isEmpty()) {
                    results[request.id] = ""
                    continue
                }

                val source = explicitSource ?: languageFor(text)
                usedSources[request.id] = source
                if (source == targetLanguage) {
                    // Jangan menerjemahkan bahasa yang sama dengan target.
                    results[request.id] = text
                    continue
                }

                val translator = translatorFor(source)
                if (!modelsReady.contains(source)) {
                    translator.downloadModelIfNeeded().awaitResult()
                    modelsReady.add(source)
                }
                if (loggedSources.add("$source>$targetLanguage")) {
                    Log.d(TAG, "model aktif source=$source target=$targetLanguage")
                }
                results[request.id] = IndonesianStyle.casual(
                    translator.translate(text).awaitResult()
                )
            }
            TranslateResult.Success(results)
        } catch (t: Throwable) {
            TranslateResult.Failure(t.message ?: "Terjemahan gagal")
        }
    }

    /** Tag bahasa yang dipakai untuk satu request (kosong bila belum diproses). */
    fun usedSourceFor(requestId: String): String? = usedSources[requestId]

    private fun languageFor(text: String): String {
        val tag = SourceLanguage.detect(text)
        return TranslateLanguage.fromLanguageTag(tag) ?: TranslateLanguage.ENGLISH
    }

    private fun translatorFor(source: String): Translator =
        translators.getOrPut(source) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(source)
                    .setTargetLanguage(targetLanguage)
                    .build()
            )
        }

    fun close() {
        translators.values.forEach { runCatching { it.close() } }
        translators.clear()
        modelsReady.clear()
    }

    private companion object {
        const val TAG = "MangaLensMT"
    }
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { if (cont.isActive) cont.resume(it) }
    addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
}
