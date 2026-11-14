package com.meld.db

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import kotlinx.coroutines.Dispatchers
import org.koin.dsl.module

fun getMeldDatabaseBuilder(context: Context): RoomDatabase.Builder<MeldDatabase> =
    Room
        .databaseBuilder<MeldDatabase>(
            context = context.applicationContext,
            name = context.getDatabasePath(MELD_DATABASE_NAME).absolutePath,
        ).setDriver(AndroidSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)

val meldDatabaseModule =
    module {
        single<MeldDatabase> { getMeldDatabaseBuilder(get<Context>()).build() }
        single<SpotifyMatchDao> { get<MeldDatabase>().spotifyMatchDao() }
    }
