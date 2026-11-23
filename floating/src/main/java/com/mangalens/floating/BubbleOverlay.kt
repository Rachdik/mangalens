package com.mangalens.floating

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import com.mangalens.inpainting.TranslatedBubble
import kotlin.math.abs
import kotlin.math.max

/**
 * Bubble melayang, teks terjemahan yang ditempel di atas balon asli, dan
 * panel status kecil. Semua view dibuat programatik agar tidak bergantung
 * pada resource tema aplikasi pemanggil.
 */
class BubbleOverlay(
    private val context: Context,
    private val onTranslate: () -> Unit,
    private val onStop: () -> Unit
) {

    private val windowManager: WindowManager = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private val screenWidth = context.resources.displayMetrics.widthPixels
    private val screenHeight = context.resources.displayMetrics.heightPixels

    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var panel: LinearLayout? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var scroll: ScrollView? = null
    private var content: TextView? = null
    private var status: TextView? = null

    private var bubbleAttached = false
    private var panelAttached = false

    /** Teks terjemahan yang ditempel langsung di atas balon aslinya. */
    private val translationViews = mutableListOf<View>()
    private val handler = Handler(Looper.getMainLooper())
    private val hidePanelTask = Runnable { hidePanel() }

    fun show() {
        if (bubbleAttached) return
        val view = TextView(context).apply {
            text = "訳"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = circle(dp(56), 0xFF1F6FEB.toInt())
            elevation = dp(8).toFloat()
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
        }

        attachTouch(view, params)

        runCatching {
            windowManager.addView(view, params)
            bubble = view
            bubbleParams = params
            bubbleAttached = true
        }
    }

    fun hide() {
        clearTranslations()
        hidePanel()
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

    /**
     * Sembunyikan bubble + panel sementara. Penting: overlay sendiri ikut
     * terekam MediaProjection, jadi harus dilepas sebelum screenshot.
     */
    fun setHidden(hidden: Boolean) {
        if (hidden) {
            // Overlay sendiri ikut terekam, termasuk teks terjemahan.
            clearTranslations()
            val view = bubble
            if (view != null && bubbleAttached) {
                runCatching { windowManager.removeView(view) }
            }
            bubbleAttached = false
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

    /**
     * Menempelkan terjemahan tepat di posisi balon pada layar: latar view
     * memakai warna balon asli sehingga teks sumber tertutup, lalu teks
     * Indonesia ditulis di atasnya. [scale] adalah skala bitmap tangkapan
     * layar terhadap layar sebenarnya.
     *
     * @return jumlah balon yang berhasil ditempel.
     */
    fun showTranslations(bubbles: List<TranslatedBubble>, scale: Float): Int {
        clearTranslations()
        if (bubbles.isEmpty() || scale <= 0f) return 0

        var shown = 0
        for (bubble in bubbles) {
            if (shown >= MAX_TRANSLATIONS) break
            val text = bubble.targetText.trim()
            if (text.isEmpty()) continue

            val left = (bubble.bounds.left / scale).toInt()
            val top = (bubble.bounds.top / scale).toInt()
            val width = (bubble.bounds.width() / scale).toInt()
            val height = (bubble.bounds.height() / scale).toInt()
            if (width < MIN_TRANSLATION_WIDTH || height < MIN_TRANSLATION_HEIGHT) continue

            val view = TextView(context).apply {
                this.text = text
                setTextColor(bubble.textColor)
                gravity = Gravity.CENTER
                includeFontPadding = false
                setPadding(dp(3), dp(2), dp(3), dp(2))
                background = roundedRect(
                    color = bubble.fillColor,
                    radius = dp(6),
                    stroke = Color.argb(28, 0, 0, 0)
                )
            }
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                view,
                MIN_TEXT_SP,
                MAX_TEXT_SP,
                1,
                TypedValue.COMPLEX_UNIT_SP
            )

            val params = WindowManager.LayoutParams(
                width,
                height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    // Koordinat tangkapan layar dihitung dari pojok layar,
                    // termasuk area status bar. Samakan asal koordinatnya.
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = left.coerceAtLeast(0)
                y = top.coerceAtLeast(0)
            }

            val attached = runCatching {
                windowManager.addView(view, params)
                true
            }.getOrDefault(false)
            if (attached) {
                translationViews += view
                shown++
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

    /** Menampilkan status singkat lalu panel ditutup otomatis agar layar bersih. */
    fun flashStatus(text: String, isError: Boolean = false, durationMs: Long = 2400L) {
        handler.removeCallbacks(hidePanelTask)
        setStatus(text, isError)
        if (!isError) handler.postDelayed(hidePanelTask, durationMs)
    }

    private fun fitScrollToContent(top: Int, bottom: Int) {
        val scroller = scroll ?: return
        val target = (bottom - top).coerceIn(dp(40), dp(340))
        if (scroller.layoutParams.height == target) return
        scroller.layoutParams = scroller.layoutParams.apply { height = target }
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
                color = 0xFFFDFBFA.toInt(),
                radius = dp(16),
                stroke = Color.argb(38, 0, 0, 0)
            )
            setPadding(dp(14), dp(10), dp(14), dp(12))
            elevation = dp(10).toFloat()
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(context).apply {
            text = "MangaLens"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF1F6FEB.toInt())
        }
        val close = TextView(context).apply {
            text = "×"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(0xFF6B6B73.toInt())
            setPadding(dp(12), 0, dp(4), 0)
            setOnClickListener { hidePanel() }
        }
        header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(close, LinearLayout.LayoutParams(-2, -2))

        val statusView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(0xFF6B6B73.toInt())
        }
        status = statusView

        val contentView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(0xFF1B1B1F.toInt())
            setLineSpacing(dp(4).toFloat(), 1f)
            setTextIsSelectable(true)
        }
        contentView.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            fitScrollToContent(top, bottom)
        }
        content = contentView

        val scroller = ScrollView(context).apply {
            isFillViewport = false
            addView(
                contentView,
                android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        scroll = scroller

        container.addView(header)
        container.addView(
            statusView,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) }
        )
        container.addView(
            scroller,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }
        )

        panel = container
        return container
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

    private fun dp(value: Int): Int = (value * density).toInt()

    private companion object {
        const val MAX_TRANSLATIONS = 40
        const val MIN_TRANSLATION_WIDTH = 30
        const val MIN_TRANSLATION_HEIGHT = 16
        const val MIN_TEXT_SP = 6
        const val MAX_TEXT_SP = 26
    }
}
