package com.meld.db

import androidx.room3.Room
import androidx.room3.RoomDatabase
import com.metrolist.shared.db.JdbcSQLiteDriver
import com.metrolist.shared.utils.XdgUtils
import kotlinx.coroutines.Dispatchers
import java.io.File

actual fun getMeldDatabaseBuilder(context: Any?): RoomDatabase.Builder<MeldDatabase> {
    val dbFile = context as? File ?: File(XdgUtils.getXdgDataHome(), "meld/$MELD_DATABASE_NAME")
    dbFile.parentFile.mkdirs()
    return Room
        .databaseBuilder<MeldDatabase>(name = dbFile.absolutePath)
        .setDriver(JdbcSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
}
