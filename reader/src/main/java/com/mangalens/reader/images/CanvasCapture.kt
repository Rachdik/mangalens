package com.mangalens.reader.images

/**
 * Hasil ekstraksi isi `<canvas>` dari WebView.
 *
 * [width]/[height] adalah ukuran gambar yang benar-benar diekstrak (bisa lebih
 * kecil dari backing store canvas bila melebihi batas piksel), sehingga OCR dan
 * overlay memakai skala yang konsisten.
 */
data class CanvasCapture(
    val bytes: ByteArray,
    val width: Int,
    val height: Int
) {
    // ByteArray butuh equals/hashCode manual agar tidak menyesatkan saat diuji.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CanvasCapture) return false
        return width == other.width && height == other.height && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = (bytes.contentHashCode() * 31 + width) * 31 + height
}

/**
 * Mengambil isi canvas dari halaman web. Implementasi nyata ada di Reader
 * karena memerlukan WebView dan thread UI; kelas ini hanya kontrak agar
 * [ImageScanSession] tidak bergantung pada Activity.
 */
fun interface CanvasExtractor {
    suspend fun extract(key: String): CanvasCapture?
}
