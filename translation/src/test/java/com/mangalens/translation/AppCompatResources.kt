package com.mangalens.translation

import android.content.Context
import androidx.test.core.app.ApplicationProvider

object AppCompatResources {
    fun getStringArray(id: Int): Array<String> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return context.resources.getStringArray(id)
    }
}
