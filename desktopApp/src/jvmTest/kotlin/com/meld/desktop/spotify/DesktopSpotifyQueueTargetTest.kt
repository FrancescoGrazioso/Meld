package com.meld.desktop.spotify

import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopSpotifyQueueTargetTest {
    private val queue = MusicQueue()

    // The player replaces the queue in a coroutine of its own; the test runs it when it says so.
    private var replaceQueue = {}

    private fun song(
        id: String,
        source: String? = null,
    ) = SongItem(id = id, title = id, artists = emptyList(), thumbnail = null, album = null, queueSourceTitle = source)

    private fun spotify(vararg ids: String) = ids.map { song(it, "meld:queue-source:spotify:1") }

    /** The target over [queue], with the player's queue functions reduced to what they do to the queue. */
    private fun TestScope.target() =
        DesktopSpotifyQueueTarget(
            scope = backgroundScope,
            queue = queue,
            play = { songs ->
                replaceQueue = {
                    val keepShuffle = queue.isShuffled
                    queue.setQueue(songs)
                    if (keepShuffle) queue.toggleShuffle()
                }
            },
            enqueue = { queue.addToQueue(it) },
            enqueueNext = { queue.playNext(it) },
            reorderQueue = { queue.reorder(it) },
            toggleShuffle = { queue.toggleShuffle() },
        )

    private val ids get() = queue.items.value.map(SongItem::id)

    @Test
    fun startReplacesTheQueueAndTheOthersMapToThePlayer() =
        runTest {
            queue.setQueue(listOf(song("old")))
            val target = target()
            target.start(spotify("s0", "s1"), "Mix", shuffle = false)
            replaceQueue()
            target.addToQueue(listOf(song("later")))
            target.playNext(listOf(song("next")))
            runCurrent()

            assertEquals(listOf("s0", "next", "s1", "later"), ids)
            assertEquals(0, queue.currentIndex.value)
            assertEquals(queue.state.value, target.queueState.value)
            assertFalse(target.shuffleEnabled.value)
        }

    @Test
    fun aShuffleRequestSwitchesShuffleOnOnceTheNewQueueIsThere() =
        runTest {
            queue.setQueue(listOf(song("old")))
            val target = target()
            target.start(spotify("s0"), null, shuffle = true)
            runCurrent()
            assertFalse(target.shuffleEnabled.value, "the old queue must not be shuffled")

            replaceQueue()
            runCurrent()
            assertTrue(target.shuffleEnabled.value)
            assertEquals(listOf("s0"), ids)
        }

    @Test
    fun shuffleCarriedOverFromTheLastQueueIsNotSwitchedOff() =
        runTest {
            queue.setQueue(listOf(song("old")))
            queue.toggleShuffle()
            val target = target()
            target.start(spotify("s0"), null, shuffle = true)
            replaceQueue()
            runCurrent()
            assertTrue(target.shuffleEnabled.value)
        }

    @Test
    fun reorderTakesEntryKeys() =
        runTest {
            val target = target()
            target.start(spotify("s2", "s3"), null, shuffle = false)
            replaceQueue()
            target.addToQueue(spotify("s0", "s1"))
            val keys =
                queue.state.value.entries
                    .map { it.key }
            target.reorder(keys.drop(2) + keys.take(2))

            assertEquals(listOf("s0", "s1", "s2", "s3"), ids)
            assertEquals("s2", queue.currentItem()?.id)
        }
}
