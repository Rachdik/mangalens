package com.mangalens.translation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Provider terjemahan gratis berbasis MyMemory API (api.mymemory.translated.net).
 *
 * KARAKTERISTIK:
 * - Gratis tanpa API key (batas ~1000 karakter/hari per IP tanpa key)
 * - Tidak perlu konfigurasi khusus, langsung jalan
 * - Kualitas bervariasi; terkadang timeout atau rate-limited
 * - Tidak ada jaminan uptime
 *
 * STATUS: Eksperimental. Bila hasil tidak memuaskan, kembali ke ML Kit atau LLM.
 *
 * CATATAN KEAMANAN: tidak ada API key yang dikirim, jadi tidak ada risiko
 * kebocoran kunci. Batas penggunaan karena itu lebih ketat.
 */
class MyMemoryTranslationProvider(
    sourceTag: String,
    targetTag: String = "id"
) : TranslationProvider {

    private val sourceLanguage: String = languageCodeFor(sourceTag)
    private val targetLanguage: String = languageCodeFor(targetTag)

    override val id: String = "mymemory:$sourceLanguage>$targetLanguage"
    override val displayName: String = "MyMemory (gratis)"

    override suspend fun prepare(): Boolean = true

    /**
     * Semua panggilan jaringan dijalankan di [Dispatchers.IO]. Sebelumnya
     * HttpURLConnection yang memblokir dijalankan di thread pemanggil
     * (termasuk Dispatchers.Default), sehingga bisa menahan thread komputasi.
     */
    override suspend fun translate(requests: List<TranslationRequest>): TranslateResult =
        withContext(Dispatchers.IO) {
            if (requests.isEmpty()) return@withContext TranslateResult.Success(emptyMap())

            val results = LinkedHashMap<String, String>()

            for (batchStart in requests.indices step BATCH_SIZE) {
                val batch = requests.subList(
                    batchStart,
                    minOf(batchStart + BATCH_SIZE, requests.size)
                )

                val batchResult = runCatching { translateBatch(buildBatchText(batch)) }
                if (batchResult.isSuccess) {
                    val parts = (batchResult.getOrNull() ?: "")
                        .split("\n", limit = batch.size + 1)
                    batch.forEachIndexed { index, request ->
                        val value = parts.getOrNull(index)?.trim().orEmpty()
                        results[request.id] = value
                    }
                } else {
                    // Kosongkan hasil yang gagal agar pipeline tidak menempelkan
                    // pesan error internal sebagai "terjemahan".
                    batch.forEach { results[it.id] = "" }
                }
            }

            TranslateResult.Success(results)
        }

    /**
     * MyMemory memecah teks berdasarkan newline, jadi satu baris per balon
     * menghasilkan pemetaan yang deterministik.
     */
    private fun buildBatchText(batch: List<TranslationRequest>): String =
        batch.joinToString("\n") { it.text.replace("\n", " ").trim() }

    private fun translateBatch(text: String): String {
        var lastError: Throwable? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                return requestOnce(text)
            } catch (e: IOException) {
                lastError = e
                if (attempt < MAX_ATTEMPTS - 1) {
                    Thread.sleep(RETRY_DELAY_MS) // sudah di Dispatchers.IO
                }
            }
        }
        throw lastError ?: IllegalStateException("MyMemory gagal")
    }

    private fun requestOnce(text: String): String {
        val encodedText = URLEncoder.encode(text, StandardCharsets.UTF_8.toString())
        val encodedLangPair = URLEncoder.encode(
            "$sourceLanguage|$targetLanguage",
            StandardCharsets.UTF_8.toString()
        )

        val url = "https://api.mymemory.translated.net/get" +
            "?q=$encodedText&langpair=$encodedLangPair"

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "MangaLens/1.0")
        }

        return try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IOException("MyMemory menolak (HTTP $responseCode)")
            }
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val json = org.json.JSONObject(response)
            val responseData = json.optJSONObject("responseData")
                ?: throw IOException("MyMemory: responseData kosong")
            responseData.optString("translatedText").orEmpty()
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    /** Petakan tag bahasa ke kode ISO 639-1 yang dipakai MyMemory. */
    private fun languageCodeFor(tag: String): String = when (tag.lowercase()) {
        "auto", "en" -> "en"
        "id" -> "id"
        "ja" -> "ja"
        "ko" -> "ko"
        "zh", "zh-cn" -> "zh"
        "es" -> "es"
        "fr" -> "fr"
        "de" -> "de"
        "pt" -> "pt"
        "ru" -> "ru"
        "vi" -> "vi"
        "th" -> "th"
        "ms" -> "ms"
        "ar" -> "ar"
        else -> "en"
    }

    private companion object {
        const val BATCH_SIZE = 5
        const val MAX_ATTEMPTS = 2
        const val RETRY_DELAY_MS = 400L
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
    }
}
