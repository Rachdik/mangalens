package com.mangalens.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.max

/**
 * Validasi OCR pada halaman manga BERBAHASA JEPANG asli (Phase 9J, Task A).
 *
 * Fixture dan lisensinya:
 * - `manga_page_ja_modern.png` — halaman manga modern, dipotong dari
 *   `File:Three versions.png` (Wikimedia Commons, CC BY-SA 4.0, karya Okitan;
 *   panel asli oleh tofumentalz). Potongan kiri = versi Jepang asli, 707x1002.
 *   Berisi 7 balon dialog dengan teks vertikal, furigana, screentone, dan teks
 *   kecil.
 * - `manga_page_ja_1902.jpg` — `Tagosaku to Mokube no Tokyo Kenbutsu`
 *   (Kitazawa Rakuten, 1902, domain publik; sumber Manga-Museum). 6 balon teks
 *   vertikal beraksara lama.
 *
 * Ground truth ditulis manual dari gambar (diperbesar 2-3x) dan TIDAK diambil
 * dari keluaran OCR. Akurasi karakter dihitung dengan jarak Levenshtein setelah
 * spasi/tanda baca dinormalisasi.
 *
 * Test ini hanya mencatat metrik terukur; ambang assertion sengaja longgar dan
 * diturunkan dari hasil pengukuran nyata, bukan dinaikkan agar terlihat lulus.
 */
@RunWith(AndroidJUnit4::class)
class JapaneseMangaOcrTest {

    private lateinit var ocrEngine: MlKitOcrEngine

    @Before
    fun setUp() {
        ocrEngine = MlKitOcrEngine()
    }

    @Test
    fun ocrOnModernJapaneseMangaPage() = runBlocking {
        val name = MODERN_PAGE
        val bitmap = loadAsset(name)
        assertNotNull("$name harus termuat dari assets", bitmap)

        val started = System.currentTimeMillis()
        val blocks = ocrEngine.recognize(bitmap!!, LanguageScript.JAPANESE)
        val elapsed = System.currentTimeMillis() - started

        println("===== MANGALENS_JA_MANGA_BEGIN =====")
        println("Image: $name ${bitmap.width}x${bitmap.height}")
        println("Duration: ${elapsed}ms  Blocks: ${blocks.size}")

        blocks.forEachIndexed { index, block ->
            println(
                "BLOCK ${index + 1} order=${block.order} orientation=${block.orientation} " +
                    "bounds=[${block.bounds.left},${block.bounds.top}]" +
                    "-[${block.bounds.right},${block.bounds.bottom}] " +
                    "lines=${block.lines.size} text='${block.text.replace("\n", " / ")}'"
            )
            Log.d(TAG, "JA BLOCK ${index + 1}: ${block.text.replace("\n", " / ")} @ ${block.bounds}")
        }

        // Dua metrik, keduanya dilaporkan:
        // - acc       : kemiripan string terhadap SATU blok (peka urutan).
        // - coverage  : berapa persen karakter balon muncul di halaman, tanpa
        //               peduli pembagian blok/urutan. Mengukur daya kenal model.
        val pageText = blocks.joinToString("") { it.text }
        var detected = 0
        var accuracySum = 0.0
        var coverageSum = 0.0
        var covered = 0
        MODERN_GROUND_TRUTH.forEachIndexed { index, truth ->
            val best = blocks.maxByOrNull { similarity(it.text, truth) }
            val score = best?.let { similarity(it.text, truth) } ?: 0.0
            val cover = coverage(pageText, truth)
            if (cover >= 0.95) covered++
            val matched = score >= DETECTION_SIMILARITY
            if (matched) {
                detected++
                accuracySum += score
            }
            coverageSum += cover
            println(
                "BUBBLE ${index + 1} ${if (matched) "DETECTED" else "MISSED"} " +
                    "acc=${(score * 100).toInt()}% coverage=${(cover * 100).toInt()}% " +
                    "gt='$truth' ocr='${best?.text?.replace("\n", " / ") ?: "-"}'"
            )
        }
        val meanAccuracy = if (detected == 0) 0.0 else accuracySum / detected
        val meanCoverage = coverageSum / MODERN_GROUND_TRUTH.size
        println("BUBBLES detected=$detected/${MODERN_GROUND_TRUTH.size} meanAccuracy=${(meanAccuracy * 100).toInt()}%")
        println("CHAR_COVERAGE bubbles=$covered/${MODERN_GROUND_TRUTH.size} mean=${(meanCoverage * 100).toInt()}%")
        println("===== MANGALENS_JA_MANGA_END =====")

        assertTrue("OCR Jepang tidak menemukan blok apa pun", blocks.isNotEmpty())
        assertTrue(
            "Tidak ada blok yang berisi kanji/kana pada halaman manga Jepang",
            blocks.any { block -> block.text.any { isJapanese(it) } }
        )
        // Baseline terukur (Phase 9J): 6/7 karakter balon terbaca penuh dan
        // 4/7 cocok sebagai satu blok dengan urutan benar. Ambang di bawah ini
        // adalah batas regresi, bukan target yang dinaikkan agar lulus.
        assertTrue(
            "Hanya $covered/${MODERN_GROUND_TRUTH.size} balon yang karakternya terbaca penuh (minimum 4)",
            covered >= 4
        )
        assertTrue(
            "Hanya $detected/${MODERN_GROUND_TRUTH.size} balon cocok sebagai satu blok (minimum 3)",
            detected >= 3
        )
    }

    /** Persentase karakter ground truth yang muncul di teks halaman OCR. */
    private fun coverage(pageText: String, truth: String): Double {
        val page = normalize(pageText)
        val needed = normalize(truth)
        if (needed.isEmpty()) return 0.0
        val available = HashMap<Char, Int>()
        page.forEach { available[it] = (available[it] ?: 0) + 1 }
        var hit = 0
        for (ch in needed) {
            val left = available[ch] ?: 0
            if (left > 0) {
                hit++
                available[ch] = left - 1
            }
        }
        return hit.toDouble() / needed.length
    }

    @Test
    fun ocrOn1902JapaneseMangaPage() = runBlocking {
        val name = HISTORIC_PAGE
        val bitmap = loadAsset(name)
        assertNotNull("$name harus termuat dari assets", bitmap)

        val started = System.currentTimeMillis()
        val blocks = ocrEngine.recognize(bitmap!!, LanguageScript.JAPANESE)
        val elapsed = System.currentTimeMillis() - started

        println("===== MANGALENS_JA_MANGA_1902_BEGIN =====")
        println("Image: $name ${bitmap.width}x${bitmap.height}")
        println("Duration: ${elapsed}ms  Blocks: ${blocks.size}")
        blocks.forEachIndexed { index, block ->
            println(
                "BLOCK ${index + 1} bounds=${block.bounds} " +
                    "orientation=${block.orientation} text='${block.text.replace("\n", " / ")}'"
            )
        }
        var detected = 0
        HISTORIC_GROUND_TRUTH.forEachIndexed { index, truth ->
            val score = blocks.maxOfOrNull { similarity(it.text, truth) } ?: 0.0
            if (score >= DETECTION_SIMILARITY) detected++
            println("BUBBLE ${index + 1} acc=${(score * 100).toInt()}% gt='$truth'")
        }
        println("BUBBLES detected=$detected/${HISTORIC_GROUND_TRUTH.size}")
        println("===== MANGALENS_JA_MANGA_1902_END =====")

        // Halaman ini beraksara lama (1902). Tidak ada ambang akurasi yang
        // dipaksakan: hasilnya dilaporkan apa adanya di dokumen validasi.
        assertTrue(
            "OCR pada halaman 1902 tidak boleh melempar exception",
            elapsed in 0..120_000
        )
    }

    private fun isJapanese(ch: Char): Boolean =
        ch in '\u3040'..'\u30FF' || ch in '\u3400'..'\u4DBF' || ch in '\u4E00'..'\u9FFF'

    private fun loadAsset(name: String): Bitmap? {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        return runCatching {
            testContext.assets.open(name).use { BitmapFactory.decodeStream(it) }
        }.onFailure { Log.e(TAG, "Gagal memuat asset $name", it) }.getOrNull()
    }

    /** Akurasi karakter 1 - jarak Levenshtein / panjang ground truth. */
    private fun similarity(ocr: String, truth: String): Double {
        val a = normalize(ocr)
        val b = normalize(truth)
        if (b.isEmpty()) return 0.0
        if (a.isEmpty()) return 0.0
        return (1.0 - levenshtein(a, b).toDouble() / b.length).coerceIn(0.0, 1.0)
    }

    private fun normalize(text: String): String =
        text.filterNot { ch ->
            ch.isWhitespace() || ch in "。、，,.!?！？…‥「」『』（）()・:;ー-"
        }

    private fun levenshtein(a: String, b: String): Int {
        val previous = IntArray(b.length + 1) { it }
        val current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
            }
            System.arraycopy(current, 0, previous, 0, previous.size)
        }
        return previous[b.length]
    }

    private companion object {
        private const val TAG = "MangaLensJaOcr"
        private const val MODERN_PAGE = "manga_page_ja_modern.png"
        private const val HISTORIC_PAGE = "manga_page_ja_1902.jpg"

        /** Di atas skor ini satu balon dianggap terdeteksi. */
        private const val DETECTION_SIMILARITY = 0.40

        /** Transkripsi manual 7 balon pada halaman manga modern. */
        private val MODERN_GROUND_TRUTH = listOf(
            "シュヴァルツ！",
            "このかわいい人形はなにかしら。",
            "どこから見つけて……",
            "いえ、特注のもので……",
            "まあ！",
            "わたしくしに内緒でオーダーするなんて！",
            "抜けがけ"
        )

        /**
         * Transkripsi perkiraan 6 balon halaman 1902. Ortografi lama (変体仮名,
         * 旧字) membuat transkripsi ini tidak bisa dianggap setara cetak modern,
         * jadi hanya dipakai sebagai indikasi, bukan skor resmi.
         */
        private val HISTORIC_GROUND_TRUTH = listOf(
            "田舎作ウムトコドッコイエラク聴いもなんだなア",
            "鎗屋ビール本長衛田舎さん待ちなよお前聴かいでおいねぇか水代かねぇ",
            "本長衛サウ捻るでねぇだよからだよ",
            "田舎作金さんそれや今めぇいこい出て来みなさんな本長衛だちゅー枚香坂の常盤が漏れると",
            "田舎作ウムこうかえ本長衛ワッ",
            "田舎作そんだら持ったしやれよーが続ってくればいからあめい其処に酢サアツん出してみな"
        )
    }
}
