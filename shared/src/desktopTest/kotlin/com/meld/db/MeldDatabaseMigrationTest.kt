package com.meld.db

import com.meld.spotify.match.MatchConfidence
import com.metrolist.shared.db.JdbcSQLiteDriver
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

// schemas/com.meld.db.MeldDatabase/1.json, as Room wrote it to disk.
private val VERSION_1 =
    listOf(
        "CREATE TABLE IF NOT EXISTS `spotify_match` (`spotifyId` TEXT NOT NULL, `youtubeId` TEXT, `score` REAL NOT NULL, " +
            "`confidence` TEXT NOT NULL, `isManualOverride` INTEGER NOT NULL, `matcherVersion` INTEGER NOT NULL, " +
            "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `title` TEXT NOT NULL, `artists` TEXT NOT NULL, " +
            "`album` TEXT, `durationMs` INTEGER, `explicit` INTEGER, `isrc` TEXT, PRIMARY KEY(`spotifyId`))",
        "CREATE INDEX IF NOT EXISTS `index_spotify_match_youtubeId` ON `spotify_match` (`youtubeId`)",
        "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
        "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'b1596e78bae9c7d19e13f907fd31b143')",
        "INSERT INTO spotify_match VALUES ('sp1', 'yt1', 0.9, 'HIGH', 1, 1, 10, 20, 'Song', '[\"Artist\"]', NULL, 200000, NULL, NULL)",
        "PRAGMA user_version = 1",
    )

class MeldDatabaseMigrationTest {
    @Test
    fun versionOneOpensAsTheCurrentVersionWithItsMatchesAndTheLaterTables() =
        runBlocking<Unit> {
            val file = Files.createTempFile("meld-migration", ".db").toFile()
            try {
                JdbcSQLiteDriver().open(file.path).use { connection ->
                    VERSION_1.forEach { sql -> connection.prepare(sql).use { it.step() } }
                }
                // Room checks the migrated tables against the entities, so a migration that drifts from them fails here.
                val database = buildMeldDatabase(file)
                try {
                    val match = database.spotifyMatchDao().get("sp1")
                    assertEquals("yt1", match?.youtubeId)
                    assertEquals(MatchConfidence.HIGH, match?.confidence)
                    assertEquals(true, match?.isManualOverride)
                    assertEquals(listOf("Artist"), match?.artists)

                    val likes = database.spotifyLikeDao()
                    likes.putOutboxEntry(SpotifyLikeOutboxEntity("yt1", liked = true, notFound = false, createdAt = 1))
                    likes.insertLikedTracks(listOf(SpotifyLikedTrackEntity("sp1", "yt1", pending = false)))
                    assertEquals(listOf("yt1"), likes.outboxIds())
                    assertEquals(listOf("sp1"), likes.likedTracksOf("yt1").map { it.spotifyId })
                    assertEquals(null, likes.likedTrack("sp1")?.addedAt)
                    likes.setLikedTrackDates(mapOf("sp1" to 5L))
                    assertEquals(5L, likes.likedTrack("sp1")?.addedAt)

                    val qobuz = QobuzMatchEntity("yt1", "q1", hires = true, bitDepth = 24, samplingRateKhz = 96.0, matchedAt = 1)
                    database.qobuzMatchDao().insertIfAbsent(listOf(qobuz, qobuz.copy(qobuzTrackId = "q2")))
                    assertEquals(listOf(qobuz), database.qobuzMatchDao().all())
                } finally {
                    database.close()
                }
            } finally {
                file.delete()
            }
        }

    @Test
    fun versionOneIsNotOpenedWithoutTheMigration() =
        runBlocking<Unit> {
            val file = Files.createTempFile("meld-migration", ".db").toFile()
            try {
                JdbcSQLiteDriver().open(file.path).use { connection ->
                    VERSION_1.forEach { sql -> connection.prepare(sql).use { it.step() } }
                }
                val database = getMeldDatabaseBuilder(file).build()
                try {
                    assertFails { database.spotifyMatchDao().get("sp1") }
                } finally {
                    database.close()
                }
            } finally {
                file.delete()
            }
        }
}
