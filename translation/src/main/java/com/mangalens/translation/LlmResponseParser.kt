package com.mangalens.translation

import org.json.JSONArray

/**
 * Parser balasan LLM yang toleran terhadap pembungkus markdown dan teks
 * tambahan. Dipisahkan dari provider agar bisa diuji tanpa jaringan.
 */
object LlmResponseParser {

    fun parse(content: String): List<String> {
        val withoutFence = content
            .replace("```json", "```")
            .replace("```JSON", "```")
        val body = if (withoutFence.contains("```")) {
            withoutFence.substringAfter("```").substringBefore("```")
        } else {
            withoutFence
        }.trim()

        val start = body.indexOf('[')
        val end = body.lastIndexOf(']')
        if (start >= 0 && end > start) {
            val array = runCatching { JSONArray(body.substring(start, end + 1)) }.getOrNull()
            if (array != null) {
                return (0 until array.length()).map { index -> array.optString(index).trim() }
            }
        }

        // Cadangan: satu baris per terjemahan, dengan nomor opsional.
        return body.lines()
            .map { it.replace(Regex("^\\s*\\d+[.)]\\s*"), "").trim() }
            .filter { it.isNotEmpty() && it != "```" }
    }
}
