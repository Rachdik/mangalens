package com.mangalens.storage.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [HistoryEntity::class],
    version = 1,
    exportSchema = true
)
abstract class MangaLensDatabase : RoomDatabase() {

    abstract fun historyDao(): HistoryDao

    companion object {
        private const val DB_NAME = "mangalens.db"

        @Volatile
        private var instance: MangaLensDatabase? = null

        fun get(context: Context): MangaLensDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MangaLensDatabase::class.java,
                    DB_NAME
                ).build().also { instance = it }
            }
    }
}
