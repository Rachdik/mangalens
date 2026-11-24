package com.mangalens.translation

/**
 * Deteksi bahasa sumber tingkat skrip untuk mode "auto".
 *
 * ML Kit Translation membutuhkan bahasa sumber yang eksplisit; memetakan
 * "auto" ke Inggris membuat teks Jepang diterjemahkan dengan model Inggris
 * (mis. `だめだ` menjadi "Mulia"). Deteksi ini memilih bahasa dari karakter yang
 * benar-benar ada di setiap dialog.
 *
 * Keterbatasan: bahasa berhuruf Latin (Spanyol, Prancis, Jerman, ...) tidak
 * dapat dibedakan tanpa model deteksi bahasa, sehingga jatuh ke Inggris.
 * Pengguna tetap dapat memilih bahasa sumber secara eksplisit.
 */
object SourceLanguage {

    const val AUTO = "auto"

    /** Kode BCP-47 yang kompatibel dengan TranslateLanguage.fromLanguageTag. */
    fun detect(text: String): String {
        var kana = 0
        var hangul = 0
        var han = 0
        var cyrillic = 0
        var arabic = 0
        var thai = 0
        var devanagari = 0
        var latin = 0

        for (ch in text) {
            val code = ch.code
            when {
                code in 0x3040..0x30FF || code in 0x31F0..0x31FF || code in 0xFF66..0xFF9F ->
                    kana++

                code in 0x1100..0x11FF || code in 0xA960..0xA97F || code in 0xAC00..0xD7FF ->
                    hangul++

                code in 0x3400..0x4DBF || code in 0x4E00..0x9FFF || code in 0xF900..0xFAFF ->
                    han++

                code in 0x0400..0x04FF -> cyrillic++
                code in 0x0600..0x06FF || code in 0x0750..0x077F -> arabic++
                code in 0x0E00..0x0E7F -> thai++
                code in 0x0900..0x097F -> devanagari++
                ch.isLetterOrDigit() -> latin++
            }
        }

        // Kana/hangul tidak dipakai bahasa lain, jadi kehadirannya menentukan.
        return when {
            kana > 0 -> "ja"
            hangul > 0 -> "ko"
            han > latin -> "zh"
            cyrillic > latin -> "ru"
            arabic > latin -> "ar"
            thai > latin -> "th"
            devanagari > latin -> "hi"
            else -> "en"
        }
    }

    /** Normalisasi tag pilihan pengguna ("jp", "zh-CN", "in") ke kode kanonis. */
    fun normalize(tag: String): String = when (val value = tag.trim().lowercase()) {
        "", AUTO -> AUTO
        "jp" -> "ja"
        "kr" -> "ko"
        "zh-cn", "zh-tw", "zh-hans", "zh-hant" -> "zh"
        "in" -> "id"
        else -> value.substringBefore('-')
    }

    fun isAuto(tag: String): Boolean = normalize(tag) == AUTO
}
