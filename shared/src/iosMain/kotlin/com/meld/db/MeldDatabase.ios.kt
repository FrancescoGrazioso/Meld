package com.meld.db

import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

// Written after upstream's MusicDatabase.ios.kt and never compiled: no iOS toolchain was available.
@OptIn(ExperimentalForeignApi::class)
actual fun getMeldDatabaseBuilder(context: Any?): RoomDatabase.Builder<MeldDatabase> {
    val path =
        context as? String ?: run {
            val paths = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
            val directory = "${requireNotNull(paths.firstOrNull() as? String)}/meld"
            val fileManager = NSFileManager.defaultManager
            check(
                fileManager.fileExistsAtPath(directory) ||
                    fileManager.createDirectoryAtPath(directory, withIntermediateDirectories = true, attributes = null, error = null),
            )
            "$directory/$MELD_DATABASE_NAME"
        }
    return Room
        .databaseBuilder<MeldDatabase>(name = path)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
}
