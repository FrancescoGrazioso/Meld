package com.meld.migration

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import com.meld.db.MeldDatabase
import com.meld.db.buildMeldDatabase
import com.metrolist.android.onboarding.LegacyInstallMigration
import com.metrolist.shared.db.DATABASE_VERSION
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** The onboarding migration of upstream, end to end, over a database of the pre-KMP Meld with made-up rows. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class LegacyMeldInstallMigrationTest {
    private fun SQLiteConnection.int(sql: String): Int =
        prepare(sql).use {
            it.step()
            it.getLong(0).toInt()
        }

    /** Inserts [values] and a filler for every other column that needs one. */
    private fun SQLiteConnection.insert(
        table: String,
        vararg values: Pair<String, Any>,
    ) {
        val row = values.toMap(linkedMapOf())
        prepare("PRAGMA table_info(`$table`)").use {
            while (it.step()) {
                if (it.getLong(3) != 0L && it.isNull(4) && it.getLong(5) == 0L) row.getOrPut(it.getText(1)) { 0 }
            }
        }
        val literals = row.values.map { if (it is String) "'$it'" else it.toString() }
        execSQL("INSERT INTO `$table` (${row.keys.joinToString { "`$it`" }}) VALUES (${literals.joinToString()})")
    }

    @Test
    fun oldMeldLibraryIsMigratedInPlaceAndItsManualMatchesMoveToMeldDatabase() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            Settings.replaceSharedForTest(Settings(MapSettings()))
            val meld = buildMeldDatabase(context)
            startKoin { modules(module { single<MeldDatabase> { meld } }) }
            val songDatabase = context.getDatabasePath("song.db")
            try {
                songDatabase.parentFile?.mkdirs()
                // The old app's exported schema 41, shared with the tests of the common code.
                val schema = File("../shared/src/desktopTest/resources/meld/legacy/meld-41.sql").readLines()
                AndroidSQLiteDriver().open(songDatabase.path).use { db ->
                    schema.filter(String::isNotBlank).forEach(db::execSQL)
                    db.insert("song", "id" to "s1", "title" to "First", "liked" to 1, "localPath" to "content://media/1")
                    db.insert("artist", "id" to "a1", "name" to "Artist", "spotifyId" to "spotify-artist")
                    db.insert("song_artist_map", "songId" to "s1", "artistId" to "a1")
                    db.insert("playlist", "id" to "p1", "name" to "Mine")
                    db.insert("playlist_song_map", "playlistId" to "p1", "songId" to "s1")
                    db.insert("event", "songId" to "s1")
                    db.insert("speed_dial_item", "id" to "s1", "title" to "First", "type" to "SONG")
                    db.insert("spotify_match", "spotifyId" to "manual", "youtubeId" to "s1", "title" to "First", "isManualOverride" to 1)
                    db.insert("spotify_match", "spotifyId" to "auto", "youtubeId" to "s1", "title" to "First")
                    db.insert("qobuz_match", "youtubeId" to "s1", "qobuzTrackId" to "q1")
                }
                val migration = LegacyInstallMigration(context)
                assertTrue(migration.hasLegacyInstallation())

                migration.migrate().getOrThrow()

                assertFalse(migration.hasLegacyInstallation())
                AndroidSQLiteDriver().open(songDatabase.path).use { db ->
                    assertEquals(DATABASE_VERSION, db.int("PRAGMA user_version"))
                    for (table in listOf("song", "artist", "song_artist_map", "playlist", "playlist_song_map", "event", "speed_dial")) {
                        assertEquals(table, 1, db.int("SELECT COUNT(*) FROM `$table`"))
                    }
                    assertEquals(0, db.int("SELECT COUNT(*) FROM sqlite_master WHERE name IN ('spotify_match', 'qobuz_match')"))
                }
                assertEquals(true, meld.spotifyMatchDao().get("manual")?.isManualOverride)
                assertNull(meld.spotifyMatchDao().get("auto"))
                assertEquals(listOf("q1"), meld.qobuzMatchDao().all().map { it.qobuzTrackId })
                assertFalse(File("${songDatabase.path}.v13-migration-backup").exists())
            } finally {
                stopKoin()
                meld.close()
                context.deleteDatabase("song.db")
                context.deleteDatabase("meld.db")
                Settings.replaceSharedForTest(null)
            }
        }
}
