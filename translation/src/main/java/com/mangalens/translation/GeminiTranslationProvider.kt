package com.mangalens.translation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Adapter Google Gemini. Format request/response berbeda dari OpenAI-compatible,
 * sehingga tidak dipaksakan lewat LlmTranslationProvider.
 */
class GeminiTranslationProvider(
    sourceTag: String,
    targetTag: String = "id",
    private val apiKey: String = "",
    private val model: String = "gemini-1.5-flash",
    private val timeoutMs: Int = LlmConfig.DEFAULT_TIMEOUT_MS
) : TranslationProvider {

    private val sourceLanguage: String =
        if (SourceLanguage.isAuto(sourceTag)) "bahasa asli dialog (deteksi otomatis)"
        else languageName(SourceLanguage.normalize(sourceTag))
    private val targetLanguage: String = languageName(targetTag)
    private val targetTag: String = targetTag

    override val id: String = "gemini:$model"
    override val displayName: String = if (model.isBlank()) "Gemini" else "Gemini ($model)"

    override suspend fun prepare(): Boolean = apiKey.isNotBlank()

    override suspend fun translate(requests: List<TranslationRequest>): TranslateResult {
        if (requests.isEmpty()) return TranslateResult.Success(emptyMap())
        if (apiKey.isBlank()) {
            return TranslateResult.Failure("Kunci API Gemini belum diisi.")
        }

        return try {
            val prompt = buildPrompt(requests)
            val payload = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().put("text", prompt))
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.4)
                })
            }

            val response = post(payload.toString())
            val json = JSONObject(response)
            val content = json.optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text")
                .orEmpty()

            val usage = parseUsage(json)
            val texts = LlmResponseParser.parse(content)
            val result = LinkedHashMap<String, String>()
            requests.forEachIndexed { index, request ->
                val translated = texts.getOrNull(index)?.trim().orEmpty()
                result[request.id] = when {
                    translated.isEmpty() -> ""
                    targetTag == "id" -> IndonesianStyle.casual(translated)
                    else -> translated
                }
            }
            TranslateResult.Success(result, usage)
        } catch (t: Throwable) {
            TranslateResult.Failure(t.message ?: "Terjemahan Gemini gagal.")
        }
    }

    suspend fun fetchModels(): List<String> {
        if (apiKey.isBlank()) return emptyList()
        return withContext(Dispatchers.IO) {
            runCatching {
                val url = "https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey"
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 20_000
                    readTimeout = 30_000
                    setRequestProperty("Accept", "application/json")
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
                connection.disconnect()
                if (code !in 200..299) return@runCatching emptyList()
                val models = JSONObject(text).optJSONArray("models") ?: return@runCatching emptyList()
                (0 until models.length()).mapNotNull { index ->
                    models.optJSONObject(index)?.optString("name")?.removePrefix("models/")
                }.filter { it.isNotBlank() }
            }.getOrDefault(emptyList())
        }
    }

    private fun buildPrompt(requests: List<TranslationRequest>): String {
        val array = org.json.JSONArray()
        for (request in requests) {
            val item = JSONObject().put("text", request.text)
            if (!request.context.isNullOrBlank()) item.put("context", request.context)
            array.put(item)
        }
        return "Kamu penerjemah manga profesional. Terjemahkan dialog dari $sourceLanguage ke " +
            "$targetLanguage yang luwes, santai, dan wajar seperti percakapan komik. " +
            "Pertahankan gaya bicara, emosi, dan nama tokoh. Jangan menambah atau mengurangi makna. " +
            "Setiap item input adalah satu dialog terpisah. Konteks hanya untuk konsistensi: " +
            "jangan menerjemahkan konteks dan jangan menggabungkan hasil antar-item. " +
            "Balas HANYA dengan array JSON berisi string terjemahan, urutan dan jumlahnya sama " +
            "dengan input. Input: ${array}"
    }

    private fun parseUsage(json: JSONObject): TokenUsage? {
        val usage = json.optJSONObject("usageMetadata") ?: return null
        return TokenUsage(
            inputTokens = usage.optInt("promptTokenCount").takeIf { usage.has("promptTokenCount") },
            outputTokens = usage.optInt("candidatesTokenCount").takeIf { usage.has("candidatesTokenCount") },
            totalTokens = usage.optInt("totalTokenCount").takeIf { usage.has("totalTokenCount") }
        )
    }

    private suspend fun post(body: String): String = withContext(Dispatchers.IO) {
        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            val timeout = timeoutMs.coerceIn(LlmConfig.MIN_TIMEOUT_MS, LlmConfig.MAX_TIMEOUT_MS)
            connectTimeout = minOf(20_000, timeout)
            readTimeout = timeout
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("Gemini menolak permintaan (HTTP $code). ${text.take(160)}")
            }
            text
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private fun languageName(tag: String): String = when (tag) {
        "id" -> "Indonesia"
        "en" -> "Inggris"
        "auto" -> "bahasa asli dialog (deteksi otomatis)"
        "ja" -> "Jepang"
        "ko" -> "Korea"
        "zh" -> "Tionghoa"
        else -> tag.ifBlank { "bahasa asing" }
    }
}
