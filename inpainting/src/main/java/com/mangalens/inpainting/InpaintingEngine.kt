package com.mangalens.inpainting

import android.graphics.Bitmap

/**
 * Menghapus teks asli di area mask lalu mengisi dengan latar sekitarnya.
 * Implementasi Kotlin murni (median ring + diffusion) masuk pada Tahap 6.
 */
interface InpaintingEngine {
    fun inpaint(source: Bitmap, mask: Bitmap): Bitmap
}
