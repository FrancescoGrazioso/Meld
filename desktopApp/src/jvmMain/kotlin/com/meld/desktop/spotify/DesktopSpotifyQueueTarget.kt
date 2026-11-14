package com.meld.desktop.spotify

import com.meld.spotify.playback.SpotifyQueueTarget
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueue
import com.metrolist.shared.playback.MusicQueueState
import com.metrolist.shared.playback.isRecommendationGeneratedQueueSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The desktop player as the Spotify feeder sees it. Everything goes through the player's public
 * queue functions, so Listen Together, Device Connect and the blocked-content filter apply as they
 * do to any other queue. The defaults are the app's player; tests pass a queue of their own.
 */
class DesktopSpotifyQueueTarget(
    scope: CoroutineScope,
    private val queue: MusicQueue = PlayerState.queue,
    private val play: (List<SongItem>) -> Unit = { PlayerState.playTrack(it.first(), it) },
    private val enqueue: (List<SongItem>) -> Unit = { PlayerState.addToQueue(it) },
    private val enqueueNext: (List<SongItem>) -> Unit = { PlayerState.playNext(it) },
    private val reorder: (List<Int>) -> Unit = { PlayerState.reorderQueue(it) },
) : SpotifyQueueTarget {
    override val queueState get() = queue.state

    init {
        // The player appends at the very end and a shuffle toggle rebuilds what is upcoming, so the
        // order is put right after every change instead of at each place that could break it.
        scope.launch { queue.state.collect { state -> state.orderWithSpotifyBeforeContinuation()?.let(reorder) } }
    }

    // The desktop queue has no title. Recommendations follow only with auto-load on, as for a playlist.
    override fun start(
        songs: List<SongItem>,
        title: String?,
    ) = play(songs)

    override fun addToQueue(songs: List<SongItem>) = enqueue(songs)

    override fun playNext(songs: List<SongItem>) = enqueueNext(songs)
}

// Mirrors the feeder's private tag prefix in SpotifyQueuePlayback; only songs it feeds carry it.
private const val SPOTIFY_QUEUE_SOURCE_PREFIX = "meld:queue-source:spotify:"

/**
 * The rule of a Spotify-fed queue: upcoming Spotify tracks play before the player's own
 * continuation (auto-mix, recommendations). Returns the queue order that restores it, moving only
 * the continuation entries that got ahead to just behind the last Spotify track, or null when the
 * rule holds.
 */
internal fun MusicQueueState.orderWithSpotifyBeforeContinuation(): List<Int>? {
    val upcoming = (currentIndex + 1)..entries.lastIndex
    val lastSpotify =
        upcoming.lastOrNull { entries[it].song.queueSourceTitle?.startsWith(SPOTIFY_QUEUE_SOURCE_PREFIX) == true } ?: return null
    val early =
        (upcoming.first until lastSpotify).filter { entries[it].song.queueSourceTitle.isRecommendationGeneratedQueueSource() }
    if (early.isEmpty()) return null
    val moved = early.toSet()
    return (0..lastSpotify).filterNot { it in moved } + early + (lastSpotify + 1..entries.lastIndex)
}
