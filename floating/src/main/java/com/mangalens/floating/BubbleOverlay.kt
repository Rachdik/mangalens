package com.mangalens.floating

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import com.mangalens.core.theme.ThemeStore
import com.mangalens.inpainting.TranslatedBubble
import com.mangalens.translation.BackgroundColor
import com.mangalens.translation.OverlayOpacity
import com.mangalens.translation.TextColor
import com.mangalens.translation.TextSize
import com.mangalens.translation.TranslationSettings
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Bubble melayang, teks terjemahan yang ditempel di atas balon asli, dan
 * panel kontrol. Semua view dibuat programatik agar tidak bergantung
 * pada resource tema aplikasi pemanggil.
 */
class BubbleOverlay(
    private val context: Context,
    private val onTranslate: () -> Unit,
    private val onStop: () -> Unit,
    private val onRefresh: () -> Unit,
    private val onPause: () -> Unit,
    private val onResume: () -> Unit,
    private val onSettings: () -> Unit,
    private val settings: TranslationSettings
) {

    private val windowManager: WindowManager = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density

    private val screenBounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching { windowManager?.currentWindowMetrics?.bounds }.getOrNull()
    } else {
        null
    }
    private val screenWidth = screenBounds?.width() ?: context.resources.displayMetrics.widthPixels
    private val screenHeight = screenBounds?.height() ?: context.resources.displayMetrics.heightPixels

    /** Warna aksen mengikuti tema aplikasi, bukan konstanta terpisah. */
    private val accentColor: Int = ThemeStore.accentColor()

    private var bubble: Button? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var refreshButton: TextView? = null
    private var refreshParams: WindowManager.LayoutParams? = null
    private var refreshAnimator: android.animation.ObjectAnimator? = null

    private var panel: LinearLayout? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var status: TextView? = null
    private var scroller: ScrollView? = null

    private var bubbleAttached = false
    private var panelAttached = false

    /** Teks terjemahan yang ditempel langsung di atas balon aslinya. */
    private val translationViews = mutableListOf<View>()
    private val handler = Handler(Looper.getMainLooper())
    private val hidePanelTask = Runnable { hidePanel() }

    /** @return true bila bubble benar-benar terpasang di layar. */
    fun show(): Boolean {
        if (bubbleAttached) return true
        val view = Button(context).apply {
            text = "訳"
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = circle(dp(56), accentColor)
            elevation = dp(8).toFloat()
            isAllCaps = false
            contentDescription = "Terjemahkan layar"
        }

        val params = WindowManager.LayoutParams(
            dp(56),
            dp(56),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = screenHeight / 3
            ignoreInsets()
        }

        attachTouch(view, params)

        val attached = runCatching {
            windowManager.addView(view, params)
            bubble = view
            bubbleParams = params
            bubbleAttached = true
            true
        }.getOrElse { error ->
            Log.w(TAG, "Bubble gagal dipasang", error)
            false
        }
        if (attached) attachRefreshButton()
        return attached
    }

    /** Tombol refresh bulat kecil di samping bubble; selalu terlihat. */
    private fun attachRefreshButton() {
        if (refreshButton != null) return
        val base = bubbleParams ?: return
        val size = dp(40)
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = refreshX(base.x)
            y = base.y + dp(8)
            ignoreInsets()
        }

        val button = TextView(context).apply {
            text = "↻"
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = circle(size, accentColor)
            elevation = dp(7).toFloat()
            contentDescription = "Perbarui terjemahan"
            isClickable = true
            isFocusable = true
            setOnClickListener { onRefresh() }
        }

        runCatching {
            windowManager.addView(button, params)
            refreshButton = button
            refreshParams = params
        }.onFailure { Log.w(TAG, "Tombol refresh gagal dipasang", it) }
    }

    private fun refreshX(bubbleX: Int): Int {
        val right = bubbleX + dp(56) + dp(8)
        return if (right + dp(40) <= screenWidth) {
            right
        } else {
            (bubbleX - dp(40) - dp(8)).coerceAtLeast(0)
        }
    }

    /** Animasi rotasi singkat selama refresh; tidak mengubah posisi tombol. */
    fun setRefreshing(refreshing: Boolean) {
        val button = refreshButton ?: return
        refreshAnimator?.cancel()
        if (refreshing) {
            button.alpha = 0.75f
            refreshAnimator = android.animation.ObjectAnimator.ofFloat(button, View.ROTATION, 0f, 360f).apply {
                duration = 750L
                repeatCount = android.animation.ValueAnimator.INFINITE
                interpolator = android.view.animation.LinearInterpolator()
                start()
            }
        } else {
            refreshAnimator = null
            button.alpha = 1f
            button.rotation = 0f
        }
    }

    fun hide() {
        handler.removeCallbacks(hidePanelTask)
        clearTranslations()
        hidePanel()
        setRefreshing(false)
        refreshButton?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        refreshButton = null
        refreshParams = null
        val view = bubble
        if (view != null && bubbleAttached) {
            runCatching { windowManager.removeView(view) }
        }
        bubble = null
        bubbleParams = null
        bubbleAttached = false
    }

    fun showPanel() {
        val container = ensurePanel()
        if (panelAttached) return
        val params = WindowManager.LayoutParams(
            panelWidth(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = panelY()
        }
        runCatching {
            windowManager.addView(container, params)
            panelParams = params
            panelAttached = true
        }
    }

    fun setHidden(hidden: Boolean) {
        if (hidden) {
            clearTranslations()
            val view = bubble
            if (view != null && bubbleAttached) {
                runCatching { windowManager.removeView(view) }
            }
            bubbleAttached = false
            refreshButton?.let { button ->
                runCatching { windowManager.removeView(button) }
            }
            refreshButton = null
            refreshParams = null
            hidePanel()
            return
        }

        val view = bubble ?: return
        val params = bubbleParams ?: return
        if (!bubbleAttached) {
            runCatching {
                windowManager.addView(view, params)
                bubbleAttached = true
            }
            attachRefreshButton()
        }
    }

    fun hidePanel() {
        val view = panel
        if (view != null && panelAttached) {
            runCatching { windowManager.removeView(view) }
        }
        panelAttached = false
    }

    fun setStatus(text: String, isError: Boolean = false) {
        showPanel()
        status?.text = text
        status?.setTextColor(if (isError) 0xFFB3261E.toInt() else 0xFF6B6B73.toInt())
    }

    fun showTranslations(bubbles: List<TranslatedBubble>, scale: Float): Int {
        clearTranslations()
        if (bubbles.isEmpty() || scale <= 0f) return 0

        val opacity = settings.overlayOpacity
        val bgColor = settings.backgroundColor
        val textColorSetting = settings.textColor
        val textSizeSetting = settings.textSize

        var shown = 0
        for (bubble in bubbles) {
            if (shown >= MAX_TRANSLATIONS) break
            val text = bubble.targetText.trim()
            if (text.isEmpty()) continue

            val textRect = bubble.textBounds
            if (textRect.width() <= 0 || textRect.height() <= 0) continue
            val pad = (textRect.height() * 0.08f).toInt().coerceIn(2, 10)
            val extraWidth = (textRect.width() * 0.08f).toInt()

            val left = ((textRect.left - pad - extraWidth / 2) / scale).toInt()
                .coerceIn(0, max(0, screenWidth - MIN_TRANSLATION_WIDTH))
            val top = ((textRect.top - pad) / scale).toInt()
                .coerceIn(0, max(0, screenHeight - MIN_TRANSLATION_HEIGHT))
            val width = ((textRect.width() + pad * 2 + extraWidth) / scale).toInt()
                .coerceAtMost(screenWidth - left)
                .coerceAtMost((screenWidth * MAX_WIDTH_FRACTION).toInt())
            val height = ((textRect.height() + pad * 2) / scale).toInt()
                .coerceAtMost(screenHeight - top)
                .coerceAtMost((screenHeight * MAX_HEIGHT_FRACTION).toInt())
            if (width < MIN_TRANSLATION_WIDTH || height < MIN_TRANSLATION_HEIGHT) continue

            val textColor = when (textColorSetting) {
                TextColor.AUTO -> if (luminance(bubble.fillColor) > 150) Color.BLACK else Color.WHITE
                TextColor.WHITE -> Color.WHITE
                TextColor.BLACK -> Color.BLACK
            }

            val bgColorValue = when (bgColor) {
                BackgroundColor.AUTO -> bubble.fillColor
                BackgroundColor.WHITE -> 0xFFFFFFFF.toInt()
                BackgroundColor.BLACK -> 0xFF000000.toInt()
                BackgroundColor.TRANSPARENT -> Color.TRANSPARENT
            }

            val alpha = opacity.alpha
            val finalBgColor = if (bgColor == BackgroundColor.TRANSPARENT) {
                Color.TRANSPARENT
            } else {
                (bgColorValue and 0xFFFFFF) or (alpha shl 24)
            }

            val translationView = TextView(context).apply {
                this.text = text
                setTextColor(textColor)
                gravity = Gravity.CENTER
                includeFontPadding = false
                textSize = textSizeSetting.sp
                setPadding(dp(2), dp(1), dp(2), dp(1))
                // Teks panjang diperkecil bertahap sampai batas terbaca, bukan
                // dipotong atau meluber keluar area dialog.
                setAutoSizeTextTypeUniformWithConfiguration(
                    9,
                    textSizeSetting.sp.toInt(),
                    1,
                    android.util.TypedValue.COMPLEX_UNIT_SP
                )
                setShadowLayer(
                    2f,
                    0f,
                    0f,
                    if (textColor == Color.BLACK) 0x66FFFFFF else 0x66000000
                )
                background = roundedRect(
                    color = finalBgColor,
                    radius = dp(5),
                    stroke = Color.argb(16, 0, 0, 0)
                )
            }

            val params = WindowManager.LayoutParams(
                width,
                height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = left
                y = top
                ignoreInsets()
            }

            val attached = runCatching {
                windowManager.addView(translationView, params)
                true
            }.getOrDefault(false)
            if (attached) {
                translationViews += translationView
                shown++
                translationView.post {
                    val layout = translationView.layout ?: return@post
                    val needed = layout.height + translationView.paddingTop + translationView.paddingBottom
                    if (needed > translationView.height + dp(2) && translationView.height > 0) {
                        val grown = min(needed, (height * 1.8f).toInt())
                            .coerceAtMost(screenHeight - top)
                            .coerceAtMost((screenHeight * 0.32f).toInt())
                        if (grown > translationView.height) {
                            params.height = grown
                            runCatching { windowManager.updateViewLayout(translationView, params) }
                        }
                    }
                }
            }
        }
        return shown
    }

    fun clearTranslations() {
        if (translationViews.isEmpty()) return
        for (view in translationViews) {
            runCatching { windowManager.removeView(view) }
        }
        translationViews.clear()
    }

    fun hasTranslations(): Boolean = translationViews.isNotEmpty()

    fun flashStatus(text: String, isError: Boolean = false, durationMs: Long = 2400L) {
        handler.removeCallbacks(hidePanelTask)
        setStatus(text, isError)
        if (!isError) handler.postDelayed(hidePanelTask, durationMs)
    }

    private fun fitScrollToContent(top: Int, bottom: Int) {
        val scrollerView = scroller ?: return
        val target = (bottom - top).coerceIn(dp(40), dp(340))
        if (scrollerView.layoutParams.height == target) return
        scrollerView.layoutParams = scrollerView.layoutParams.apply { height = target }
        val container = panel
        val params = panelParams
        if (container != null && params != null && panelAttached) {
            runCatching { windowManager.updateViewLayout(container, params) }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachTouch(view: View, params: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var downRawX = 0f
        var downRawY = 0f
        var dragged = false

        val detector = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    onTranslate()
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    onStop()
                }
            }
        )

        view.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragged = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) dragged = true
                    params.x = (startX + dx).coerceIn(0, max(0, screenWidth - dp(56)))
                    params.y = (startY + dy).coerceIn(0, max(0, screenHeight - dp(56)))
                    runCatching { windowManager.updateViewLayout(view, params) }
                    val refresh = refreshButton
                    val refreshLayout = refreshParams
                    if (refresh != null && refreshLayout != null) {
                        refreshLayout.x = refreshX(params.x)
                        refreshLayout.y = params.y + dp(8)
                        runCatching { windowManager.updateViewLayout(refresh, refreshLayout) }
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragged && panelAttached) {
                        panelParams?.y = panelY()
                        val container = panel
                        val current = panelParams
                        if (container != null && current != null) {
                            runCatching { windowManager.updateViewLayout(container, current) }
                        }
                    }
                    true
                }

                else -> true
            }
        }
    }

    private fun ensurePanel(): LinearLayout {
        panel?.let { return it }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRect(
                color = 0xFFFFFFFF.toInt(),
                radius = dp(16),
                stroke = Color.argb(38, 0, 0, 0)
            )
            setPadding(dp(14), dp(10), dp(14), dp(12))
            elevation = dp(10).toFloat()
        }

        // Header dengan tombol kontrol
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(context).apply {
            text = "MangaLens"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accentColor)
        }

        val refreshBtn = createControlButton(context, "↻", dp(2)) { onRefresh() }
        val pauseBtn = createControlButton(context, "⏸", dp(2)) { onPause() }
        val resumeBtn = createControlButton(context, "▶", dp(2)) { onResume() }
        val settingsBtn = createControlButton(context, "⚙", dp(12)) { onSettings() }
        val closeBtn = createControlButton(context, "×", dp(12)) { hidePanel() }

        // Hirisan tombol horizontal
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(2), 0, dp(2), 0)
        }
        btnRow.addView(refreshBtn, LinearLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.CENTER
        })
        btnRow.addView(pauseBtn, LinearLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.CENTER
        })
        btnRow.addView(resumeBtn, LinearLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.CENTER
        })
        btnRow.addView(settingsBtn, LinearLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.CENTER
        })
        btnRow.addView(closeBtn, LinearLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.CENTER
        })

        header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(btnRow, LinearLayout.LayoutParams(-2, -2))

        val statusView = TextView(context).apply {
            textSize = 12f
            setTextColor(0xFF6B6B73.toInt())
        }
        status = statusView

        val contentView = TextView(context).apply {
            textSize = 14f
            setTextColor(0xFF1B1B1F.toInt())
            setLineSpacing(dp(4).toFloat(), 1f)
            setTextIsSelectable(true)
        }
        contentView.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            fitScrollToContent(top, bottom)
        }

        val scrollerView = ScrollView(context).apply {
            isFillViewport = false
            addView(
                contentView,
                android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        scroller = scrollerView

        container.addView(header)
        container.addView(
            statusView,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) }
        )
        container.addView(
            scrollerView,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }
        )

        panel = container
        return container
    }

    private fun createControlButton(context: Context, text: String, paddingEnd: Int, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 16f
            setTextColor(accentColor)
            setPadding(dp(8), 0, paddingEnd, 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private fun panelWidth(): Int = (screenWidth - dp(24)).coerceAtMost(dp(360))

    private fun panelY(): Int {
        val bubbleTop = bubbleParams?.y ?: (screenHeight / 3)
        val below = bubbleTop + dp(56) + dp(8)
        val approxHeight = dp(360)
        return if (below + approxHeight > screenHeight) {
            (bubbleTop - approxHeight).coerceAtLeast(dp(8))
        } else {
            below
        }
    }

    private fun circle(size: Int, color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setSize(size, size)
    }

    private fun roundedRect(color: Int, radius: Int, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(color)
            setStroke(dp(1), stroke)
        }

    private fun WindowManager.LayoutParams.ignoreInsets() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    private fun luminance(color: Int): Int =
        (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)).toInt()

    private companion object {
        const val TAG = "MangaLensOverlay"

        /** Indigo, sama dengan warna utama aplikasi. */
        const val BUBBLE_COLOR = 0xFF4F46E5.toInt()
        const val MAX_TRANSLATIONS = 40
        const val MIN_TRANSLATION_WIDTH = 30
        const val MIN_TRANSLATION_HEIGHT = 16

        /** Batas ukuran satu tempelan agar tidak pernah menutupi satu layar. */
        const val MAX_WIDTH_FRACTION = 0.9f
        const val MAX_HEIGHT_FRACTION = 0.28f
    }
}
