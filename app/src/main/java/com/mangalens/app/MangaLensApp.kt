package com.mangalens.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.mangalens.core.di.Dependencies
import com.mangalens.core.theme.AppColorStore
import com.mangalens.core.theme.ThemeStore
import com.mangalens.ui.theme.MangaLensTheme
import com.mangalens.storage.data.MangaLensDatabase
import com.mangalens.storage.data.RoomHistoryStore

class MangaLensApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Baca preferensi tampilan lebih awal agar tema sudah benar sejak frame pertama.
        ThemeStore.init(this)
        AppColorStore.init(this)
        AppCompatDelegate.setDefaultNightMode(MangaLensTheme.nightModeFor(ThemeStore.mode))
        val database = MangaLensDatabase.get(this)
        Dependencies.historyStore = RoomHistoryStore(database.historyDao())
    }
}
