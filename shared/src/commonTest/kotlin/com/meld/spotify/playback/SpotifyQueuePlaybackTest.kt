package com.meld.spotify.playback

import com.meld.spotify.SpotifyTrack
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SpotifyQueuePlaybackTest {
    private val youtube = FakeYouTube()
    private val target = FakeTarget()

    private fun TestScope.playback(): SpotifyQueuePlayback {
        val resolver = SpotifyTrackResolver(FakeMatchDao(), youtube::search, youtube::songsById, backgroundScope, { 0 })
        return SpotifyQueuePlayback(resolver, target, backgroundScope, Random(7))
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
            assertEquals(listOf("yt5"), target.ids)
            assertEquals(listOf<String?>("Mix"), target.titles)

            youtube.gate?.complete(Unit)
            runCurrent()
            // One window ahead of the listener and no further.
            assertEquals(yt(5..15), target.ids)
            assertEquals(11, youtube.calls.size)
        }

    @Test
    fun queueIsRefilledAsTheListenerAdvances() =
        runTest {
            playback().play(tracks(0..29))
            runCurrent()
            assertEquals(yt(0..10), target.ids)

            repeat(6) { target.queue.getNext() }
            runCurrent()
            assertEquals(yt(0..10), target.ids)

            target.queue.getNext()
            runCurrent()
            assertEquals(yt(0..20), target.ids)
        }

    @Test
    fun moreIsAskedOnlyWhenTheQueueRunsLow() =
        runTest {
            val pages = ArrayDeque(listOf(tracks(13..18), emptyList()))
            var asked = 0
            playback().play(tracks(0..12)) {
                asked++
                pages.removeFirst()
            }
            runCurrent()
            assertEquals(yt(0..10), target.ids)
            assertEquals(0, asked)

            repeat(7) { target.queue.getNext() }
            runCurrent()
            assertEquals(yt(0..12), target.ids)
            assertEquals(0, asked)

            repeat(2) { target.queue.getNext() }
            runCurrent()
            assertEquals(yt(0..18), target.ids)
            assertEquals(1, asked)

            repeat(6) { target.queue.getNext() }
            runCurrent()
            assertEquals(yt(0..18), target.ids)
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
            assertEquals(yt(100..109), target.ids)
            assertEquals(listOf<String?>("New"), target.titles)
            assertEquals("New", playback.summary.value.title)
        }

    @Test
    fun requestStillResolvingDoesNotReplaceAQueueStartedMeanwhile() =
        runTest {
            target.queue.setQueue(listOf(ytSong("before", "Before")))
            youtube.gate = CompletableDeferred()
            playback().play(tracks(0..9))
            runCurrent()

            target.queue.setQueue(listOf(ytSong("other", "Other")))
            youtube.gate?.complete(Unit)
            runCurrent()
            assertEquals(listOf("other"), target.ids)
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
            assertEquals(yt(0..10), target.ids)

            target.queue.setQueue(listOf(ytSong("yt0", "Song 0"), ytSong("other", "Other")))
            runCurrent()
            assertEquals(listOf("yt0", "other"), target.ids)
            assertEquals(0, asked)
        }

    @Test
    fun shuffleOrdersTheSourceOnceAndNeverMovesQueuedTracks() =
        runTest {
            playback().play(tracks(0..29), startIndex = 3, shuffle = true)
            runCurrent()
            assertEquals("yt3", target.ids.first())

            var queued = target.ids
            repeat(29) {
                target.queue.getNext()
                runCurrent()
                assertEquals(queued, target.ids.take(queued.size))
                queued = target.ids
            }
            assertEquals(yt(0..29).toSet(), queued.toSet())
            assertEquals(30, queued.size)
            assertNotEquals(listOf("yt3") + (yt(0..29) - "yt3"), queued)
        }

    @Test
    fun laterPagesAreShuffledToo() =
        runTest {
            val pages = ArrayDeque(listOf(tracks(1..20), emptyList()))
            playback().play(tracks(0..0), shuffle = true) { pages.removeFirst() }
            runCurrent()
            assertEquals(11, target.ids.size)
            assertNotEquals(yt(0..10), target.ids)
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

            assertEquals(listOf("yt1", "yt3", "live", "yt6"), target.ids)
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

            assertTrue(target.ids.isEmpty())
            val summary = playback.summary.value
            assertEquals(listOf("sp0"), summary.failed.ids())
        }

    @Test
    fun failingSourceIsReported() =
        runTest {
            val playback = playback()
            playback.play(tracks(0..1)) { error("Spotify is unreachable") }
            runCurrent()
            assertEquals(yt(0..1), target.ids)
            assertNotNull(playback.summary.value.error)
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
            assertEquals(listOf("yt0", "yt30", "yt31", "yt1", "yt20", "yt22"), target.ids)
            val summary = playback.summary.value
            assertEquals(listOf("sp21"), summary.unavailable.ids())
        }
}
