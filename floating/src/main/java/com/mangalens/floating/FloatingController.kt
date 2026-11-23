package com.mangalens.floating

/**
 * Kendali bubble overlay. Implementasi foreground service + WindowManager
 * masuk pada Tahap 7.
 */
interface FloatingController {
    fun show()
    fun hide()
    fun isShowing(): Boolean
}
