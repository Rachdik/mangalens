package com.mangalens.ocr

/**
 * Menentukan skrip tulisan dari teks hasil OCR.
 *
 * Dipakai dua kali: untuk memilih pasangan bahasa terjemahan (jangan
 * menerjemahkan teks Inggris dengan model Jepang) dan untuk memberi tahu
 * pengguna bahasa apa yang benar-benar terbaca di layar.
 */
object ScriptDetector {

    /** Jumlah huruf/angka; dipakai membandingkan hasil dua recognizer. */
    fun characterCount(text: String): Int = text.count { it.isLetterOrDigit() }

    fun detect(text: String): LanguageScript {
        var hangul = 0
        var kana = 0
        var han = 0
        var latin = 0

        for (ch in text) {
            val code = ch.code
            when {
                code in 0x1100..0x11FF || code in 0xA960..0xA97F || code in 0xAC00..0xD7FF ->
                    hangul++

                code in 0x3040..0x30FF || code in 0x31F0..0x31FF || code in 0xFF66..0xFF9F ->
                    kana++

                code in 0x3400..0x4DBF || code in 0x4E00..0x9FFF || code in 0xF900..0xFAFF ->
                    han++

                ch.isLetterOrDigit() -> latin++
            }
        }

        return when {
            kana >= 2 -> LanguageScript.JAPANESE
            hangul > 0 && hangul >= latin -> LanguageScript.KOREAN
            han > latin -> LanguageScript.CHINESE
            else -> LanguageScript.LATIN
        }
    }

    /** Kode bahasa BCP-47 untuk ML Kit Translation. */
    fun languageTag(script: LanguageScript): String = when (script) {
        LanguageScript.AUTO, LanguageScript.LATIN -> "en"
        LanguageScript.JAPANESE -> "ja"
        LanguageScript.KOREAN -> "ko"
        LanguageScript.CHINESE -> "zh"
    }

    /** Bahasa sumber berdasarkan isi teks yang benar-benar terbaca. */
    fun languageTagOf(text: String): String = languageTag(detect(text))
}
