package com.meld.spotify.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The feeder and the one queue target against upstream's real queue, once for each way the
 * platform players differ: where they put queued songs and whether they take a reorder.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpotifyQueueContractTest {
    private val android = FakePlayer()

    private fun desktop(queueLocked: Boolean) =
        FakePlayer().apply {
            appendsAtEnd = true
            this.queueLocked = queueLocked
        }

    private fun TestScope.playback(
        player: FakePlayer,
        youtube: FakeYouTube = FakeYouTube(),
    ): SpotifyQueuePlayback {
        val resolver = SpotifyTrackResolver(FakeMatchDao(), youtube::search, youtube::songsById, backgroundScope, { 0 })
        return SpotifyQueuePlayback(resolver, player.target(backgroundScope), backgroundScope, Random(7))
    }

    private fun yt(range: IntRange) = range.map { "yt$it" }

    @Test
    fun androidQueuesTheSourceInItsOrderAroundTheTappedTrack() = runTest { sourceIsQueuedInOrder(android) }

    @Test
    fun desktopQueuesTheSourceInItsOrderAroundTheTappedTrack() = runTest { sourceIsQueuedInOrder(desktop(queueLocked = false)) }

    /** The feeder's own reorder passes the desktop queue lock, which is on by default. */
    @Test
    fun lockedDesktopQueuesTheSourceInItsOrderAroundTheTappedTrack() =
        runTest {
            val player = desktop(queueLocked = true)
            sourceIsQueuedInOrder(player)
            assertFalse(player.reorderQueue(List(player.ids.size) { it }), "the lock still holds for the user's drags")
        }

    private fun TestScope.sourceIsQueuedInOrder(player: FakePlayer) {
        playback(player).play(tracks(0..27), startIndex = 20, title = "Mix")
        runCurrent()
        assertEquals(yt(0..27), player.ids)
        assertEquals("yt20", player.queue.currentItem()?.id)
        assertEquals(listOf<String?>("Mix"), player.titles)
        assertFalse(spotifySourcePending())
        assertTrue(player.continuation(listOf("mix0")))
        assertEquals(yt(0..27) + "mix0", player.ids)
        assertEquals(yt(0..19).reversed(), List(20) { player.queue.getPrevious()?.id }, "\"previous\" reaches every earlier track")
    }

    /** A Listen Together guest's player takes no reorder: the earlier tracks stay where they were appended. */
    @Test
    fun aRefusedReorderIsNotWaitedForAndLosesOrDuplicatesNoTrack() =
        runTest {
            val player = desktop(queueLocked = false).apply { refusesReorder = true }
            playback(player).play(tracks(0..27), startIndex = 20)
            // No time passes here: the feeder goes on at once instead of waiting for an order that will not come.
            runCurrent()
            assertEquals(yt(20..27) + yt(10..19) + yt(0..9), player.ids)
            assertEquals("yt20", player.queue.currentItem()?.id)
            assertNull(player.queue.peekPrevious())
            assertFalse(spotifySourcePending())
            assertTrue(player.continuation(listOf("mix0")))
            assertEquals("mix0", player.ids.last())
        }

    private fun eachPlayer(test: TestScope.(FakePlayer) -> Unit) =
        listOf(android, desktop(queueLocked = false), desktop(queueLocked = true)).forEach { runTest { test(it) } }

    @Test
    fun shuffleFromTheStartQueuesTheWholeSourceAtOnce() =
        eachPlayer { player ->
            playback(player).play(tracks(0..27), startIndex = 4, shuffle = true)
            runCurrent()
            assertTrue(player.queue.isShuffled, "the player's shuffle is on, not only the feeder's")
            assertEquals("yt4", player.ids.first())
            wholeSourceIsQueuedOnce(player)
        }

    /** The reported case: 28 tracks started from the fifth, then the queue's shuffle button. */
    @Test
    fun shuffleSwitchedOnInTheQueueQueuesTheRestOfTheSourceAtOnce() =
        eachPlayer { player ->
            playback(player).play(tracks(0..27), startIndex = 4)
            runCurrent()
            // In order the queue is fed ahead of the listener: the tapped track, ten ahead and the four before it.
            assertEquals(yt(0..14), player.ids)
            assertFalse(player.continuation(listOf("mix0")))

            player.toggleShuffle()
            runCurrent()
            assertEquals("yt4", player.queue.currentItem()?.id)
            wholeSourceIsQueuedOnce(player)
        }

    /** Every track of the 28 is in the queue once, then the continuation, and the listener hears each once. */
    private fun TestScope.wholeSourceIsQueuedOnce(player: FakePlayer) {
        assertEquals(yt(0..27), player.ids.sortedBy { it.drop(2).toInt() })
        assertFalse(spotifySourcePending())
        assertTrue(player.continuation(listOf("mix0", "mix1")))
        assertEquals(listOf("mix0", "mix1"), player.ids.drop(28))
        repeat(2) {
            player.toggleShuffle()
            runCurrent()
            assertTrue(player.recommendationsAreLast, player.ids.toString())
        }
        val played = playedToTheEnd(player).dropLast(2)
        assertEquals(yt(0..27).toSet(), played.toSet())
        assertEquals(28, played.size)
    }

    @Test
    fun aNewerPlayCancelsTheBulkResolutionAndLeavesNoStrayTrack() =
        eachPlayer { player ->
            val youtube = FakeYouTube().apply { gate = CompletableDeferred() }
            youtube.ungated += "Song 0"
            val playback = playback(player, youtube)
            playback.play(tracks(0..27), shuffle = true)
            runCurrent()
            assertEquals(listOf("yt0"), player.ids)
            val asked = youtube.calls.size

            youtube.ungated += (100..104).map { "Song $it" }
            playback.play(tracks(100..104))
            runCurrent()
            youtube.gate?.complete(Unit)
            runCurrent()
            assertEquals(yt(100..104), player.ids)
            assertFalse(spotifySourcePending())
            assertEquals(asked + 5, youtube.calls.size, "the older request looks nothing more up")
        }

    @Test
    fun aFailingPageIsAskedAgainDuringBulkResolutionAndDoesNotTruncateTheQueue() =
        eachPlayer { player ->
            var asked = 0
            playback(player).play(tracks(0..9), shuffle = true) {
                when (++asked) {
                    1 -> tracks(10..19)
                    2, 3 -> error("Spotify is unreachable")
                    4 -> tracks(20..27)
                    else -> null
                }
            }
            runCurrent()
            assertEquals(listOf("yt0"), player.ids)
            assertTrue(spotifySourcePending())
            assertFalse(player.continuation(listOf("mix0")))

            testScheduler.advanceTimeBy(6_000)
            runCurrent()
            assertEquals(5, asked)
            wholeSourceIsQueuedOnce(player)
        }

    private fun TestScope.next(player: FakePlayer): String? {
        val song = player.queue.getNext()
        runCurrent()
        return song?.id
    }

    /** The current track and every one after it, as the listener skips to the end of the queue. */
    private fun TestScope.playedToTheEnd(player: FakePlayer): List<String> =
        generateSequence(player.queue.currentItem()?.id) { next(player) }.toList()
}
