package com.meld.migration

import androidx.sqlite.driver.AndroidSQLiteDriver
import com.meld.db.MeldDatabase
import com.metrolist.shared.youtube.logging.YtLogger
import org.koin.mp.KoinPlatform
import java.io.File

/** Upstream's legacy conversion calls this on the copy it is about to convert; see [importLegacyMeldDatabase]. */
suspend fun prepareLegacyMeldDatabase(legacy: File) {
    AndroidSQLiteDriver().open(legacy.path).use { connection ->
        importLegacyMeldDatabase(connection, KoinPlatform.getKoin().get<MeldDatabase>()) { YtLogger.w("MeldMigration", it) }
    }
}
