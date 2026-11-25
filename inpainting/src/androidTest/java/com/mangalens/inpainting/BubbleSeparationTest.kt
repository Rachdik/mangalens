package com.mangalens.inpainting

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.ocr.LanguageScript
import com.mangalens.ocr.MlKitOcrEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pemisahan balon pada halaman manga Jepang nyata (Phase 9K, Priority B).
 *
 * Halaman `manga_page_ja_modern.png` memiliki 7 region teks (6 terbaca OCR):
 * - kanan atas: シュヴァルツ！
 * - kiri atas: このかわいい人形はなにかしら。
 * - tengah kiri: まあ！ (region sendiri sejak Phase 9L)
 * - tengah kiri: わたしくしに内緒で オーダーする なんて！
 * - tengah kanan atas: どこから見つけて……
 * - tengah kanan bawah: いえ、特注のもので……
 * - kiri bawah: 抜けがけ (teks sangat kecil; ML Kit tidak membacanya)
 *
 * Sebelum Phase 9K, balon kiri atas dan balon tengah kiri digabung menjadi satu,
 * sehingga terjemahan satu balon menutupi balon lain. Sebelum Phase 9L,
 * `まあ！` dan `わたしくしに…` digabung menjadi satu region teks.
 */
@RunWith(AndroidJUnit4::class)
class BubbleSeparationTest {

    private val context = InstrumentationRegistry.getInstrumentation().context

    @Test
    fun separatesAdjacentJapaneseBubbles() = runBlocking {
        val bitmap = loadAsset("manga_page_ja_modern.png")
        assertTrue("fixture harus termuat", bitmap != null)
        val blocks = MlKitOcrEngine().recognize(bitmap!!, LanguageScript.JAPANESE)
        println("SEPARATION blocks=${blocks.size}")
        blocks.forEach { println("  block '${it.text.replace("\n", " / ")}' ${it.bounds}") }

        val bubbles = BubbleDetector.detect(bitmap, blocks)
        println("SEPARATION bubbles=${bubbles.size}")
        bubbles.forEachIndexed { index, bubble ->
            println(
                "  bubble ${index + 1} conf=${bubble.confidence} text='${bubble.text}' " +
                    "textBounds=${bubble.textBounds} lines=${bubble.lines.size}"
            )
        }

        // 6 region dari 7 ground truth (抜けがけ tidak terbaca OCR).
        // Penggabungan dua balon tetangga (bug Phase 9J) menghasilkan 3-4,
        // penggabungan dua ucapan dalam satu balon (bug Phase 9K) menghasilkan 5.
        assertEquals("jumlah balon salah: ${bubbles.map { it.text }}", 6, bubbles.size)

        val topLeft = bubbles.firstOrNull { it.text.contains("この") }
        assertTrue("balon この... tidak ditemukan", topLeft != null)
        assertTrue(
            "balon この... tidak boleh memuat dialog balon lain: '${topLeft!!.text}'",
            !topLeft.text.contains("まあ") && !topLeft.text.contains("オーダー")
        )

        val long = bubbles.firstOrNull { it.text.contains("オーダー") }
        assertTrue("region オーダー tidak ditemukan", long != null)
        // Isi yang diuji adalah KEUTUHAN region, bukan ketepatan OCR: model
        // membaca "でわたくしに内緒で" (huruf た hilang), itu urusan test OCR.
        assertTrue(
            "region panjang harus memuat ketiga kolomnya: '${long!!.text}'",
            long.text.contains("なんて") && long.text.contains("内緒") &&
                long.text.contains("オーダー")
        )
        assertTrue(
            "region panjang tidak boleh memuat この...: '${long.text}'",
            !long.text.contains("この")
        )

        // Phase 9L: "まあ!" adalah ucapan terpisah dari kolom panjang. Menggabung
        // keduanya membuat salah satu kalimat tidak pernah diterjemahkan sendiri.
        val maa = bubbles.firstOrNull { it.text.contains("まあ") }
        assertTrue("region まあ! tidak ditemukan terpisah", maa != null)
        assertTrue(
            "region まあ! tidak boleh memuat dialog panjang: '${maa!!.text}'",
            !maa.text.contains("内緒") && !maa.text.contains("オーダー")
        )

        // Kotak teks antar balon tidak boleh bertumpuk.
        for (i in bubbles.indices) {
            for (j in i + 1 until bubbles.size) {
                val a = bubbles[i].textBounds
                val b = bubbles[j].textBounds
                val overlap = android.graphics.Rect()
                val intersects = overlap.setIntersect(a, b)
                val overlapArea = if (intersects) overlap.width() * overlap.height() else 0
                val smaller = minOf(a.width() * a.height(), b.width() * b.height())
                assertTrue(
                    "kotak balon ${i + 1} dan ${j + 1} bertumpuk: $a vs $b",
                    smaller <= 0 || overlapArea < smaller * 0.10
                )
            }
        }
    }

    private fun loadAsset(name: String): Bitmap? = runCatching {
        context.assets.open(name).use { BitmapFactory.decodeStream(it) }
    }.getOrNull()
}
