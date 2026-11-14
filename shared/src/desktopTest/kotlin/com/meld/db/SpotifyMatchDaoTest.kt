package com.meld.db

import com.meld.spotify.match.MatchConfidence
import com.meld.spotify.match.MatchQuery
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpotifyMatchDaoTest {
    private fun match(
        spotifyId: String,
        youtubeId: String? = "yt-$spotifyId",
        score: Double = 0.9,
        matcherVersion: Int = 1,
        now: Long = 1_000,
    ) = SpotifyMatchEntity(
        spotifyId = spotifyId,
        youtubeId = youtubeId,
        score = score,
        confidence = if (youtubeId == null) MatchConfidence.NONE else MatchConfidence.HIGH,
        isManualOverride = false,
        matcherVersion = matcherVersion,
        createdAt = now,
        updatedAt = now,
        title = "Señorita",
        artists = listOf("Shawn Mendes", "Camila Cabello, Jr."),
        album = null,
        durationMs = 190_000,
        explicit = null,
        isrc = "USUM71911283",
    )

    private fun withDao(block: suspend (SpotifyMatchDao) -> Unit) =
        runBlocking {
            val path = Files.createTempFile("meld-match", ".db")
            Files.deleteIfExists(path)
            val database = getMeldDatabaseBuilder(path.toFile()).build()
            try {
                block(database.spotifyMatchDao())
            } finally {
                database.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun automaticMatchRoundTripsAndKeepsItsCreationTime() =
        withDao { dao ->
            assertNull(dao.get("a"))
            assertTrue(dao.saveAutomatic(match("a")))
            assertEquals(match("a"), dao.get("a"))
            assertEquals(
                MatchQuery("Señorita", listOf("Shawn Mendes", "Camila Cabello, Jr."), null, 190_000, null, "USUM71911283"),
                dao.get("a")?.toMatchQuery(),
            )

            assertTrue(dao.saveAutomatic(match("a", youtubeId = null, now = 2_000)))
            assertEquals(match("a", youtubeId = null, now = 2_000).copy(createdAt = 1_000), dao.get("a"))
        }

    @Test
    fun manualOverrideSurvivesEveryAutomaticWrite() =
        withDao { dao ->
            dao.saveAutomatic(match("a"))
            dao.saveManualOverride(match("a", youtubeId = "chosen", now = 2_000))
            val override = match("a", youtubeId = "chosen", now = 2_000).copy(isManualOverride = true, createdAt = 1_000)
            assertEquals(override, dao.get("a"))

            assertFalse(dao.saveAutomatic(match("a", youtubeId = "auto", matcherVersion = 9, now = 3_000)))
            assertEquals(-1L, dao.insertIfAbsent(match("a", youtubeId = "reverse")))
            dao.deleteAutomatic("a")
            assertEquals(0, dao.deleteAutomaticOlderThan(Int.MAX_VALUE))
            assertEquals(override, dao.get("a"))

            dao.saveManualOverride(match("a", youtubeId = "chosen-again", now = 4_000))
            assertEquals("chosen-again", dao.get("a")?.youtubeId)
            dao.delete("a")
            assertNull(dao.get("a"))
        }

    @Test
    fun reverseLookupNeverReplacesAnExistingMatchAndIsOrdered() =
        withDao { dao ->
            dao.saveAutomatic(match("album", youtubeId = "video", score = 0.95))
            assertEquals(-1L, dao.insertIfAbsent(match("album", youtubeId = "other-video")))
            assertEquals("video", dao.get("album")?.youtubeId)

            assertTrue(dao.insertIfAbsent(match("single", youtubeId = "video", score = 0.85)) > 0)
            dao.saveManualOverride(match("deluxe", youtubeId = "video", score = 0.1))
            dao.saveAutomatic(match("missing", youtubeId = null))
            assertEquals(listOf("deluxe", "album", "single"), dao.getByYoutubeId("video").map { it.spotifyId })
            assertEquals(emptyList(), dao.getByYoutubeId("unknown"))
        }

    @Test
    fun lookupByManyIdsStaysUnderTheSqliteVariableLimit() =
        withDao { dao ->
            listOf("id-0", "id-20000", "id-39999").forEach { dao.saveAutomatic(match(it)) }
            // More ids than even a current SQLite accepts in one statement (32766).
            val ids = List(40_000) { "id-$it" } + "id-0"
            assertEquals(setOf("id-0", "id-20000", "id-39999"), dao.getAll(ids).map { it.spotifyId }.toSet())
            assertEquals(3, dao.getAll(ids).size)
            assertEquals(emptyList(), dao.getAll(emptyList()))
        }

    @Test
    fun matcherUpgradeDropsOnlyOlderAutomaticMatches() =
        withDao { dao ->
            dao.saveAutomatic(match("old", matcherVersion = 1))
            dao.saveAutomatic(match("current", matcherVersion = 2))
            dao.saveManualOverride(match("override", matcherVersion = 1))
            assertEquals(1, dao.deleteAutomaticOlderThan(2))
            assertEquals(setOf("current", "override"), dao.getAll(listOf("old", "current", "override")).map { it.spotifyId }.toSet())
        }
}
