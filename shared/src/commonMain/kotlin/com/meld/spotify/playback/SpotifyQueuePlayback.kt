package com.meld.spotify.playback

import com.meld.spotify.SpotifyTrack
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueueState
import com.metrolist.shared.youtube.logging.YtLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/** The platform player as the feeder needs it. Its functions are called on the feeder's scope. */
interface SpotifyQueueTarget {
    val queueState: StateFlow<MusicQueueState>

    /** Replaces the queue with [songs] and plays the first. */
    fun start(
        songs: List<SongItem>,
        title: String?,
    )

    fun addToQueue(songs: List<SongItem>)

    fun playNext(songs: List<SongItem>)
}

/**
 * What the last [SpotifyPlayback.play] and the enqueue calls after it could not play as asked.
 * [unavailable] has no acceptable YouTube match; [failed] could not be looked up (network, rate limit)
 * and will be tried again next time; [lowConfidence] play, but may be another version of the song.
 * [error] is set when the source or the match database failed and feeding stopped.
 */
data class SpotifyPlaybackSummary(
    val title: String? = null,
    val unavailable: List<SpotifyTrack> = emptyList(),
    val failed: List<SpotifyTrack> = emptyList(),
    val lowConfidence: List<SpotifyTrack> = emptyList(),
    val error: Throwable? = null,
)

/**
 * Feeds the player queue with YouTube songs matched to Spotify tracks, a few ahead of the listener.
 * [scope] must be single-threaded and the thread the public functions are called from (the main
 * thread): that is what makes "is this request still current, then touch the queue" atomic.
 */
class SpotifyQueuePlayback(
    private val resolver: SpotifyTrackResolver,
    private val target: SpotifyQueueTarget,
    private val scope: CoroutineScope,
    private val random: Random = Random,
) : SpotifyPlayback {
    private val _summary = MutableStateFlow(SpotifyPlaybackSummary())
    val summary: StateFlow<SpotifyPlaybackSummary> = _summary.asStateFlow()

    private var request: Job? = null

    override fun play(
        tracks: List<SpotifyTrack>,
        startIndex: Int,
        title: String?,
        shuffle: Boolean,
        more: (suspend () -> List<SpotifyTrack>)?,
    ) {
        request?.cancel()
        _summary.value = SpotifyPlaybackSummary(title)
        if (tracks.isEmpty()) return
        val start = startIndex.coerceIn(tracks.indices)
        val source =
            if (shuffle) {
                listOf(tracks[start]) + tracks.filterIndexed { index, _ -> index != start }.shuffled(random)
            } else {
                tracks.drop(start)
            }
        val queueAtRequest = target.queueState.value
        request = scope.launch { guarded { feed(source, title, shuffle, more, queueAtRequest) } }
    }

    override fun playNext(tracks: List<SpotifyTrack>) {
        scope.launch {
            guarded {
                val songs = tracks.chunked(WINDOW).flatMap { resolveCounted(it) ?: return@guarded }
                if (songs.isNotEmpty()) target.playNext(songs)
            }
        }
    }

    override fun addToQueue(tracks: List<SpotifyTrack>) {
        scope.launch {
            guarded {
                tracks.chunked(WINDOW).forEach { chunk ->
                    val songs = resolveCounted(chunk) ?: return@guarded
                    if (songs.isNotEmpty()) target.addToQueue(songs)
                }
            }
        }
    }

    private suspend fun feed(
        source: List<SpotifyTrack>,
        title: String?,
        shuffle: Boolean,
        more: (suspend () -> List<SpotifyTrack>)?,
        queueAtRequest: MusicQueueState,
    ) {
        // Marks this request's queue entries, so a queue started elsewhere is never mistaken for it.
        val tag = "$QUEUE_SOURCE_PREFIX${random.nextLong()}"
        val pending = ArrayDeque(source)
        var started = false
        while (true) {
            if (started) {
                val state = target.queueState.first { !it.has(tag) || it.upcoming(tag) < LOW_WATER }
                if (!state.has(tag)) return
            }
            if (pending.isEmpty()) {
                val page = more?.invoke().orEmpty()
                if (page.isEmpty()) return
                pending += if (shuffle) page.shuffled(random) else page
            }
            // One track at a time until something plays, so the tapped track never waits for its neighbours.
            val batch = List(minOf(if (started) WINDOW else 1, pending.size)) { pending.removeFirst() }
            val songs = (resolveCounted(batch) ?: return).map { it.copy(queueSourceTitle = tag) }
            if (songs.isEmpty()) continue
            currentCoroutineContext().ensureActive()
            val queue = target.queueState.value
            if (!started) {
                if (queue.replacedSince(queueAtRequest)) return
                target.start(songs, title)
                withTimeoutOrNull(START_TIMEOUT) { target.queueState.first { it.has(tag) } } ?: return
                started = true
            } else {
                if (!queue.has(tag)) return
                val tagged = queue.tagged(tag)
                target.addToQueue(songs)
                // Without this the next round would read the queue from before the append and feed again.
                withTimeoutOrNull(APPEND_TIMEOUT) { target.queueState.first { it.tagged(tag) != tagged } } ?: return
            }
        }
    }

    /** The matched songs in order, or null when YouTube answered for none of [batch] and it is pointless to go on. */
    private suspend fun resolveCounted(batch: List<SpotifyTrack>): List<SongItem>? {
        val results = batch.zip(resolver.resolveAll(batch))

        fun tracksWhere(predicate: (SpotifyResolution) -> Boolean) = results.filter { predicate(it.second) }.map { it.first }
        _summary.update { summary ->
            summary.copy(
                unavailable = summary.unavailable + tracksWhere { it is SpotifyResolution.NoMatch },
                failed = summary.failed + tracksWhere { it is SpotifyResolution.Failed },
                lowConfidence = summary.lowConfidence + tracksWhere { it is SpotifyResolution.Matched && it.lowConfidence },
            )
        }
        if (results.all { it.second is SpotifyResolution.Failed }) return null
        return results.mapNotNull { (it.second as? SpotifyResolution.Matched)?.song }
    }

    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            YtLogger.e(TAG, "Spotify playback stopped", e)
            _summary.update { it.copy(error = e) }
        }
    }

    private companion object {
        const val TAG = "MeldSpotifyPlayback"
        const val QUEUE_SOURCE_PREFIX = "meld:queue-source:spotify:"
        const val WINDOW = 10
        const val LOW_WATER = 4
        val START_TIMEOUT = 30.seconds
        val APPEND_TIMEOUT = 5.seconds
    }
}

private fun MusicQueueState.tagged(tag: String): List<Long> = entries.filter { it.song.queueSourceTitle == tag }.map { it.key }

private fun MusicQueueState.has(tag: String): Boolean = entries.any { it.song.queueSourceTitle == tag }

private fun MusicQueueState.upcoming(tag: String): Int = entries.drop(currentIndex + 1).count { it.song.queueSourceTitle == tag }

/** True when another queue was started in the meantime. Entry keys survive every edit except a replacement. */
private fun MusicQueueState.replacedSince(before: MusicQueueState): Boolean {
    val keys = before.entries.mapTo(hashSetOf()) { it.key }
    return keys.isNotEmpty() && entries.isNotEmpty() && entries.none { it.key in keys }
}
