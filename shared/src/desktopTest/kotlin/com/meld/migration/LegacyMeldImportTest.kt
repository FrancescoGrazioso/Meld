package com.meld.migration

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.meld.db.MeldDatabase
import com.meld.db.QobuzMatchEntity
import com.meld.db.getMeldDatabaseBuilder
import com.meld.spotify.match.MatchConfidence
import com.metrolist.shared.backup.copyLegacyBackupTables
import com.metrolist.shared.db.JdbcSQLiteDriver
import com.metrolist.shared.db.getDatabaseBuilder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull

/**
 * Databases are built from `resources/meld/legacy`: the old app's exported schemas (`app/schemas/…/N.json`
 * of the pre-KMP tree, `upstream-38` from Metrolist v13.7.0), one statement per line as Room created them.
 * The rows are made up.
 */
class LegacyMeldImportTest {
    private val libraryTables =
        listOf("song", "artist", "song_artist_map", "playlist", "playlist_song_map", "event", "lyrics", "format", "search_history")

    private fun withFiles(block: suspend (File) -> Unit) =
        runBlocking {
            val directory = Files.createTempDirectory("legacy-meld").toFile()
            try {
                block(directory)
            } finally {
                directory.deleteRecursively()
            }
        }

    private suspend fun <T> File.meld(block: suspend (MeldDatabase) -> T): T {
        val database = getMeldDatabaseBuilder(resolve("meld.db")).build()
        try {
            return block(database)
        } finally {
            database.close()
        }
    }

    /** An old database with a small library and, where the schema has them, Meld's own rows. */
    private fun File.legacy(
        schema: String,
        name: String = "legacy.db",
    ): File =
        resolve(name).also { file ->
            file.sql { db ->
                val statements = checkNotNull(LegacyMeldImportTest::class.java.getResource("/meld/legacy/$schema.sql")).readText().lines()
                statements.filter(String::isNotBlank).forEach(db::execSQL)
                db.insert("song", "id" to "s1", "title" to "First", "liked" to 1, "isLocal" to 0)
                db.insert("song", "id" to "s2", "title" to "Second", "localPath" to "content://media/2", "isrc" to "QZAAA0000001")
                db.insert("artist", "id" to "a1", "name" to "Artist", "spotifyId" to "spotify-artist")
                db.insert("song_artist_map", "songId" to "s1", "artistId" to "a1")
                db.insert("playlist", "id" to "p1", "name" to "Mine")
                db.insert("playlist_song_map", "playlistId" to "p1", "songId" to "s1")
                db.insert("playlist_song_map", "playlistId" to "p1", "songId" to "s2", "position" to 1)
                db.insert("event", "songId" to "s1", "timestamp" to 5, "playTime" to 1000)
                db.insert("lyrics", "id" to "s1", "lyrics" to "la la")
                db.insert("format", "id" to "s1", "mimeType" to "audio/webm", "codecs" to "opus")
                db.insert("search_history", "query" to "something")
                db.insert("speed_dial_item", "id" to "s1", "title" to "First", "type" to "SONG")
                db.insert("spotify_match", "spotifyId" to "auto", "youtubeId" to "s2", "title" to "Second", "artist" to "Artist")
                if ("isManualOverride" in db.columns("spotify_match")) {
                    db.insert(
                        "spotify_match",
                        "spotifyId" to "manual",
                        "youtubeId" to "s1",
                        "title" to "First (Live)",
                        "artist" to "Artist",
                        "matchScore" to 0.25,
                        "cachedAt" to 111,
                        "isManualOverride" to 1,
                    )
                }
                db.insert("qobuz_match", "youtubeId" to "s1", "qobuzTrackId" to "q1", "hires" to 1, "bitDepth" to 24, "matchedAt" to 222)
            }
        }

    /** What upstream's `convertLegacyDatabase` does with the file once Meld's step has run. */
    private suspend fun File.convert(legacy: File): File {
        val current = resolve("current-${legacy.name}")
        val empty = getDatabaseBuilder(current).build()
        try {
            empty.dao().eventCount().first()
        } finally {
            empty.close()
        }
        val version =
            legacy.sql { db ->
                db.prepare("PRAGMA user_version").use {
                    it.step()
                    it.getLong(0).toInt()
                }
            }
        current.sql { copyLegacyBackupTables(it, legacy.path, version) }
        // Room refuses a file whose schema is not exactly its own.
        val converted = getDatabaseBuilder(current).build()
        try {
            assertEquals(1, converted.dao().eventCount().first())
        } finally {
            converted.close()
        }
        return current
    }

    private suspend fun File.prepare(legacy: File): LegacyMeldImport? = meld { meld -> legacy.sql { importLegacyMeldDatabase(it, meld) } }

    @Test
    fun everyReleasedSchemaIsImportedWithItsLibraryAndMeldData() =
        withFiles { directory ->
            for (version in 33..41) {
                val legacy = directory.legacy("meld-$version", "legacy-$version.db")
                val before = legacy.sql { db -> libraryTables.associateWith(db::count) }
                directory.resolve("meld.db").delete()

                val found = directory.prepare(legacy)

                val hasOverrides = version >= 34
                val hasQobuz = version >= 38
                assertEquals(
                    LegacyMeldImport(
                        schemaVersion = version,
                        manualMatches = if (hasOverrides) 1 else 0,
                        automaticMatches = 1,
                        qobuzMatches = if (hasQobuz) 1 else 0,
                        localSongs = if (version >= 36) 1 else 0,
                        songIsrcs = if (hasQobuz) 1 else 0,
                        spotifyArtists = if (version >= 37) 1 else 0,
                    ),
                    found,
                    "schema $version",
                )
                directory.convert(legacy).sql { db ->
                    assertEquals(before, libraryTables.associateWith(db::count), "schema $version")
                    assertEquals(1, db.count("speed_dial"), "schema $version")
                    assertEquals("First", db.text("SELECT title FROM song WHERE id = 's1' AND liked = 1"))
                    assertEquals("Mine", db.text("SELECT name FROM playlist WHERE id = 'p1'"))
                    assertEquals("s2", db.text("SELECT songId FROM playlist_song_map WHERE playlistId = 'p1' AND position = 1"))
                    assertEquals("la la", db.text("SELECT lyrics FROM lyrics WHERE id = 's1'"))
                }
                directory.meld { meld ->
                    val manual = meld.spotifyMatchDao().get("manual")
                    if (hasOverrides) {
                        assertEquals(true, manual?.isManualOverride)
                        assertEquals("s1", manual?.youtubeId)
                        assertEquals(MatchConfidence.HIGH, manual?.confidence)
                        assertEquals("First (Live)", manual?.title)
                        assertEquals(listOf("Artist"), manual?.artists)
                        assertEquals(111L, manual?.createdAt)
                    } else {
                        assertNull(manual)
                    }
                    // The old matcher's own choices are not carried over.
                    assertNull(meld.spotifyMatchDao().get("auto"))
                    assertEquals(
                        if (hasQobuz) {
                            listOf(
                                QobuzMatchEntity("s1", "q1", hires = true, bitDepth = 24, samplingRateKhz = null, matchedAt = 222),
                            )
                        } else {
                            emptyList()
                        },
                        meld.qobuzMatchDao().all(),
                    )
                }
            }
        }

    @Test
    fun upstreamRejectsAnOldMeldDatabaseThatWasNotPrepared() =
        withFiles { directory ->
            for (version in listOf(39, 40, 41)) {
                assertFails { directory.convert(directory.legacy("meld-$version", "legacy-$version.db")) }
            }
        }

    @Test
    fun preparingAgainAfterAnInterruptionChangesNothing() =
        withFiles { directory ->
            // The first run copied Meld's data and then died before the snapshot was stripped; onboarding starts over.
            directory.prepare(directory.legacy("meld-41", "interrupted.db"))
            val remade =
                directory.meld { meld ->
                    val matches = meld.spotifyMatchDao()
                    // In between, the user picked another video for the same track.
                    matches.saveManualOverride(checkNotNull(matches.get("manual")).copy(youtubeId = "s2", updatedAt = 999))
                    checkNotNull(matches.get("manual"))
                }
            val legacy = directory.legacy("meld-41")

            assertEquals(1, directory.prepare(legacy)?.manualMatches)
            // A snapshot that is already stripped is not an old Meld database any more.
            assertNull(directory.prepare(legacy))

            directory.meld { meld ->
                assertEquals(remade, meld.spotifyMatchDao().get("manual"))
                assertEquals(1, meld.qobuzMatchDao().all().size)
            }
            directory.convert(legacy).sql { assertEquals(2, it.count("song")) }
        }

    @Test
    fun plainUpstreamDatabaseIsNotTouchedAndImportsAsBefore() =
        withFiles { directory ->
            val legacy = directory.legacy("upstream-38")
            val untouched = legacy.readBytes()

            assertNull(directory.prepare(legacy))

            assertContentEquals(untouched, legacy.readBytes())
            directory.meld { assertNull(it.spotifyMatchDao().get("manual")) }
            directory.convert(legacy).sql { db ->
                assertEquals(2, db.count("song"))
                assertEquals(1, db.count("speed_dial"))
                assertEquals("Mine", db.text("SELECT name FROM playlist WHERE id = 'p1'"))
            }
        }

    @Test
    fun unknownDatabasesAreLeftForUpstreamToReject() =
        withFiles { directory ->
            val newer = directory.legacy("meld-41", "newer.db")
            newer.sql { it.execSQL("PRAGMA user_version = 42") }
            val foreign = directory.resolve("foreign.db")
            foreign.sql { it.execSQL("CREATE TABLE spotify_match (spotifyId TEXT PRIMARY KEY, youtubeId TEXT)") }

            for (file in listOf(newer, foreign)) {
                val untouched = file.readBytes()
                assertNull(directory.prepare(file))
                assertContentEquals(untouched, file.readBytes())
                assertFails { directory.convert(file) }
            }
            directory.meld { assertNull(it.spotifyMatchDao().get("manual")) }
        }
}

private inline fun <T> File.sql(block: (SQLiteConnection) -> T): T = JdbcSQLiteDriver().open(path).use(block)

private fun SQLiteConnection.count(table: String): Int =
    prepare("SELECT COUNT(*) FROM `$table`").use {
        it.step()
        it.getLong(0).toInt()
    }

private fun SQLiteConnection.text(sql: String): String? = prepare(sql).use { if (it.step() && !it.isNull(0)) it.getText(0) else null }

private fun SQLiteConnection.columns(table: String): Map<String, Boolean> =
    prepare("PRAGMA table_info(`$table`)").use {
        buildMap {
            // Required: NOT NULL, no default, and not part of the key, which is either given or a row id.
            while (it.step()) put(it.getText(1), it.getLong(3) != 0L && it.isNull(4) && it.getLong(5) == 0L)
        }
    }

/** Inserts [values] for the columns this schema version has, and a filler for every other required one. */
private fun SQLiteConnection.insert(
    table: String,
    vararg values: Pair<String, Any?>,
) {
    val given = values.toMap()
    val columns = columns(table)
    if (columns.isEmpty()) return
    val row = columns.filter { (name, required) -> name in given || required }.mapValues { (name, _) -> given[name] ?: 0 }
    val literals = row.values.map { if (it is String) "'$it'" else it.toString() }
    execSQL("INSERT INTO `$table` (${row.keys.joinToString { "`$it`" }}) VALUES (${literals.joinToString()})")
}
