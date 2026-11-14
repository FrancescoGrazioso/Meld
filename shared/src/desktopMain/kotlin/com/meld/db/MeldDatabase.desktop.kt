package com.meld.db

import androidx.room3.Room
import androidx.room3.RoomDatabase
import com.metrolist.shared.db.JdbcSQLiteDriver
import com.metrolist.shared.utils.XdgUtils
import kotlinx.coroutines.Dispatchers
import org.koin.dsl.module
import java.io.File

fun getMeldDatabaseBuilder(
    dbFile: File = File(XdgUtils.getXdgDataHome(), "metrolist/$MELD_DATABASE_NAME"),
): RoomDatabase.Builder<MeldDatabase> {
    dbFile.parentFile.mkdirs()
    return Room
        .databaseBuilder<MeldDatabase>(name = dbFile.absolutePath)
        .setDriver(JdbcSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
}

val meldDatabaseModule =
    module {
        single<MeldDatabase> { getMeldDatabaseBuilder().build() }
        single<SpotifyMatchDao> { get<MeldDatabase>().spotifyMatchDao() }
    }
