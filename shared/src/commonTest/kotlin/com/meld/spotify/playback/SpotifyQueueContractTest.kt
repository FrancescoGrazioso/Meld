package com.meld.spotify.playback

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

    private fun TestScope.playback(player: FakePlayer): SpotifyQueuePlayback {
        val youtube = FakeYouTube()
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
            assertFalse(player.reorderQueue(player.ids.indices.reversed().toList()), "the lock still holds for the user's drags")
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

    @Test
    fun androidShuffleFromTheStartPlaysEveryTrackOnce() = runTest { shuffleFromTheStartPlaysEveryTrackOnce(android) }

    @Test
    fun desktopShuffleFromTheStartPlaysEveryTrackOnce() = runTest { shuffleFromTheStartPlaysEveryTrackOnce(desktop(queueLocked = false)) }

    @Test
    fun lockedDesktopShuffleFromTheStartPlaysEveryTrackOnce() = runTest { shuffleFromTheStartPlaysEveryTrackOnce(desktop(queueLocked = true)) }

    private fun TestScope.shuffleFromTheStartPlaysEveryTrackOnce(player: FakePlayer) {
        playback(player).play(tracks(0..27), startIndex = 4, shuffle = true)
        runCurrent()
        assertTrue(player.queue.isShuffled, "the player's shuffle is on, not only the feeder's")
        val played = playedToTheEnd(player)
        assertEquals("yt4", played.first())
        assertEquals(yt(0..27).toSet(), played.toSet())
        assertEquals(28, played.size)
    }

    @Test
    fun androidShuffleSwitchedOnInTheQueue() = runTest { shuffleIsSwitchedOnInTheQueue(android) }

    @Test
    fun desktopShuffleSwitchedOnInTheQueue() = runTest { shuffleIsSwitchedOnInTheQueue(desktop(queueLocked = false)) }

    @Test
    fun lockedDesktopShuffleSwitchedOnInTheQueue() = runTest { shuffleIsSwitchedOnInTheQueue(desktop(queueLocked = true)) }

    /** The reported case: 28 tracks started from the fifth, then the queue's shuffle button. */
    private fun TestScope.shuffleIsSwitchedOnInTheQueue(player: FakePlayer) {
        playback(player).play(tracks(0..27), startIndex = 4)
        runCurrent()
        player.toggleShuffle()
        runCurrent()
        // known defect, see 10-DA-FARE.md §16/§17: the queue shows 15 of the 28 tracks (the tapped one, a window of
        // ten ahead and the four before it) until the listener is three tracks from its end; then ten more.
        assertEquals(15, player.ids.size)
        assertTrue(spotifySourcePending())
        val played = mutableListOf("yt4")
        repeat(10) { played += next(player)!! }
        assertEquals(15, player.ids.size)
        played += next(player)!!
        assertEquals(25, player.ids.size)

        // What the listener hears is right all the same: every track of the source, once.
        played += playedToTheEnd(player).drop(1)
        assertEquals(yt(0..27).toSet(), played.toSet())
        assertEquals(28, played.size)
    }

    private fun TestScope.next(player: FakePlayer): String? = player.queue.getNext()?.id.also { runCurrent() }

    /** The current track and every one after it, as the listener skips to the end of the queue. */
    private fun TestScope.playedToTheEnd(player: FakePlayer): List<String> =
        generateSequence(player.queue.currentItem()?.id) { next(player) }.toList()
}
