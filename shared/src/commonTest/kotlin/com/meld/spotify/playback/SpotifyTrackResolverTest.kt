package com.meld.spotify.playback

import com.meld.spotify.match.TrackMatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SpotifyTrackResolverTest {
    private val dao = FakeMatchDao()
    private val youtube = FakeYouTube()
    private var now = 0L

    private fun TestScope.resolver(
        maxConcurrentSearches: Int = 3,
        includeIsrc: Boolean = false,
    ) = SpotifyTrackResolver(dao, youtube::search, youtube::songsById, backgroundScope, { now }, includeIsrc, maxConcurrentSearches)

    private fun SpotifyResolution.songId() = assertIs<SpotifyResolution.Matched>(this).song.id

    @Test
    fun storedMatchesAnswerWithoutSearching() =
        runTest {
            dao.rows["sp1"] = storedMatch(1)
            dao.rows["sp2"] = storedMatch(2, youtubeId = "picked", manual = true, version = 0)
            val results = resolver().resolveAll(tracks(1..2))
            assertEquals(listOf("yt1", "picked"), results.map { it.songId() })
            assertTrue(youtube.calls.isEmpty())
            assertEquals(1, youtube.metadataRequests)
        }

    @Test
    fun searchResultIsStoredWithTheMatcherVersion() =
        runTest {
            assertEquals("yt1", resolver().resolve(track(1)).songId())
            val row = dao.rows.getValue("sp1")
            assertEquals("yt1", row.youtubeId)
            assertEquals(TrackMatcher.VERSION, row.matcherVersion)
            assertNull(row.explicit)
        }

    @Test
    fun matchFromAnOlderMatcherIsSearchedAgain() =
        runTest {
            dao.rows["sp1"] = storedMatch(1, youtubeId = "old", version = TrackMatcher.VERSION - 1)
            assertEquals("yt1", resolver().resolve(track(1)).songId())
            assertEquals("yt1", dao.rows.getValue("sp1").youtubeId)
        }

    @Test
    fun manualOverrideIsNeverSearchedOrReplaced() =
        runTest {
            val override = storedMatch(1, youtubeId = "picked", manual = true)
            dao.rows["sp1"] = override
            youtube.unloadable += "picked"
            assertIs<SpotifyResolution.Failed>(resolver().resolve(track(1)))
            assertTrue(youtube.calls.isEmpty())
            assertEquals(override, dao.rows["sp1"])
        }

    @Test
    fun storedMatchWhoseVideoIsGoneIsSearchedAgain() =
        runTest {
            dao.rows["sp1"] = storedMatch(1, youtubeId = "gone")
            youtube.unloadable += "gone"
            assertEquals("yt1", resolver().resolve(track(1)).songId())
            assertEquals("yt1", dao.rows.getValue("sp1").youtubeId)
        }

    @Test
    fun noMatchIsRememberedOnlyForAWhile() =
        runTest {
            youtube.missing += "Song 1"
            val resolver = resolver()
            assertEquals(SpotifyResolution.NoMatch, resolver.resolve(track(1)))
            assertNull(dao.rows.getValue("sp1").youtubeId)
            val searches = youtube.calls.size

            youtube.missing.clear()
            now += 6.days.inWholeMilliseconds
            assertEquals(SpotifyResolution.NoMatch, resolver.resolve(track(1)))
            assertEquals(searches, youtube.calls.size)

            now += 2.days.inWholeMilliseconds
            assertEquals("yt1", resolver.resolve(track(1)).songId())
        }

    @Test
    fun youtubeFailureIsNotStoredAsNoMatch() =
        runTest {
            youtube.failing += "Song 1"
            val resolver = resolver()
            assertIs<SpotifyResolution.Failed>(resolver.resolve(track(1)))
            assertTrue(dao.rows.isEmpty())

            youtube.failing.clear()
            assertEquals("yt1", resolver.resolve(track(1)).songId())
        }

    @Test
    fun oneFailedSearchIsRetried() =
        runTest {
            youtube.failing += "Song 1"
            val result = async { resolver().resolve(track(1)) }
            runCurrent()
            youtube.failing.clear()
            advanceTimeBy(2.seconds)
            assertEquals("yt1", result.await().songId())
        }

    @Test
    fun lowConfidenceMatchIsAcceptedAndFlagged() =
        runTest {
            youtube.results["Song 1"] = listOf(ytSong("live", "Song 1 (Live)"))
            val result = assertIs<SpotifyResolution.Matched>(resolver().resolve(track(1)))
            assertEquals("live", result.song.id)
            assertTrue(result.lowConfidence)
            assertTrue(assertIs<SpotifyResolution.Matched>(resolver().resolve(track(1))).lowConfidence)
        }

    @Test
    fun isrcIsSearchedOnlyWhenEnabled() =
        runTest {
            youtube.missing += "Song 1"
            resolver().resolve(track(1, isrc = "USUM71911283"))
            assertTrue(youtube.calls.none { it.isIsrc })

            dao.rows.clear()
            resolver(includeIsrc = true).resolve(track(1, isrc = "USUM71911283"))
            assertTrue(youtube.calls.any { it.isIsrc })
        }

    @Test
    fun searchesOfAllCallersShareOneLimit() =
        runTest {
            youtube.gate = CompletableDeferred()
            val resolver = resolver(maxConcurrentSearches = 2)
            val batch = async { resolver.resolveAll(tracks(1..5)) }
            val single = async { resolver.resolve(track(6)) }
            runCurrent()
            assertEquals(2, youtube.active)

            youtube.gate?.complete(Unit)
            runCurrent()
            assertEquals(2, youtube.maxActive)
            assertEquals((1..5).map { "yt$it" }, batch.await().map { it.songId() })
            assertEquals("yt6", single.await().songId())
        }

    @Test
    fun sameTrackAskedTwiceIsSearchedOnce() =
        runTest {
            youtube.gate = CompletableDeferred()
            val resolver = resolver()
            val first = async { resolver.resolve(track(1)) }
            val second = async { resolver.resolve(track(1)) }
            runCurrent()
            assertEquals(1, youtube.calls.size)

            youtube.gate?.complete(Unit)
            assertEquals("yt1", first.await().songId())
            assertEquals("yt1", second.await().songId())
            assertEquals(1, youtube.calls.size)
        }

    @Test
    fun sharedSearchStopsOnlyWhenEveryCallerLeft() =
        runTest {
            youtube.gate = CompletableDeferred()
            val resolver = resolver()
            val first = launch { resolver.resolve(track(1)) }
            val second = launch { resolver.resolve(track(1)) }
            runCurrent()

            first.cancel()
            runCurrent()
            assertEquals(1, youtube.active)

            second.cancel()
            runCurrent()
            assertEquals(0, youtube.active)
            assertTrue(dao.rows.isEmpty())
        }
}
