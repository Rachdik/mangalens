package com.mangalens.reader.images

import java.io.File
import java.security.MessageDigest

/** Menyimpan byte gambar asli di cache internal, bukan di galeri. */
class ImageStore(directory: File) {

    private val dir: File = directory.apply { mkdirs() }

    fun find(key: String): File? = fileFor(key).takeIf { it.isFile && it.length() > 0 }

    fun write(key: String, bytes: ByteArray, _mimeType: String?): File {
        val target = fileFor(key)
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
        return target
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
}
