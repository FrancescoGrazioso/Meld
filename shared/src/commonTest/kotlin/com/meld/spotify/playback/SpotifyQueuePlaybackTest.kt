package com.meld.spotify.playback

import com.meld.spotify.SpotifyTrack
import com.metrolist.shared.playback.MusicQueue
import com.metrolist.shared.playback.QUEUE_SOURCE_AUTO_MIX
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SpotifyQueuePlaybackTest {
    private val youtube = FakeYouTube()
    private val player = FakePlayer()

    private fun TestScope.playback(): SpotifyQueuePlayback {
        val resolver = SpotifyTrackResolver(FakeMatchDao(), youtube::search, youtube::songsById, backgroundScope, { 0 })
        return SpotifyQueuePlayback(resolver, player.target(backgroundScope), backgroundScope, Random(7))
    }

    private fun yt(range: IntRange) = range.map { "yt$it" }

    private fun List<SpotifyTrack>.ids() = map { it.id }

    @Test
    fun tappedTrackStartsBeforeItsNeighboursAreResolved() =
        runTest {
            youtube.gate = CompletableDeferred()
            youtube.ungated += "Song 5"
            playback().play(tracks(0..29), startIndex = 5, title = "Mix")
            runCurrent()
            assertEquals(listOf("yt5"), player.ids)
            assertEquals(listOf<String?>("Mix"), player.titles)

            youtube.gate?.complete(Unit)
            runCurrent()
            // One window ahead of the listener and no further, and everything before the tapped track.
            assertEquals(yt(0..15), player.ids)
            assertEquals("yt5", player.queue.currentItem()?.id)
            assertEquals(16, youtube.calls.size)
        }

    @Test
    fun startingInTheMiddleQueuesTheWholeSourceAroundTheTappedTrack() =
        runTest {
            playback().play(tracks(0..27), startIndex = 20)
            runCurrent()
            assertEquals(yt(0..27), player.ids)
            assertEquals("yt20", player.queue.currentItem()?.id)
            assertEquals("yt19", player.queue.getPrevious()?.id)
            assertFalse(spotifySourcePending())
        }

    @Test
    fun earlierAndLaterTracksLandBeforeAContinuationThatIsAlreadyThere() =
        runTest {
            youtube.gate = CompletableDeferred()
            youtube.ungated += "Song 14"
            playback().play(tracks(0..27), startIndex = 14)
            runCurrent()
            assertEquals(listOf("yt14"), player.ids)
            player.continuation(listOf("mix0", "mix1"), force = true)

            youtube.gate?.complete(Unit)
            runCurrent()
            assertEquals(yt(0..24) + listOf("mix0", "mix1"), player.ids)

            repeat(7) { player.queue.getNext() }
            runCurrent()
            assertEquals(yt(0..27) + listOf("mix0", "mix1"), player.ids)
            assertEquals("yt21", player.queue.currentItem()?.id)
        }

    @Test
    fun continuationWaitsUntilTheWholeSourceIsQueued() =
        runTest {
            playback().play(tracks(0..27))
            runCurrent()
            assertEquals(yt(0..10), player.ids)
            assertTrue(spotifySourcePending())
            assertFalse(player.continuation(listOf("mix0")))

            repeat(17) {
                player.queue.getNext()
                runCurrent()
            }
            assertEquals(yt(0..27), player.ids)
            assertFalse(spotifySourcePending())
            assertTrue(player.continuation(listOf("mix0")))
            assertEquals(yt(0..27) + "mix0", player.ids)
        }

    @Test
    fun sourceOfAReplacedQueueNoLongerHoldsContinuationBack() =
        runTest {
            playback().play(tracks(0..27))
            runCurrent()
            assertTrue(spotifySourcePending())

            player.queue.setQueue(listOf(ytSong("other", "Other")))
            assertFalse(spotifySourcePending())
        }

    @Test
    fun legacyShuffleNeverMixesContinuationIntoTheSource() = runTest { shuffleIsToggledWhileTheSourceLoads(legacy = true) }

    @Test
    fun shuffleNeverMixesContinuationIntoTheSource() = runTest { shuffleIsToggledWhileTheSourceLoads(legacy = false) }

    /** The reported case: 28 tracks, the queue's shuffle button pressed on and off, continuation offered all along. */
    private fun TestScope.shuffleIsToggledWhileTheSourceLoads(legacy: Boolean) {
        player.legacyShuffle = legacy
        playback().play(tracks(0..27), startIndex = 6)
        runCurrent()
        var step = 0

        fun advance() {
            if (step % 3 == 0) {
                player.toggleShuffle()
                runCurrent()
            }
            player.continuation(listOf("mix$step"))
            player.queue.getNext()
            runCurrent()
            assertTrue(player.recommendationsAreLast, "step $step: ${player.ids}")
            step++
        }
        while (spotifySourcePending() && step < 200) advance()
        assertFalse(spotifySourcePending())
        repeat(8) { advance() }

        assertEquals(yt(0..27).sorted(), player.ids.filter { it.startsWith("yt") }.sorted())
        assertTrue(player.ids.count { it.startsWith("mix") } >= 8)
    }

    @Test
    fun legacyShuffleKeepsRecommendationsAfterTheQueuedTracks() {
        val recommendations = (0..9).map { ytSong("mix$it", "Mix $it").copy(queueSourceTitle = QUEUE_SOURCE_AUTO_MIX) }
        val songs = (0..9).map { ytSong("yt$it", "Song $it") } + recommendations
        val queue = MusicQueue().apply { setQueue(songs, 3) }
        repeat(3) {
            queue.toggleShuffle(legacyShuffle = true)
            assertEquals("yt3", queue.currentItem()?.id)
            assertEquals(
                yt(0..9).toSet(),
                queue.items.value
                    .take(10)
                    .map { it.id }
                    .toSet(),
            )
            assertEquals(recommendations, queue.items.value.drop(10))

            queue.toggleShuffle(legacyShuffle = true)
            assertEquals(songs, queue.items.value)
        }
    }

    @Test
    fun queueIsRefilledAsTheListenerAdvances() =
        runTest {
            playback().play(tracks(0..29))
            runCurrent()
            assertEquals(yt(0..10), player.ids)

            repeat(6) { player.queue.getNext() }
            runCurrent()
            assertEquals(yt(0..10), player.ids)

            player.queue.getNext()
            runCurrent()
            assertEquals(yt(0..20), player.ids)
        }

    @Test
    fun moreIsAskedOnlyWhenTheQueueRunsLow() =
        runTest {
            val pages = ArrayDeque(listOf(tracks(13..18), null))
            var asked = 0
            playback().play(tracks(0..12)) {
                asked++
                pages.removeFirst()
            }
            runCurrent()
            assertEquals(yt(0..10), player.ids)
            assertEquals(0, asked)

            repeat(7) { player.queue.getNext() }
            runCurrent()
            assertEquals(yt(0..12), player.ids)
            assertEquals(0, asked)

            repeat(2) { player.queue.getNext() }
            runCurrent()
            assertEquals(yt(0..18), player.ids)
            assertEquals(1, asked)

            repeat(6) { player.queue.getNext() }
            runCurrent()
            assertEquals(yt(0..18), player.ids)
            assertEquals(2, asked)
        }

    @Test
    fun newerRequestCancelsTheOlderOne() =
        runTest {
            youtube.gate = CompletableDeferred()
            val playback = playback()
            playback.play(tracks(0..9), title = "Old")
            runCurrent()
            playback.play(tracks(100..109), title = "New")
            runCurrent()

            youtube.gate?.complete(Unit)
            runCurrent()
            assertEquals(yt(100..109), player.ids)
            assertEquals(listOf<String?>("New"), player.titles)
            assertEquals("New", playback.summary.value.title)
        }

    @Test
    fun requestStillResolvingDoesNotReplaceAQueueStartedMeanwhile() =
        runTest {
            player.queue.setQueue(listOf(ytSong("before", "Before")))
            youtube.gate = CompletableDeferred()
            playback().play(tracks(0..9))
            runCurrent()

            player.queue.setQueue(listOf(ytSong("other", "Other")))
            youtube.gate?.complete(Unit)
            runCurrent()
            assertEquals(listOf("other"), player.ids)
        }

    @Test
    fun feedingStopsWhenAnotherQueueReplacesThisOne() =
        runTest {
            var asked = 0
            playback().play(tracks(0..11)) {
                asked++
                tracks(12..20)
            }
            runCurrent()
            assertEquals(yt(0..10), player.ids)

            player.queue.setQueue(listOf(ytSong("yt0", "Song 0"), ytSong("other", "Other")))
            runCurrent()
            assertEquals(listOf("yt0", "other"), player.ids)
            assertEquals(0, asked)
        }

    @Test
    fun shufflePlaysEveryTrackOnceBeforeAnyContinuationAndNeverMovesQueuedTracks() =
        runTest {
            playback().play(tracks(0..29), startIndex = 3, shuffle = true)
            runCurrent()
            assertEquals("yt3", player.ids.first())
            assertTrue(player.queue.isShuffled)

            val played = mutableListOf("yt3")
            var queued = player.ids
            repeat(29) { step ->
                player.continuation(listOf("mix$step"))
                player.queue.getNext()
                runCurrent()
                played += player.queue.currentItem()!!.id
                assertEquals(queued, player.ids.take(queued.size))
                queued = player.ids
            }
            assertEquals(yt(0..29).toSet(), played.toSet())
            assertEquals(30, played.size)
            assertNotEquals(listOf("yt3") + (yt(0..29) - "yt3"), played)
            assertTrue(player.ids.any { it.startsWith("mix") })
        }

    @Test
    fun shuffleSwitchedOnInTheQueueDrawsTheRestOfTheSourceAtRandom() =
        runTest {
            playback().play(tracks(0..39), startIndex = 5)
            runCurrent()
            assertEquals(yt(0..15), player.ids)

            player.toggleShuffle()
            val played = mutableListOf(player.queue.currentItem()!!.id)
            repeat(39) {
                player.queue.getNext()
                runCurrent()
                played += player.queue.currentItem()!!.id
            }
            assertEquals(yt(0..39).toSet(), played.toSet())
            assertEquals(40, played.size)
            assertNotEquals(yt(16..39), player.ids.drop(16))
        }

    @Test
    fun pagesJoinTheShuffledSource() =
        runTest {
            val pages = ArrayDeque(listOf(tracks(1..20), null))
            playback().play(tracks(0..0), shuffle = true) { pages.removeFirst() }
            runCurrent()
            assertEquals(21, player.ids.size)
            assertNotEquals(yt(0..20), player.ids)
        }

    @Test
    fun unmatchedTracksAreSkippedAndCounted() =
        runTest {
            youtube.missing += listOf("Song 0", "Song 2")
            youtube.failing += "Song 4"
            youtube.results["Song 5"] = listOf(ytSong("live", "Song 5 (Live)"))
            val playback = playback()
            playback.play(tracks(0..6), title = "Mix")
            testScheduler.advanceTimeBy(2_000)
            runCurrent()

            assertEquals(listOf("yt1", "yt3", "live", "yt6"), player.ids)
            val summary = playback.summary.value
            assertEquals(listOf("sp0", "sp2"), summary.unavailable.ids())
            assertEquals(listOf("sp4"), summary.failed.ids())
            assertEquals(listOf("sp5"), summary.lowConfidence.ids())
        }

    @Test
    fun unreachableYoutubeStopsInsteadOfBurningThroughTheSource() =
        runTest {
            youtube.failing += (0..29).map { "Song $it" }
            val playback = playback()
            playback.play(tracks(0..29))
            testScheduler.advanceTimeBy(60_000)
            runCurrent()

            assertTrue(player.ids.isEmpty())
            val summary = playback.summary.value
            assertEquals(listOf("sp0"), summary.failed.ids())
        }

    @Test
    fun failedPageIsAskedAgainAndDoesNotEndTheSource() =
        runTest {
            var asked = 0
            val playback = playback()
            playback.play(tracks(0..1)) {
                when (++asked) {
                    1, 2 -> error("Spotify is unreachable")
                    3 -> tracks(2..3)
                    else -> null
                }
            }
            runCurrent()
            assertEquals(yt(0..1), player.ids)
            assertEquals(1, asked)
            assertTrue(spotifySourcePending())

            testScheduler.advanceTimeBy(2_000)
            runCurrent()
            assertEquals(2, asked)
            testScheduler.advanceTimeBy(4_000)
            runCurrent()
            assertEquals(yt(0..3), player.ids)
            assertEquals(4, asked)
            assertFalse(spotifySourcePending())
            assertNull(playback.summary.value.error)
        }

    @Test
    fun playNextAndAddToQueueResolveThenDelegate() =
        runTest {
            youtube.missing += "Song 21"
            val playback = playback()
            playback.play(tracks(0..1))
            runCurrent()

            playback.addToQueue(tracks(20..22))
            playback.playNext(tracks(30..31))
            runCurrent()
            assertEquals(listOf("yt0", "yt30", "yt31", "yt1", "yt20", "yt22"), player.ids)
            val summary = playback.summary.value
            assertEquals(listOf("sp21"), summary.unavailable.ids())
        }
}
