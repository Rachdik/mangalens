package com.mangalens.translation

/**
 * Merapikan hasil terjemahan ML Kit agar tidak terlalu baku.
 *
 * Model on-device menerjemahkan dengan ragam formal ("Anda", "saya",
 * "apakah", "namun"). Untuk dialog manga, ragam itu terasa kaku. Penggantian
 * di sini sengaja terbatas pada kata yang maknanya tidak berubah, supaya tata
 * bahasa tetap benar dan tidak mengubah arti kalimat.
 */
object IndonesianStyle {

    private val replacements = listOf(
        "\\bAnda\\b" to "kamu",
        "\\banda\\b" to "kamu",
        "\\bSaya\\b" to "Aku",
        "\\bsaya\\b" to "aku",
        "\\bApakah\\b" to "Apa",
        "\\bapakah\\b" to "apa",
        "\\bBagaimana\\b" to "Gimana",
        "\\bbagaimana\\b" to "gimana",
        "\\bMengapa\\b" to "Kenapa",
        "\\bmengapa\\b" to "kenapa",
        "\\bIngin\\b" to "Mau",
        "\\bingin\\b" to "mau",
        "\\bDapat\\b" to "Bisa",
        "\\bdapat\\b" to "bisa",
        "\\btersebut\\b" to "itu",
        "\\bmerupakan\\b" to "itu",
        "\\bNamun\\b" to "Tapi",
        "\\bnamun\\b" to "tapi",
        "\\bTetapi\\b" to "Tapi",
        "\\btetapi\\b" to "tapi",
        "\\bKetika\\b" to "Waktu",
        "\\bketika\\b" to "waktu",
        "\\bsehingga\\b" to "jadi",
        "\\bsaat ini\\b" to "sekarang",
        "\\boleh karena itu\\b" to "jadi"
    ).map { (pattern, replacement) -> Regex(pattern) to replacement }

    fun casual(text: String): String {
        var result = text
        for ((pattern, replacement) in replacements) {
            result = pattern.replace(result, replacement)
        }
        return result
    }
}
