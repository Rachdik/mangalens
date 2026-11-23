package com.mangalens.app

import android.app.Application
import com.mangalens.core.di.Dependencies
import com.mangalens.storage.data.MangaLensDatabase
import com.mangalens.storage.data.RoomHistoryStore

class MangaLensApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val database = MangaLensDatabase.get(this)
        Dependencies.historyStore = RoomHistoryStore(database.historyDao())
    }
}
