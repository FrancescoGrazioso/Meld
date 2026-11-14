package com.meld.spotify.playback

import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueueState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * A platform player as the Spotify feeder sees it, for every platform: each host passes its player's
 * public queue functions and nothing else, so Listen Together, Device Connect and the blocked-content
 * filter apply as they do to any other queue.
 *
 * [enqueue] must leave the songs before the player's own recommendations. A player that appends at
 * the very end does, because it fetches none while the source is pending ([spotifySourcePending]).
 * [reorder] answers whether the player took the new order.
 */
class PlayerQueueTarget(
    private val scope: CoroutineScope,
    override val queueState: StateFlow<MusicQueueState>,
    override val shuffleEnabled: StateFlow<Boolean>,
    private val play: (songs: List<SongItem>, title: String?) -> Unit,
    private val enqueue: (List<SongItem>) -> Unit,
    private val enqueueNext: (List<SongItem>) -> Unit,
    private val reorder: (entryKeys: List<Long>) -> Boolean,
    private val toggleShuffle: () -> Unit,
) : SpotifyQueueTarget {
    private var shuffleRequest: Job? = null

    // Without a shuffle request the player keeps its own rule: shuffle carried over from the last queue, or off.
    override fun start(
        songs: List<SongItem>,
        title: String?,
        shuffle: Boolean,
    ) {
        shuffleRequest?.cancel()
        play(songs, title)
        if (!shuffle) return
        // A player may replace the queue a moment later and reset shuffle with it, so shuffle waits for the new queue.
        val source = songs.first().queueSourceTitle
        shuffleRequest =
            scope.launch {
                queueState.first { state -> state.entries.any { it.song.queueSourceTitle == source } }
                if (!shuffleEnabled.value) toggleShuffle()
            }
    }

    override fun addToQueue(songs: List<SongItem>) = enqueue(songs)

    override fun playNext(songs: List<SongItem>) = enqueueNext(songs)

    override fun reorder(entryKeys: List<Long>): Boolean = reorder.invoke(entryKeys)
}
