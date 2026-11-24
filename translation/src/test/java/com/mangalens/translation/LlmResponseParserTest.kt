package com.mangalens.translation

import org.junit.Assert.assertEquals
import org.junit.Test

class LlmResponseParserTest {

    @Test
    fun plainJsonArray_isParsedInOrder() {
        assertEquals(
            listOf("Tunggu!", "Jangan!"),
            LlmResponseParser.parse("[\"Tunggu!\",\"Jangan!\"]")
        )
    }

    @Test
    fun markdownFence_isIgnored() {
        val response = "```json\n[\"Halo\",\"Apa kau serius?\"]\n```"
        assertEquals(listOf("Halo", "Apa kau serius?"), LlmResponseParser.parse(response))
    }

    @Test
    fun extraExplanationAroundArray_isIgnored() {
        val response = "Berikut hasilnya:\n[\"Satu\",\"Dua\"]\nSemoga membantu."
        assertEquals(listOf("Satu", "Dua"), LlmResponseParser.parse(response))
    }

    @Test
    fun numberedLines_fallbackKeepsNumbersInsideText() {
        val response = "1. Aku punya 2 kucing.\n2. Tunggu 5 menit."
        assertEquals(
            listOf("Aku punya 2 kucing.", "Tunggu 5 menit."),
            LlmResponseParser.parse(response)
        )
    }

    @Test
    fun emptyItem_isPreservedForAlignment() {
        assertEquals(listOf("", "Dua"), LlmResponseParser.parse("[\"\",\"Dua\"]"))
    }
}
