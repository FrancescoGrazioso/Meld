package com.meld.desktop.spotify

import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueue
import com.metrolist.shared.playback.QUEUE_SOURCE_AUTO_MIX
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopSpotifyQueueTargetTest {
    private val queue = MusicQueue()

    private fun song(
        id: String,
        source: String? = null,
    ) = SongItem(id = id, title = id, artists = emptyList(), thumbnail = null, album = null, queueSourceTitle = source)

    private fun spotify(vararg ids: String) = ids.map { song(it, "meld:queue-source:spotify:1") }

    private fun mix(vararg ids: String) = ids.map { song(it, QUEUE_SOURCE_AUTO_MIX) }

    /** The target over [queue], with the player's queue functions reduced to what they do to the queue. */
    private fun TestScope.target() =
        DesktopSpotifyQueueTarget(
            scope = backgroundScope,
            queue = queue,
            play = { queue.setQueue(it) },
            enqueue = { queue.addToQueue(it) },
            enqueueNext = { queue.playNext(it) },
            reorder = { queue.reorder(it) },
        )

    private val ids get() = queue.items.value.map(SongItem::id)

    private fun assertSpotifyBeforeMix() {
        val upcoming = queue.items.value.drop(queue.currentIndex.value + 1)
        val firstMix = upcoming.indexOfFirst { it.queueSourceTitle == QUEUE_SOURCE_AUTO_MIX }
        if (firstMix >= 0) assertTrue(upcoming.drop(firstMix).all { it.queueSourceTitle == QUEUE_SOURCE_AUTO_MIX }, "$ids")
    }

    @Test
    fun startReplacesTheQueueAndTheOthersMapToThePlayer() =
        runTest {
            queue.setQueue(listOf(song("old")))
            val target = target()
            target.start(spotify("s0", "s1"), "Mix")
            target.addToQueue(listOf(song("later")))
            target.playNext(listOf(song("next")))
            runCurrent()

            assertEquals(listOf("s0", "next", "s1", "later"), ids)
            assertEquals(0, queue.currentIndex.value)
            assertEquals(queue.state.value, target.queueState.value)
        }

    @Test
    fun appendedSpotifyTracksGoBeforeTheContinuation() =
        runTest {
            val target = target()
            target.start(spotify("s0", "s1"), null)
            queue.addToQueue(mix("a0", "a1"))
            target.addToQueue(spotify("s2", "s3"))
            runCurrent()
            assertEquals(listOf("s0", "s1", "s2", "s3", "a0", "a1"), ids)

            // The continuation the player adds once the listener reaches the last Spotify track loaded so far.
            queue.skipTo(3)
            queue.addToQueue(mix("a2"))
            target.addToQueue(spotify("s4"))
            runCurrent()
            assertEquals(listOf("s0", "s1", "s2", "s3", "s4", "a0", "a1", "a2"), ids)
            assertEquals(3, queue.currentIndex.value)
        }

    @Test
    fun theOrderHoldsThroughShuffleToggles() =
        listOf(false, true).forEach { legacy ->
            runTest {
                val target = target()
                target.start(spotify("s0", "s1", "s2", "s3"), null)
                queue.addToQueue(mix("a0", "a1"))
                runCurrent()

                queue.toggleShuffle(legacyShuffle = legacy)
                runCurrent()
                assertTrue(queue.isShuffled)
                assertSpotifyBeforeMix()

                target.addToQueue(spotify("s4", "s5"))
                runCurrent()
                assertSpotifyBeforeMix()

                // Unshuffling restores the original order, where tracks added while shuffled sit after the mix.
                queue.toggleShuffle(legacyShuffle = legacy)
                runCurrent()
                assertFalse(queue.isShuffled)
                assertSpotifyBeforeMix()
                assertEquals(setOf("s0", "s1", "s2", "s3", "s4", "s5", "a0", "a1"), ids.toSet())
                assertEquals("s0", queue.currentItem()?.id)

                queue.toggleShuffle(legacyShuffle = legacy)
                queue.toggleShuffle(legacyShuffle = legacy)
                runCurrent()
                assertSpotifyBeforeMix()
            }
        }

    @Test
    fun onlyTheContinuationAheadOfSpotifyTracksMoves() {
        queue.setQueue(mix("played") + spotify("s0") + mix("a0") + song("manual") + spotify("s1") + mix("a1"), startIndex = 1)
        val order = queue.state.value.orderWithSpotifyBeforeContinuation()
        assertEquals(listOf("played", "s0", "manual", "s1", "a0", "a1"), order?.map { ids[it] })
    }

    @Test
    fun aQueueWithoutUpcomingSpotifyTracksIsLeftAlone() {
        queue.setQueue(spotify("s0") + mix("a0") + song("manual"), startIndex = 0)
        assertNull(queue.state.value.orderWithSpotifyBeforeContinuation())
        queue.setQueue(spotify("s0", "s1") + mix("a0"), startIndex = 0)
        assertNull(queue.state.value.orderWithSpotifyBeforeContinuation())
    }
}
