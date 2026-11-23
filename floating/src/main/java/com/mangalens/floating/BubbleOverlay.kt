package com.mangalens.floating

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max

/**
 * Bubble melayang + panel hasil terjemahan. Semua view dibuat programatik
 * agar tidak bergantung pada resource tema aplikasi pemanggil.
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

    fun showResult(lines: List<TranslatedLine>) {
        showPanel()
        content?.text = lines.joinToString("\n\n") { line ->
            val translated = line.target.ifBlank { "(tidak ada hasil)" }
            "${line.source}\n→ $translated"
        }
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
}
