package com.mangalens.reader.images

import java.io.File
import java.security.MessageDigest

/**
 * Menyimpan byte gambar asli di cache internal, bukan di galeri.
 *
 * Cache dibatasi [maxBytes] (default 96 MB). Setiap kali menulis gambar baru,
 * berkas terlama (berdasarkan waktu modifikasi) dihapus sampai pemakaian turun
 * di bawah [TRIM_TARGET] dari batas. Tanpa batas ini, membaca banyak chapter
 * membuat folder cache tumbuh tanpa henti dan memenuhi penyimpanan.
 */
class ImageStore(
    directory: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES
) {

    private val dir: File = directory.apply { mkdirs() }
    private val lock = Any()

    fun find(key: String): File? = fileFor(key).takeIf { it.isFile && it.length() > 0 }

    fun write(key: String, bytes: ByteArray, _mimeType: String?): File = synchronized(lock) {
        val target = fileFor(key)
        if (target.isFile && target.length() == bytes.size.toLong()) {
            // Sudah ada isi yang sama: segarkan waktunya agar tidak dihapus LRU.
            target.setLastModified(System.currentTimeMillis())
            return target
        }
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
        prune(keep = target)
        target
    }

    /** Total byte yang dipakai cache saat ini. */
    fun totalBytes(): Long = synchronized(lock) {
        dir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    /** Hapus berkas terlama sampai pemakaian berada di bawah [TRIM_TARGET] * maxBytes. */
    private fun prune(keep: File) {
        val files = dir.listFiles()?.filter { it.isFile && it != keep } ?: return
        var total = files.sumOf { it.length() } + keep.length()
        if (total <= maxBytes) return

        val target = (maxBytes * TRIM_TARGET).toLong()
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= target) break
            val length = file.length()
            if (file.delete()) total -= length
        }
    }

    /**
     * Nama file harus deterministik dari kunci, bukan dari MIME, supaya
     * pengecekan cache menemukan file yang sama pada pembukaan berikutnya.
     * Ekstensi .img tidak masalah karena BitmapFactory membaca isi file.
     */
    private fun fileFor(key: String): File = File(dir, "${sha256(key)}.img")

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)

    companion object {
        /** Batas keras ukuran cache gambar sumber. */
        const val DEFAULT_MAX_BYTES = 96L * 1024 * 1024

        /** Setelah pemangkasan, cache disisakan ~70% dari batas. */
        private const val TRIM_TARGET = 0.7
    }
}
