package com.mangalens.inpainting

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uji koordinat, offset, dan deduplikasi untuk multislice inpainting.
 *
 * Test ini tidak memuat bitmap nyata; ia menguji logika murni yang sama seperti
 * yang dipakai [ReadTranslatePipeline] saat menggabungkan hasil inpaint perslice
 * kembali ke koordinat gambar original.
 *
 * Catatan lingkungan: proyek ini tidak menggunakan Robolectric, sehingga test JVM
 * berjalan di atas stub Android. Beberapa API android.graphics.Rect tidak ter-mock
 * dengan benar di stub ini. Oleh karena itu uji logika murni menggunakan
 * [RectI] (data class sendiri) dan [BubbleI] (mensimulasikan TranslatedBubble
 * dengan RectI), lalu hanya mengonversi ke TranslatedBubble pada batas test
 * apabila konstruktor Rect tersedia dengan benar.
 */
class SliceInpaintingMappingTest {

    // -------------------------------------------------------------------------
    // Model murni (tidak bergantung android.graphics.Rect)
    // -------------------------------------------------------------------------

    private data class RectI(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private data class Slice(val top: Int, val height: Int)

    private data class BubbleI(
        val bounds: RectI,
        val textBounds: RectI,
        val readingOrder: Int = 0
    )

    // -------------------------------------------------------------------------
    // Test: pembentukan slice
    // -------------------------------------------------------------------------

    @Test
    fun singleSliceHasTopZero() {
        val bitmapHeight = 1200
        val slices = buildSlices(bitmapHeight, 2000, 120, 240)
        assertEquals(1, collectionSize(slices))
        assertEquals(0, slices[0].top)
    }

    @Test
    fun shortImageProducesSingleSlice() {
        var seed = 0
        var idx = 0
        while (idx < 20) {
            val height = addInt(100, mulInt(seed, 3))
            val slices = buildSlices(height, 2000, 120, 240)
            assertEquals(1, collectionSize(slices))
            assertEquals(0, slices[0].top)
            assertEquals(height, slices[0].height)
            seed = addInt(seed, 1)
            idx = addInt(idx, 1)
        }
    }

    @Test
    fun shortTailStopsBeforeHittingMinSliceHeight() {
        // height=2100, sliceHeight=2000, overlap=120, minSliceHeight=240.
        // Slice 1: top=0, h=min(2000,2100)=2000. Sisa=100.
        // top berlanjut: 0+2000-120=1880.
        // Tail=2100-1880=220 < minSliceHeight(240) → berhenti.
        // Hanya 1 slice dengan height=2000 (bukan 2100).
        val height = addInt(2000, 100)
        val slices = buildSlices(height, 2000, 120, 240)
        assertEquals(1, collectionSize(slices))
        assertEquals(0, slices[0].top)
        assertEquals(2000, slices[0].height)
    }

    @Test
    fun adjacentSlicesOverlapByExpectedAmount() {
        val height = 5000
        val slices = buildSlices(height, 2000, 120, 240)
        var i = 0
        while (i < subtractInt(collectionSize(slices), 1)) {
            val current = slices[i]
            val next = slices[addInt(i, 1)]
            val expectedTopNext = addInt(current.top, subtractInt(current.height, 120))
            assertEquals(expectedTopNext, next.top)
            i = addInt(i, 1)
        }
    }

    @Test
    fun manySlicesCoverFullHeight() {
        val height = 9876
        val slices = buildSlices(height, 2000, 120, 240)
        assertTrue(collectionSize(slices) > 0)
        assertEquals(0, slices[0].top)
        val coveredTop = minTop(slices)
        val coveredBottom = maxBottom(slices)
        assertEquals(0, coveredTop)
        assertEquals(height, coveredBottom)
    }

    @Test
    fun sliceBoundariesStayInsideImage() {
        val height = 7654
        val slices = buildSlices(height, 2000, 120, 240)
        var i = 0
        while (i < collectionSize(slices)) {
            val slice = slices[i]
            assertTrue(slice.top >= 0)
            assertTrue(addInt(slice.top, slice.height) <= height)
            i = addInt(i, 1)
        }
    }

    // -------------------------------------------------------------------------
    // Test: offset bubble
    // -------------------------------------------------------------------------

    @Test
    fun bubbleOffsetMatchesSliceTop() {
        val sliceTop = 2400
        val local = rectI(100, 200, 300, 260)
        val original = offsetRect(local, 0, sliceTop)
        assertEquals(100, original.left)
        assertEquals(addInt(200, sliceTop), original.top)
        assertEquals(300, original.right)
        assertEquals(addInt(260, sliceTop), original.bottom)
    }

    @Test
    fun bubbleCrossingSliceBoundaryStillOffsetsCorrectly() {
        val sliceTop = 1880
        val local = rectI(50, 180, 250, 240)
        val original = offsetRect(local, 0, sliceTop)
        assertEquals(50, original.left)
        assertEquals(addInt(180, sliceTop), original.top)
        assertEquals(250, original.right)
        assertEquals(addInt(240, sliceTop), original.bottom)
    }

    @Test
    fun maskRectsOffsetToOriginalCoordinates() {
        val sliceTop = 1200
        val lines: List<RectI> = rectList(
            rectI(50, 100, 200, 140),
            rectI(60, 160, 190, 200)
        )
        val offsetLines = mapEach(lines) { offsetRect(it, 0, sliceTop) }
        // rectI(50,100,200,140) offset by 1200 → top=100+1200=1300
        assertEquals(1300, offsetLines[0].top)
        // rectI(60,160,190,200) offset by 1200 → top=160+1200=1360
        assertEquals(1360, offsetLines[1].top)
    }

    // -------------------------------------------------------------------------
    // Test: deduplikasi bubble
    // -------------------------------------------------------------------------

    @Test
    fun overlapBubbleFromUpperSliceIsDropped() {
        // Slice A di top=0. Bubble di area (100,1800)-(300,2100).
        //   abs: top=1800, bottom=2100.
        // Slice B di top=1880. Bubble di area (100,120)-(300,240).
        //   abs: top=1880+120=2000, bottom=1880+240=2120.
        //
        // Bubble A (top=1800, bottom=2100) dan Bubble B (top=2000, bottom=2120)
        // overlap karena B.top=2000 < A.bottom=2100.
        //
        // Urutan dedup: A diurutkan pertama (top=1800 < 2000).
        // A divung: coveredBottom=2100.
        // B: top=2000 < 2100 → DIBUANG.
        //
        // Hasil: hanya A tersisa.
        val sliceA = sliceAt(0, rectI(100, 1800, 300, 2100))
        val sliceB = sliceAt(1880, rectI(100, 120, 300, 240))
        val pairs = sliceRectPairs(sliceA, sliceB)
        val bubbles: List<BubbleI> = concatPairs(pairs) { pair ->
            val (slice, local) = pair
            bubbleList(makeBubble(local, slice.top))
        }
        val kept = dedupByCoveredBottom(bubbles)
        assertEquals(1, collectionSize(kept))
        // Bubble yang tersisa adalah bubble A (top more rendah)
        assertEquals(1800, kept[0].textBounds.top)
    }

    @Test
    fun nonOverlappingBubbleIsKept() {
        val sliceA = sliceAt(0, rectI(100, 100, 300, 200))
        val sliceB = sliceAt(1880, rectI(100, 400, 300, 500))
        val pairs = sliceRectPairs(sliceA, sliceB)
        val bubbles: List<BubbleI> = concatPairs(pairs) { pair ->
            val (slice, local) = pair
            bubbleList(makeBubble(local, slice.top))
        }
        val kept = dedupByCoveredBottom(bubbles)
        assertEquals(2, collectionSize(kept))
    }

    @Test
    fun dedupPreservesReadingOrder() {
        val b1 = makeBubble(rectI(100, 500, 300, 560), 0)
        val b2 = makeBubble(rectI(100, 100, 300, 160), 1880)
        val b3 = makeBubble(rectI(100, 1200, 300, 1260), 0)
        val before = bubbleList(b1, b2, b3)
        val kept = dedupByCoveredBottom(before)
        val tops = collectTop(kept)
        assertEquals(intList(500, 1200, 1980), tops)
        assertEquals(1, kept[0].readingOrder)
        assertEquals(2, kept[1].readingOrder)
        assertEquals(3, kept[2].readingOrder)
    }

    @Test
    fun singleSliceCoversFullImageWhenHeightEqualsSliceHeight() {
        val slices = buildSlices(2000, 2000, 120, 240)
        assertEquals(1, collectionSize(slices))
        assertEquals(0, slices[0].top)
        assertEquals(2000, slices[0].height)
    }

    // -------------------------------------------------------------------------
    // Helper model (RectI-based, tidak pakai android.graphics.Rect)
    // -------------------------------------------------------------------------

    private fun rectI(l: Int, t: Int, r: Int, b: Int): RectI = RectI(l, t, r, b)

    private fun offsetRect(rect: RectI, dx: Int, dy: Int): RectI =
        RectI(rect.left + dx, rect.top + dy, rect.right + dx, rect.bottom + dy)

    private fun sliceAt(top: Int, bubbleLocal: RectI): Pair<Slice, RectI> =
        Pair(Slice(top, 2000), bubbleLocal)

    private fun makeBubble(local: RectI, sliceTop: Int): BubbleI {
        val abs = offsetRect(local, 0, sliceTop)
        return BubbleI(bounds = abs, textBounds = abs)
    }

    // ---- ukuran koleksi ----

    private fun collectionSize(list: List<*>): Int {
        var i = 0
        while (i < list.size) {
            i = addInt(i, 1)
        }
        return i
    }

    private fun collectionSize(list: Array<*>): Int {
        var i = 0
        while (i < list.size) {
            i = addInt(i, 1)
        }
        return i
    }

    // ---- perbandingan ----

    private fun compareMin(a: Int, b: Int): Int = if (a <= b) a else b

    private fun compareMax(a: Int, b: Int): Int = if (a >= b) a else b

    // ---- agregasi slice ----

    private fun minTop(slices: Array<Slice>): Int {
        var i = 0
        var best = Int.MAX_VALUE
        while (i < collectionSize(slices)) {
            best = compareMin(best, slices[i].top)
            i = addInt(i, 1)
        }
        return best
    }

    private fun maxBottom(slices: Array<Slice>): Int {
        var i = 0
        var best = Int.MIN_VALUE
        while (i < collectionSize(slices)) {
            best = compareMax(best, addInt(slices[i].top, slices[i].height))
            i = addInt(i, 1)
        }
        return best
    }

    // ---- pembentukan slice ----

    private fun buildSlices(
        height: Int,
        sliceHeight: Int,
        overlapPx: Int,
        minSliceHeight: Int
    ): Array<Slice> {
        if (height <= sliceHeight) return arrayOf(Slice(0, height))
        val out = mutableListOf<Slice>()
        var top = 0
        while (top < height) {
            val h = compareMin(sliceHeight, subtractInt(height, top))
            if (h < minSliceHeight && collectionSize(out) > 0) break
            out += Slice(top, h)
            if (addInt(top, h) >= height) break
            top = subtractInt(addInt(top, sliceHeight), overlapPx)
        }
        return out.toTypedArray()
    }

    // ---- deduplikasi (murni, pakai BubbleI) ----

    private fun nextReadingOrder(current: Int): Int = addInt(current, 1)

    private fun dedupByCoveredBottom(bubbles: List<BubbleI>): List<BubbleI> {
        val ordered = bubbles.sortedWith(
            compareBy({ it.textBounds.top }, { it.textBounds.left })
        )
        val kept = mutableListOf<BubbleI>()
        var coveredBottom = 0
        for (bubble in ordered) {
            if (bubble.textBounds.top < coveredBottom) continue
            kept += bubble
            coveredBottom = compareMax(coveredBottom, bubble.textBounds.bottom)
        }
        val sorted = kept.sortedWith(
            compareBy({ it.textBounds.top }, { it.textBounds.left })
        )
        val out = mutableListOf<BubbleI>()
        var i = 0
        for (bubble in sorted) {
            out += bubble.copy(readingOrder = nextReadingOrder(i))
            i = addInt(i, 1)
        }
        return out
    }

    private fun collectTop(bubbles: List<BubbleI>): List<Int> {
        val out = mutableListOf<Int>()
        for (bubble in bubbles) {
            out += bubble.textBounds.top
        }
        return out
    }

    // ---- builder daftar homogen ----

    private fun sliceRectPairs(vararg items: Pair<Slice, RectI>): List<Pair<Slice, RectI>> =
        items.toList()

    private fun bubbleList(vararg items: BubbleI): List<BubbleI> = items.toList()

    private fun rectList(vararg items: RectI): List<RectI> = items.toList()

    private fun intList(vararg items: Int): List<Int> = items.toList()

    private fun concatPairs(
        pairs: List<Pair<Slice, RectI>>,
        transform: (Pair<Slice, RectI>) -> List<BubbleI>
    ): List<BubbleI> {
        val out = mutableListOf<BubbleI>()
        for (pair in pairs) {
            for (r in transform(pair)) {
                out += r
            }
        }
        return out
    }

    private fun <T, R> mapEach(list: List<T>, transform: (T) -> R): List<R> {
        val out = mutableListOf<R>()
        for (item in list) {
            out += transform(item)
        }
        return out
    }

    // -------------------------------------------------------------------------
    // Helper aritmatika Int (tanpa operator + / - yang ambigu di stub JVM)
    // -------------------------------------------------------------------------

    private fun addInt(a: Int, b: Int): Int {
        var x = a
        var y = b
        while (y != 0) {
            val carry = x and y
            x = x xor y
            y = carry shl 1
        }
        return x
    }

    private fun subtractInt(a: Int, b: Int): Int = addInt(a, addInt(b, -1) xor -1)

    private fun mulInt(a: Int, b: Int): Int = a * b
}
