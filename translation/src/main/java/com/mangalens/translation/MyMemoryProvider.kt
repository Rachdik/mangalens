package com.mangalens.translation

import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Provider terjemahan gratis berbasis MyMemory API (api.mymemory.translated.net).
 *
 * KARAKTERISTIK:
 * - Gratis tanpa API key (ada batas 1000 karakter/layanan gratis, 5000 dengan key)
 * - Tidak perlu konfigurasi khusus, langsung jalan
 * - Kualitas lebih baik dari ML Kit untuk beberapa bahasa, lebih buruk untuk lainnya
 * - Terkadang timeout atau rate-limited pada penggunaan intensif
 * - Tidak ada jaminan uptime atau ketersediaan jangka panjang
 *
 * STATUS: Eksperimental. Disarankan untuk mencoba terlebih dahulu, dan bila hasil
 * tidak memuaskan, kembali ke ML Kit atau LLM.
 *
 * CATATAN KEAMANAN: Tidak ada API key yang dikirim, jadi tidak ada risiko kebocoran.
 * Namun ini juga berarti batas penggunaan lebih ketat.
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

    override suspend fun translate(requests: List<TranslationRequest>): TranslateResult {
        if (requests.isEmpty()) return TranslateResult.Success(emptyMap())

        val results = LinkedHashMap<String, String>()
        val batchSize = 5 // MyMemory API menerima beberapa teks sekaligus

        for (batchStart in requests.indices step batchSize) {
            val batch = requests.subList(batchStart, minOf(batchStart + batchSize, requests.size))
            val batchText = batch.joinToString("\n") { it.text }

            try {
                val translated = translateBatch(batchText) ?: continue

                // Split kembali sesuai jumlah request
                val parts = translated.split("\n", limit = batch.size + 1)
                batch.forEachIndexed { index, request ->
                    if (index < parts.size) {
                        results[request.id] = parts[index].trim()
                    }
                }
            } catch (e: Exception) {
                // Jika batch gagal, tandai semua sebagai gagal
                batch.forEach { results[it.id] = "<gagal: ${e.message}>" }
            }
        }

        return TranslateResult.Success(results)
    }

    private fun translateBatch(text: String): String? {
        val encodedText = URLEncoder.encode(text, StandardCharsets.UTF_8.toString())
        val encodedLangPair = URLEncoder.encode("$sourceLanguage|$targetLanguage", StandardCharsets.UTF_8.toString())

        val url = "https://api.mymemory.translated.net/get?q=$encodedText&langpair=$encodedLangPair"

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "MangaLens/1.0")
        }

        return try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IllegalStateException("MyMemory menolak (HTTP $responseCode)")
            }

            val response = connection.inputStream.bufferedReader().readText()
            val json = org.json.JSONObject(response)

            // MyMemory response structure: {"responseData":{"translatedText":"..."},"responseStatus":200}
            val responseData = json.optJSONObject("responseData") ?: return null
            responseData.getString("translatedText")
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Petakan tag bahasa ke kode yang dipakai MyMemory.
     * MyMemory menggunakan kode ISO 639-1 (2 huruf).
     */
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
        else -> "en" // Fallback ke Inggris
    }
}
