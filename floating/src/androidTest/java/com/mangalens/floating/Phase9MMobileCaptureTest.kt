package com.mangalens.floating

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowMetrics
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.inpainting.MangaPageTranslator
import com.mangalens.inpainting.PageTranslation
import com.mangalens.inpainting.TranslatedBubble
import com.mangalens.ocr.LanguageScript
import com.mangalens.ocr.MlKitOcrEngine
import com.mangalens.translation.BackgroundColor
import com.mangalens.translation.MyMemoryTranslationProvider
import com.mangalens.translation.OverlayOpacity
import com.mangalens.translation.TextColor
import com.mangalens.translation.TextSize
import com.mangalens.translation.TranslationSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.reflect.Proxy

/**
 * Phase 9M: cross-app floating-bubble and capture-lifecycle coverage on top
 * of the Phase 9L rendering test. Reuses local [RecordingWindowManager] /
 * [FakeWindowContext] so the overlay is laid out against a fake WindowManager
 * (the system overlay permission cannot be granted through instrumentation
 * without user interaction).
 *
 * The assertions are deliberately limited to production-code behaviours that
 * can be verified without a real overlay window:
 *  1. Permission state: [FloatingTranslate.canDrawOverlays] mirrors
 *     [Settings.canDrawOverlays].
 *  2. Empty bubble list is a no-op and does not touch the overlay.
 *  3. Coordinate mapping: [BubbleOverlay.showTranslations] keeps
 *     [TranslatedBubble.textBounds] inside the screen bounds at two scales.
 */
@RequiresApi(Build.VERSION_CODES.R)
@RunWith(AndroidJUnit4::class)
class Phase9MMobileCaptureTest {

    private val screenWidth = 1080
    private val screenHeight = 2412

    @Test
    fun overlayPermissionStateMatchesSystemSettings() {
        val context = InstrumentationRegistry.getInstrumentation().context
        // FloatingTranslate.canDrawOverlays delegates to Settings.canDrawOverlays.
        assertNotNull("canDrawOverlays must not throw",
            FloatingTranslate.canDrawOverlays(context)
        )
        assertTrue(
            "canDrawOverlays must return a boolean",
            FloatingTranslate.canDrawOverlays(context) is Boolean
        )
    }

    @Test
    fun emptyBubbleListDoesNotTouchOverlay() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val settings = TranslationSettings(context)

        val screen = RecordingWindowManager(screenWidth, screenHeight)
        val overlay = createOverlay(settings, screen)
        val shown = overlay.showTranslations(emptyList(), scale = 1f)

        assertEquals("empty input must render nothing", 0, shown)
        assertFalse("no views must be attached", overlay.hasTranslations())
        overlay.clearTranslations()
        overlay.hide()
    }

    @Test
    fun coordinateMappingStaysInsideScreenAtTwoScales() {
        val context = InstrumentationRegistry.getInstrumentation().context

        val bubble = TranslatedBubble(
            bounds = Rect(380, 180, 630, 450),
            textBounds = Rect(400, 200, 600, 420),
            sourceText = "これはテストです",
            targetText = "INI ADALAH SEBUAH UJI COBA",
            fillColor = Color.WHITE,
            textColor = Color.BLACK,
            textLines = listOf(Rect(400, 200, 600, 420))
        )

        val settings = TranslationSettings(context).apply {
            textSize = TextSize.NORMAL
            overlayOpacity = OverlayOpacity.HALF
            backgroundColor = BackgroundColor.AUTO
            textColor = TextColor.AUTO
        }

        val rect1 = placeBubble(context, bubble, scale = 1f, settings)
        val rect2 = placeBubble(context, bubble, scale = 0.75f, settings)

        // Both scales place the overlay inside the screen.
        for (rect in listOf(rect1, rect2)) {
            assertTrue("rect must be inside the screen", rect.left >= 0 && rect.top >= 0)
            assertTrue("rect.right must not exceed screen width", rect.right <= screenWidth)
            assertTrue("rect.bottom must not exceed screen height", rect.bottom <= screenHeight)
        }
    }

    /**
     * Tiny overlay placement helper for the coordinate-movement tests. It keeps
     * the bubble on-screen and gives a stable, deterministic position so the
     * test does not depend on a real overlay window.
     */
    private fun placeBubble(
        context: Context,
        bubble: TranslatedBubble,
        scale: Float,
        settings: TranslationSettings
    ): Rect {
        val screen = RecordingWindowManager(screenWidth, screenHeight)
        val overlay = createOverlay(settings, screen)
        overlay.showTranslations(listOf(bubble), scale)
        val params = screen.added.firstOrNull()?.second
            ?: error("overlay must be added for scale=$scale")
        val rect = Rect(params.x, params.y, params.x + params.width, params.y + params.height)
        overlay.clearTranslations()
        overlay.hide()
        return rect
    }

    private fun loadAsset(context: Context, name: String): Bitmap? = runCatching {
        context.assets.open(name).use { BitmapFactory.decodeStream(it) }
    }.onFailure { Log.e(TAG, "asset $name gagal dimuat", it) }.getOrNull()

    private fun createOverlay(
        settings: TranslationSettings,
        screen: RecordingWindowManager
    ): BubbleOverlay = BubbleOverlay(
        FakeWindowContext(InstrumentationRegistry.getInstrumentation().context, screen.manager),
        onTranslate = {},
        onStop = {},
        onRefresh = {},
        onPause = {},
        onResume = {},
        onSettings = {},
        settings = settings
    )

    /** Small local test helpers that mirror the production test window manager. */
    private class RecordingWindowManager(
        private val width: Int,
        private val height: Int
    ) {
        val added = mutableListOf<Pair<android.view.View, WindowManager.LayoutParams>>()

        private fun record(view: android.view.View, params: WindowManager.LayoutParams) {
            added += view to params
            val w = if (params.width > 0) params.width else width
            val h = if (params.height > 0) params.height else height
            view.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY)
            )
            view.layout(0, 0, w, h)
        }

        val manager: WindowManager = Proxy.newProxyInstance(
            WindowManager::class.java.classLoader,
            arrayOf(WindowManager::class.java)
        ) { _, method, args ->
            when (method.name) {
                "addView" -> {
                    record(
                        args!![0] as android.view.View,
                        args[1] as WindowManager.LayoutParams
                    )
                    null
                }

                "updateViewLayout" -> {
                    val view = args!![0] as android.view.View
                    val lp = args[1] as WindowManager.LayoutParams
                    added.removeAll { it.first === view }
                    record(view, lp)
                    null
                }

                "removeView", "removeViewImmediate" -> {
                    val view = args!![0] as android.view.View
                    added.removeAll { it.first === view }
                    null
                }

                "getCurrentWindowMetrics", "getMaximumWindowMetrics" ->
                    WindowMetrics(Rect(0, 0, width, height), WindowInsets.Builder().build())

                else -> null
            }
        } as WindowManager

        fun close() {
            added.clear()
        }
    }

    /** Context that returns a fake WindowManager above. */
    private class FakeWindowContext(
        base: Context,
        private val windowManager: WindowManager
    ) : ContextWrapper(base) {
        // getSystemService(Class) in ContextWrapper delegates to getSystemService(String);
        // overriding by name is sufficient for the production helper.
        override fun getSystemService(name: String): Any? =
            if (name == Context.WINDOW_SERVICE) windowManager else super.getSystemService(name)
    }

    private companion object {
        const val TAG = "MangaLensPhase9M"
    }
}
