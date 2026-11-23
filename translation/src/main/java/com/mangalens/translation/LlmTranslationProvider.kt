package com.mangalens.translation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Provider terjemahan berbasis LLM lewat API yang kompatibel dengan
 * OpenAI (`POST {baseUrl}/chat/completions`).
 *
 * Kunci API dan model dibaca dari `.env` di root proyek saat build
 * (`LLM_API_KEY`, `LLM_BASE_URL`, `LLM_MODEL`) lalu disimpan di BuildConfig.
 * Provider ini mengirim semua teks satu layar dalam satu permintaan agar hemat
 * token dan hasilnya konsisten, lalu meminta balasan berupa array JSON.
 *
 * Catatan keamanan: kunci ikut tertanam di APK yang dibangun. Jangan bagikan
 * APK hasil build yang memakai kunci asli.
 */
class LlmTranslationProvider(
    sourceTag: String,
    targetTag: String = "id",
    private val apiKey: String = BuildConfig.LLM_API_KEY,
    baseUrl: String = BuildConfig.LLM_BASE_URL,
    private val model: String = BuildConfig.LLM_MODEL
) : TranslationProvider {

    private val endpoint: String = baseUrl.trimEnd('/') + "/chat/completions"
    private val sourceLanguage: String = languageName(sourceTag)
    private val targetLanguage: String = languageName(targetTag)
    private val targetTag: String = targetTag

    override val id: String = "llm:$model"
    override val displayName: String = if (model.isBlank()) "LLM" else "LLM ($model)"

    /** LLM tidak butuh unduhan model; cukup kunci API terisi. */
    override suspend fun prepare(): Boolean = apiKey.isNotBlank()

    override suspend fun translate(requests: List<TranslationRequest>): TranslateResult {
        if (requests.isEmpty()) return TranslateResult.Success(emptyMap())
        if (apiKey.isBlank()) {
            return TranslateResult.Failure(
                "Kunci API LLM belum diisi. Tambahkan LLM_API_KEY di file .env lalu build ulang."
            )
        }

        return try {
            val payload = JSONObject().apply {
                put("model", model)
                put("temperature", 0.4)
                put(
                    "messages",
                    JSONArray().apply {
                        put(
                            JSONObject()
                                .put("role", "system")
                                .put("content", systemPrompt())
                        )
                        put(
                            JSONObject()
                                .put("role", "user")
                                .put("content", userPrompt(requests))
                        )
                    }
                )
            }

            val response = post(payload.toString())
            val content = JSONObject(response)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")

            val texts = parseTexts(content)
            val result = LinkedHashMap<String, String>()
            requests.forEachIndexed { index, request ->
                val translated = texts.getOrNull(index)?.trim().orEmpty()
                result[request.id] = when {
                    translated.isEmpty() -> ""
                    // Perapian ragam hanya untuk bahasa Indonesia.
                    targetTag == "id" -> IndonesianStyle.casual(translated)
                    else -> translated
                }
            }
            TranslateResult.Success(result)
        } catch (t: Throwable) {
            TranslateResult.Failure(t.message ?: "Terjemahan LLM gagal.")
        }
    }

    private fun systemPrompt(): String =
        "Kamu penerjemah manga profesional. Terjemahkan dialog dari $sourceLanguage ke " +
            "$targetLanguage yang luwes, santai, dan wajar seperti percakapan komik. " +
            "Pertahankan gaya bicara, emosi, dan nama tokoh. Jangan menambah atau mengurangi makna. " +
            "Balas HANYA dengan array JSON berisi string terjemahan, urutan dan jumlahnya sama " +
            "dengan input. Contoh: [\"Halo!\",\"Apa kau serius?\"]"

    private fun userPrompt(requests: List<TranslationRequest>): String {
        val array = JSONArray()
        for (request in requests) array.put(request.text)
        return array.toString()
    }

    /** Jaringan selalu di [Dispatchers.IO] agar thread pemanggil tidak diblokir. */
    private suspend fun post(body: String): String = withContext(Dispatchers.IO) {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }

        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException(
                    "LLM menolak permintaan (HTTP $code). ${text.take(160)}"
                )
            }
            text
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    /** Ambil array JSON dari balasan; bila gagal, pakai baris bernomor sebagai cadangan. */
    private fun parseTexts(content: String): List<String> {
        val trimmed = content.trim()
        val start = trimmed.indexOf('[')
        val end = trimmed.lastIndexOf(']')
        if (start >= 0 && end > start) {
            val array = runCatching { JSONArray(trimmed.substring(start, end + 1)) }.getOrNull()
            if (array != null) {
                return (0 until array.length()).map { array.optString(it) }
            }
        }
        return trimmed.lines()
            .map { it.replace(Regex("^\\s*\\d+[.)]\\s*"), "").trim() }
            .filter { it.isNotEmpty() }
    }

    private fun languageName(tag: String): String = when (tag) {
        "id" -> "Indonesia"
        "en" -> "Inggris"
        "ja" -> "Jepang"
        "ko" -> "Korea"
        "zh" -> "Tionghoa"
        "es" -> "Spanyol"
        "fr" -> "Prancis"
        "de" -> "Jerman"
        "pt" -> "Portugis"
        "ru" -> "Rusia"
        "vi" -> "Vietnam"
        "th" -> "Thailand"
        "ms" -> "Melayu"
        "ar" -> "Arab"
        else -> tag.ifBlank { "bahasa asing" }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 60_000
    }
}
