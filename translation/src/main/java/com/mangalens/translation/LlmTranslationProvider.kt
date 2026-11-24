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
 * Kunci API diberikan saat runtime dari [LlmConfig] (penyimpanan terenkripsi
 * yang diisi pengguna), bukan dari source code/BuildConfig/APK. Base URL dan
 * model boleh diubah pengguna.
 *
 * Provider ini mengirim semua teks satu layar dalam satu permintaan agar hemat
 * token dan hasilnya konsisten, lalu meminta balasan berupa array JSON.
 */
class LlmTranslationProvider(
    sourceTag: String,
    targetTag: String = "id",
    private val apiKey: String = "",
    private val baseUrl: String = BuildConfig.LLM_BASE_URL,
    private val model: String = BuildConfig.LLM_MODEL,
    private val timeoutMs: Int = LlmConfig.DEFAULT_TIMEOUT_MS
) : TranslationProvider {

    private val endpoint: String = baseUrl.trimEnd('/') + "/chat/completions"
    private val sourceLanguage: String =
        if (SourceLanguage.isAuto(sourceTag)) {
            "bahasa asli dialog (deteksi otomatis; jangan asumsikan Inggris)"
        } else {
            languageName(SourceLanguage.normalize(sourceTag))
        }
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
                "Kunci API LLM belum diisi. Buka pengaturan provider lalu simpan kunci."
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

            val usage = parseUsage(response)
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
            TranslateResult.Success(result, usage)
        } catch (t: Throwable) {
            TranslateResult.Failure(t.message ?: "Terjemahan LLM gagal.")
        }
    }

    private fun systemPrompt(): String =
        "Kamu penerjemah manga profesional. Terjemahkan dialog dari $sourceLanguage ke " +
            "$targetLanguage yang luwes, santai, dan wajar seperti percakapan komik. " +
            "Pertahankan gaya bicara, emosi, dan nama tokoh. Jangan menambah atau mengurangi makna. " +
            "Setiap item input adalah satu dialog terpisah. Konteks hanya untuk konsistensi: " +
            "jangan menerjemahkan konteks dan jangan menggabungkan hasil antar-item. " +
            "Balas HANYA dengan array JSON berisi string terjemahan, urutan dan jumlahnya sama " +
            "dengan input. Contoh: [\"Halo!\",\"Apa kau serius?\"]"

    private fun userPrompt(requests: List<TranslationRequest>): String {
        val array = JSONArray()
        for (request in requests) {
            val item = JSONObject().put("text", request.text)
            if (!request.context.isNullOrBlank()) item.put("context", request.context)
            array.put(item)
        }
        return array.toString()
    }

    /** Jaringan selalu di [Dispatchers.IO] agar thread pemanggil tidak diblokir. */
    private suspend fun post(body: String): String = withContext(Dispatchers.IO) {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            val timeout = timeoutMs.coerceIn(LlmConfig.MIN_TIMEOUT_MS, LlmConfig.MAX_TIMEOUT_MS)
            connectTimeout = minOf(CONNECT_TIMEOUT_MS, timeout)
            readTimeout = timeout
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

    private fun parseTexts(content: String): List<String> = LlmResponseParser.parse(content)

    private fun parseUsage(response: String): TokenUsage? {
        return runCatching {
            val json = JSONObject(response)
            val usage = json.optJSONObject("usage") ?: json.optJSONObject("usageMetadata") ?: return null
            TokenUsage(
                inputTokens = usage.optInt("prompt_tokens").takeIf { usage.has("prompt_tokens") }
                    ?: usage.optInt("promptTokenCount").takeIf { usage.has("promptTokenCount") },
                outputTokens = usage.optInt("completion_tokens").takeIf { usage.has("completion_tokens") }
                    ?: usage.optInt("candidatesTokenCount").takeIf { usage.has("candidatesTokenCount") },
                totalTokens = usage.optInt("total_tokens").takeIf { usage.has("total_tokens") }
                    ?: usage.optInt("totalTokenCount").takeIf { usage.has("totalTokenCount") }
            )
        }.getOrNull()
    }

    suspend fun fetchModels(): List<String> {
        if (apiKey.isBlank()) return emptyList()
        return withContext(Dispatchers.IO) {
            runCatching {
                val url = baseUrl.trimEnd('/') + "/models"
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 20_000
                    readTimeout = 30_000
                    setRequestProperty("Authorization", "Bearer $apiKey")
                    setRequestProperty("Accept", "application/json")
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
                connection.disconnect()
                if (code !in 200..299) return@runCatching emptyList()
                val json = JSONObject(text)
                val data = json.optJSONArray("data")
                if (data != null) {
                    (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("id") }.filter { it.isNotBlank() }
                } else {
                    json.optJSONArray("models")?.let { arr ->
                        (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("id") ?: arr.optString(it) }.filter { it.isNotBlank() }
                    } ?: emptyList()
                }
            }.getOrDefault(emptyList())
        }
    }

    private fun languageName(tag: String): String = when (tag) {
        "id" -> "Indonesia"
        "en" -> "Inggris"
        "auto" -> "bahasa asli dialog (deteksi otomatis)"
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
