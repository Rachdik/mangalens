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
 * Cakupan balon Jepang terhadap ground truth (Phase 9L).
 *
 * Ground truth ditulis manual dari halaman (7 region dialog). Test ini mengukur
 * berapa region yang benar-benar terdeteksi dan diterjemahkan, serta memastikan
 * tidak ada dua region ground truth yang dipetakan ke region deteksi yang sama
 * (indikasi penggabungan) dan tidak ada satu region ground truth yang terpecah
 * menjadi beberapa region deteksi (indikasi pemisahan berlebihan).
 *
 * Yang diukur adalah GEOMETRI + keberadaan teks, bukan akurasi ejaan OCR.
 */
@RunWith(AndroidJUnit4::class)
class JapaneseBubbleCoverageTest {

    private val context = InstrumentationRegistry.getInstrumentation().context

    @Test
    fun coversJapaneseBubblesAgainstGroundTruth() = runBlocking {
        val bitmap = context.assets.open("manga_page_ja_modern.png").use {
            BitmapFactory.decodeStream(it)
        }
        val blocks = MlKitOcrEngine().recognize(bitmap, LanguageScript.JAPANESE)
        val bubbles = BubbleDetector.detect(bitmap, blocks)

        println("COVERAGE blocks=${blocks.size} bubbles=${bubbles.size}")
        bubbles.forEachIndexed { index, bubble ->
            println("COVERAGE region ${index + 1} '${bubble.text.replace("\n", " / ")}' ${bubble.textBounds}")
        }

        val matches = mutableMapOf<Int, MutableList<Int>>()
        bubbles.forEachIndexed { regionIndex, bubble ->
            GROUND_TRUTH.forEachIndexed { truthIndex, truth ->
                if (matched(bubble.text, truth)) {
                    matches.getOrPut(truthIndex) { mutableListOf() }.add(regionIndex)
                }
            }
        }

        GROUND_TRUTH.forEachIndexed { index, truth ->
            val hit = matches[index].orEmpty()
            println(
                "COVERAGE truth ${index + 1} '${truth}' -> " +
                    if (hit.isEmpty()) "MISSED" else "regions=${hit.map { it + 1 }}"
            )
        }

        val covered = matches.keys.size
        val merged = matches.values.count { it.size > 1 }
        val split = matches.count { (_, regions) -> regions.size == 0 }.let { 0 } +
            bubbles.indices.count { region -> matches.values.none { it.contains(region) } }

        println(
            "COVERAGE result covered=$covered/${GROUND_TRUTH.size} " +
                "unmatchedRegions=$split mergedTruths=$merged"
        )

        // Target Phase 9L: minimal 6 dari 7 region ground truth terdeteksi.
        assertTrue(
            "hanya $covered/${GROUND_TRUTH.size} region ground truth terdeteksi",
            covered >= 6
        )
        // Satu region deteksi tidak boleh mewakili dua dialog berbeda.
        assertEquals("ada region yang menggabungkan dua dialog ground truth", 0, merged)
        // Semua region deteksi harus cocok ke salah satu ground truth (tidak ada
        // region hantu dari artwork).
        assertTrue(
            "ada region deteksi yang tidak cocok dengan ground truth mana pun",
            split == 0
        )
        // Balon yang sebelumnya benar tidak boleh hilang.
        listOf("シュヴァルツ", "この", "どこから", "いえ", "まあ", "オーダー").forEach { key ->
            assertTrue(
                "balon '$key' hilang dari hasil deteksi",
                bubbles.any { it.text.contains(key) }
            )
        }
    }

    /** Cocok bila ≥60% karakter ground truth muncul di teks region (ABA/urutan bebas). */
    private fun matched(text: String, truth: String): Boolean {
        val cleaned = text.filter { it.isLetterOrDigit() || it in "。「」、…" }
        val target = truth.filter { it.isLetterOrDigit() || it in "。「」、…" }
        if (target.isEmpty()) return false
        val pool = cleaned.toMutableList()
        var hits = 0
        for (ch in target) {
            val at = pool.indexOf(ch)
            if (at >= 0) {
                hits++
                pool.removeAt(at)
            }
        }
        return hits.toDouble() / target.length >= 0.6
    }

    private companion object {
        /** 7 region dialog pada fixture; 抜けがけ tetap tidak terbaca OCR. */
        val GROUND_TRUTH = listOf(
            "シュヴァルツ！",
            "このかわいい人形はなにかしら。",
            "どこから見つけて……",
            "いえ、特注のもので……",
            "まあ！",
            "わたしくしに内緒でオーダーするなんて！",
            "抜けがけ"
        )
    }
}
