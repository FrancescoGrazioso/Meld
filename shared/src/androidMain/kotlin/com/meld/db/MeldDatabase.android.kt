package com.meld.db

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import kotlinx.coroutines.Dispatchers
import org.koin.mp.KoinPlatform

actual fun getMeldDatabaseBuilder(context: Any?): RoomDatabase.Builder<MeldDatabase> {
    val appContext = (context as? Context ?: KoinPlatform.getKoin().get<Context>()).applicationContext
    return Room
        .databaseBuilder<MeldDatabase>(
            context = appContext,
            name = appContext.getDatabasePath(MELD_DATABASE_NAME).absolutePath,
        ).setDriver(AndroidSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
}
