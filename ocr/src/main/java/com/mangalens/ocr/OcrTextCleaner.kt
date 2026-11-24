package com.mangalens.ocr

/**
 * Normalisasi hasil OCR sebelum grouping/terjemahan.
 *
 * Aturan:
 * - hanya spasi/karakter tak terlihat yang dibersihkan;
 * - angka, tanda baca, dan huruf dipertahankan apa adanya;
 * - baris tanpa huruf/angka dibuang agar garis atau bintik tidak menjadi dialog;
 * - baris kosong tidak ikut dikirim ke penerjemah.
 */
object OcrTextCleaner {

    fun clean(raw: String): String = raw.lineSequence()
        .map { normalizeLine(it) }
        .filter { line -> line.any { it.isLetterOrDigit() } }
        .joinToString("\n")

    private val latinConfusables = mapOf(
        'Ă' to 'A', 'Ą' to 'A', 'Á' to 'A', 'À' to 'A', 'Â' to 'A', 'Ã' to 'A', 'Ä' to 'A', 'Å' to 'A',
        'É' to 'E', 'È' to 'E', 'Ê' to 'E', 'Ë' to 'E',
        'Í' to 'I', 'Ì' to 'I', 'Î' to 'I', 'Ï' to 'I',
        'Ó' to 'O', 'Ò' to 'O', 'Ô' to 'O', 'Õ' to 'O', 'Ö' to 'O',
        'Ú' to 'U', 'Ù' to 'U', 'Û' to 'U', 'Ü' to 'U',
        'Ç' to 'C', 'Ñ' to 'N', 'Š' to 'S', 'Ž' to 'Z'
    )

    fun normalizeLine(line: String): String {
        val builder = StringBuilder(line.length)
        var lastWasSpace = false
        for (ch in line) {
            when {
                ch == '\u00A0' || ch == '\u3000' || ch == '\t' -> {
                    if (!lastWasSpace) builder.append(' ')
                    lastWasSpace = true
                }

                ch.isWhitespace() -> {
                    if (!lastWasSpace) builder.append(' ')
                    lastWasSpace = true
                }

                ch.code == 0x200B || ch.code == 0x200C || ch.code == 0x200D ||
                    ch.code == 0xFEFF -> Unit

                else -> {
                    builder.append(latinConfusables[ch] ?: ch)
                    lastWasSpace = false
                }
            }
        }
        return builder.toString().trim()
    }
}
