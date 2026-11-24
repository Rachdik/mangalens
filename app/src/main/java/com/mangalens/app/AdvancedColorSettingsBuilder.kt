package com.mangalens.app

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.mangalens.core.theme.AppColorRole
import com.mangalens.core.theme.AppColorStore
import com.mangalens.core.theme.AppColorValues
import com.mangalens.ui.R

/**
 * Builder kartu pengaturan warna lanjutan di SettingsActivity.
 *
 * Setiap peran warna mendapat satu kartu kecil berisi:
 * - Swatch preview warna saat ini (live update saat slider digerakkan,
 *   tapi tidak tersimpan permanen sampai tombol Apply ditekan).
 * - SeekBar R/G/B (0–255) dengan label dan nilai angka.
 * - Input HEX manual yang bisa dipakai mengganti nilai langsung.
 *   Input tidak valid ditolak dengan pesan singkat.
 * - Tombol "Terapkan" untuk menyimpan perubahan ke AppColorStore.
 * - Tombol "Kembali ke default" untuk mereset satu peran.
 *
 * Halaman juga punya tombol "Reset semua warna" di bagian bawah.
 */
class AdvancedColorSettingsBuilder(private val activity: SettingsActivity) {

    fun build(role: AppColorRole, label: String, parent: LinearLayout) {
        val card = sectionCard(label, parent)
        val values = AppColorStore.get()
        val currentColor = when (role) {
            AppColorRole.PRIMARY -> values.primary
            AppColorRole.BACKGROUND -> values.background
            AppColorRole.SURFACE -> values.surface
            AppColorRole.TEXT -> values.text
            AppColorRole.SECONDARY_TEXT -> values.secondaryText
            AppColorRole.FLOATING_BUTTON -> values.floatingButton
            AppColorRole.OVERLAY_BACKGROUND -> values.overlayBackground
        }

        var r = Color.red(currentColor)
        var g = Color.green(currentColor)
        var b = Color.blue(currentColor)
        var hexInput = AppColorValues.intToHex(currentColor).takeLast(6)

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        card.addView(container)

        val topRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val swatch = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(currentColor)
                cornerRadius = dp(8).toFloat()
            }
            contentDescription = label
        }
        topRow.addView(swatch, LinearLayout.LayoutParams(dp(44), dp(44)))

        val hexDisplay = TextView(activity).apply {
            text = String.format("#%06X", 0xFFFFFF and currentColor)
            textSize = 16f
            setTextColor(ContextCompat.getColor(activity, R.color.mangalens_on_surface))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) }
            gravity = Gravity.CENTER_VERTICAL
        }
        topRow.addView(hexDisplay)
        container.addView(topRow)

        val seekContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(seekContainer, LinearLayout.LayoutParams(0, -2, 1f).apply { topMargin = dp(10) })

        fun makeSeekBar(labelText: String, initial: Int): SeekBar {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val lbl = TextView(activity).apply {
                text = labelText
                textSize = 13f
                setTextColor(ContextCompat.getColor(activity, R.color.mangalens_muted))
                setPadding(0, 0, dp(8), 0)
            }
            row.addView(lbl)
            val seek = SeekBar(activity).apply {
                max = 255
                progress = initial
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) }
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                        r = if (labelText == "R") progress else r
                        g = if (labelText == "G") progress else g
                        b = if (labelText == "B") progress else b
                        val color = Color.rgb(r, g, b)
                        swatch.background = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            setColor(color)
                            cornerRadius = dp(8).toFloat()
                        }
                        hexDisplay.text = String.format("#%06X", 0xFFFFFF and color)
                        hexInput = String.format("%02X%02X%02X", r, g, b)
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            row.addView(seek)
            seekContainer.addView(row)
            return seek
        }

        makeSeekBar("R", r)
        makeSeekBar("G", g)
        makeSeekBar("B", b)

        val hexRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val hexLabel = TextView(activity).apply {
            text = "HEX"
            textSize = 12f
            setTextColor(ContextCompat.getColor(activity, R.color.mangalens_muted))
            setPadding(0, 0, dp(4), 0)
        }
        hexRow.addView(hexLabel)
        val hexEdit = EditText(activity).apply {
            setText(hexInput)
            hint = "FFAA22"
            textSize = 14f
            setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(ContextCompat.getColor(activity, R.color.mangalens_on_surface))
            setHintTextColor(ContextCompat.getColor(activity, R.color.mangalens_muted))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4); marginEnd = dp(8) }
            setOnFocusChangeListener { view, hasFocus ->
                if (!hasFocus) {
                    val editText = view as EditText
                    val newHex = editText.text.toString().trim()
                    if (AppColorValues.isValidHex(newHex)) {
                        val color = "FF$newHex".toIntOrNull(16) ?: currentColor
                        r = Color.red(color); g = Color.green(color); b = Color.blue(color)
                        swatch.background = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            setColor(color)
                            cornerRadius = dp(8).toFloat()
                        }
                        hexDisplay.text = String.format("#%06X", 0xFFFFFF and color)
                        hexInput = newHex
                    } else {
                        editText.setText(hexInput)
                        activity.toast("Format HEX tidak valid")
                    }
                }
            }
        }
        hexRow.addView(hexEdit)
        container.addView(hexRow)

        val actionRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        container.addView(actionRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        actionRow.addView(
            activity.actionButton("Terapkan") {
                val color = Color.rgb(r, g, b)
                AppColorStore.set(role, color)
                card.background = GradientDrawable().apply {
                    setColor(ContextCompat.getColor(activity, R.color.mangalens_card))
                    cornerRadius = dp(14).toFloat()
                    setStroke(dp(1), ContextCompat.getColor(activity, R.color.mangalens_outline))
                }
                activity.toast("Warna " + label + " disimpan")
            },
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        actionRow.addView(
            activity.actionButton("Reset") {
                AppColorStore.resetRole(role)
                activity.recreate()
            },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) }
        )
    }

    fun buildResetAllButton(parent: LinearLayout) {
        val card = sectionCard("Reset semua warna", parent)
        val resetRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        card.addView(resetRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        resetRow.addView(
            activity.actionButton("Reset semua ke default tema") {
                AppColorStore.resetAll()
                activity.recreate()
            },
            LinearLayout.LayoutParams(0, -2, 1f)
        )
    }

    private fun sectionCard(title: String, parent: LinearLayout): LinearLayout {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(ContextCompat.getColor(activity, R.color.mangalens_card))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), ContextCompat.getColor(activity, R.color.mangalens_outline))
            }
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        card.addView(TextView(activity).apply {
            text = title
            textSize = 14f
            setTextColor(ContextCompat.getColor(activity, R.color.mangalens_on_surface))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(6))
        })
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        return card
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
}
