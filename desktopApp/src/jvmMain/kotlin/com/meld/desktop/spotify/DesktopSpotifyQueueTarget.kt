package com.meld.desktop.spotify

import com.meld.spotify.playback.SpotifyQueueTarget
import com.meld.spotify.playback.indicesOf
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The desktop player as the Spotify feeder sees it. Everything goes through the player's public
 * queue functions, so Listen Together, Device Connect and the blocked-content filter apply as they
 * do to any other queue. The defaults are the app's player; tests pass a queue of their own.
 *
 * The player appends at the very end, which is "before its own recommendations" because it fetches
 * none while the source is pending (spotifySourcePending in PlayerState.canLoadRecommendations).
 */
class DesktopSpotifyQueueTarget(
    private val scope: CoroutineScope,
    private val queue: MusicQueue = PlayerState.queue,
    private val play: (List<SongItem>) -> Unit = { PlayerState.playTrack(it.first(), it) },
    private val enqueue: (List<SongItem>) -> Unit = { PlayerState.addToQueue(it) },
    private val enqueueNext: (List<SongItem>) -> Unit = { PlayerState.playNext(it) },
    private val reorderQueue: (List<Int>) -> Unit = { PlayerState.reorderQueue(it) },
    private val toggleShuffle: () -> Unit = { PlayerState.toggleShuffle() },
) : SpotifyQueueTarget {
    private var shuffleRequest: Job? = null

    override val queueState get() = queue.state

    override val shuffleEnabled get() = queue.shuffleEnabled

    // The desktop queue has no title. Recommendations follow only with auto-load on, as for a playlist.
    // Without a shuffle request the player keeps its own rule: shuffle carried over from the last queue, or off.
    override fun start(
        songs: List<SongItem>,
        title: String?,
        shuffle: Boolean,
    ) {
        shuffleRequest?.cancel()
        play(songs)
        if (!shuffle) return
        // The player replaces the queue a moment later and resets shuffle with it, so shuffle waits for the new queue.
        val source = songs.first().queueSourceTitle
        shuffleRequest =
            scope.launch {
                queue.state.first { state -> state.entries.any { it.song.queueSourceTitle == source } }
                if (!queue.isShuffled) toggleShuffle()
            }
    }

    override fun addToQueue(songs: List<SongItem>) = enqueue(songs)

    override fun playNext(songs: List<SongItem>) = enqueueNext(songs)

    override fun reorder(entryKeys: List<Long>) = reorderQueue(queue.state.value.indicesOf(entryKeys))
}
