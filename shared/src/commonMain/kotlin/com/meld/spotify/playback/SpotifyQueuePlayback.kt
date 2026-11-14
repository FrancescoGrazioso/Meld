package com.meld.spotify.playback

import com.meld.spotify.SpotifyTrack
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueueState
import com.metrolist.shared.youtube.logging.YtLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/** The platform player as the feeder needs it. Its functions are called on the feeder's scope. */
interface SpotifyQueueTarget {
    val queueState: StateFlow<MusicQueueState>

    /** The player's shuffle, which the listener also switches from the queue screen. */
    val shuffleEnabled: StateFlow<Boolean>

    /** Replaces the queue with [songs] and plays the first; [shuffle] switches the player's shuffle on. */
    fun start(
        songs: List<SongItem>,
        title: String?,
        shuffle: Boolean,
    )

    /** Appends [songs] after the queued tracks and before the player's own recommendations. */
    fun addToQueue(songs: List<SongItem>)

    fun playNext(songs: List<SongItem>)

    /** Reorders the queue to [entryKeys], which must be exactly the keys of its entries. */
    fun reorder(entryKeys: List<Long>)
}

/**
 * True while the playing queue is a Spotify source that still has tracks to queue. The player asks
 * before it adds recommendations of its own: they belong after the last track of the source.
 */
var spotifySourcePending: () -> Boolean = { false }
    private set

/**
 * What the last [SpotifyPlayback.play] and the enqueue calls after it could not play as asked.
 * [unavailable] has no acceptable YouTube match; [failed] could not be looked up (network, rate limit)
 * and will be tried again next time; [lowConfidence] play, but may be another version of the song.
 * [error] is set when the match database failed and feeding stopped.
 */
data class SpotifyPlaybackSummary(
    val title: String? = null,
    val unavailable: List<SpotifyTrack> = emptyList(),
    val failed: List<SpotifyTrack> = emptyList(),
    val lowConfidence: List<SpotifyTrack> = emptyList(),
    val error: Throwable? = null,
)

/**
 * Feeds the player queue with YouTube songs matched to Spotify tracks: the tapped track first, then
 * a few ahead of the listener and every track before the tapped one. While the player shuffles,
 * the next tracks are drawn at random from all that is not queued yet, so each plays once.
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

    // The tag of the source that still has tracks to queue.
    @Volatile
    private var feeding: String? = null

    init {
        spotifySourcePending = { feeding?.let { target.queueState.value.has(it) } == true }
    }

    override fun play(
        tracks: List<SpotifyTrack>,
        startIndex: Int,
        title: String?,
        shuffle: Boolean,
        more: (suspend () -> List<SpotifyTrack>?)?,
    ) {
        request?.cancel()
        _summary.value = SpotifyPlaybackSummary(title)
        if (tracks.isEmpty()) return
        val start = startIndex.coerceIn(tracks.indices)
        val queueAtRequest = target.queueState.value
        request = scope.launch { guarded { feed(tracks, start, title, shuffle, more, queueAtRequest) } }
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
        tracks: List<SpotifyTrack>,
        start: Int,
        title: String?,
        shuffle: Boolean,
        more: (suspend () -> List<SpotifyTrack>?)?,
        queueAtRequest: MusicQueueState,
    ) {
        // Marks this request's queue entries, so a queue started elsewhere is never mistaken for it.
        val tag = "$QUEUE_SOURCE_PREFIX${random.nextLong()}"
        val before = ArrayDeque(tracks.subList(0, start))
        val after = ArrayDeque(tracks.subList(start, tracks.size))
        var nextPage = more
        var started = false
        try {
            while (before.isNotEmpty() || after.isNotEmpty() || nextPage != null) {
                var low = true
                var shuffled = false
                if (started) {
                    // The tracks ahead wait for the listener; the ones before the tapped track do not.
                    val (state, shuffleOn) =
                        combine(target.queueState, target.shuffleEnabled, ::Pair).first { (queue, on) ->
                            !queue.has(tag) || queue.upcoming(tag) < LOW_WATER || (!on && before.isNotEmpty())
                        }
                    if (!state.has(tag)) return
                    low = state.upcoming(tag) < LOW_WATER
                    shuffled = shuffleOn
                }
                // A shuffled source is drawn from as a whole, so its pages are read ahead of the listener.
                val page = nextPage
                if (low && page != null && (after.isEmpty() || shuffled)) {
                    val loaded =
                        loadPage(page) {
                            val queue = target.queueState.value
                            if (started) queue.has(tag) else !queue.replacedSince(queueAtRequest)
                        }
                    if (loaded == null) nextPage = null else after += loaded
                }
                if (!started && after.isEmpty()) return
                val earlier = started && !shuffled && !(low && after.isNotEmpty())
                val batch =
                    when {
                        // One track at a time until something plays, so the tapped track never waits for its neighbours.
                        !started -> {
                            listOf(after.removeFirst())
                        }

                        shuffled -> {
                            List(minOf(WINDOW, before.size + after.size)) {
                                val index = random.nextInt(before.size + after.size)
                                if (index < before.size) before.removeAt(index) else after.removeAt(index - before.size)
                            }
                        }

                        // The nearest first, so "previous" works as soon as possible.
                        earlier -> {
                            List(minOf(WINDOW, before.size)) { before.removeLast() }.asReversed()
                        }

                        else -> {
                            List(minOf(WINDOW, after.size)) { after.removeFirst() }
                        }
                    }
                val songs = (resolveCounted(batch) ?: return).map { it.copy(queueSourceTitle = tag) }
                if (songs.isEmpty()) continue
                currentCoroutineContext().ensureActive()
                val queue = target.queueState.value
                if (!started) {
                    if (queue.replacedSince(queueAtRequest)) return
                    feeding = tag
                    target.start(songs, title, shuffle)
                    withTimeoutOrNull(START_TIMEOUT) {
                        target.queueState.first { it.has(tag) }
                        if (shuffle) target.shuffleEnabled.first { it }
                    } ?: return
                    started = true
                } else {
                    if (!queue.has(tag)) return
                    val known = queue.tagged(tag)
                    target.addToQueue(songs)
                    // Without this the next round would read the queue from before the append and feed again.
                    val grown = withTimeoutOrNull(APPEND_TIMEOUT) { target.queueState.first { it.tagged(tag) != known } } ?: return
                    if (earlier && !target.shuffleEnabled.value) {
                        val order = grown.withInFront(tag, grown.tagged(tag) - known.toSet())
                        target.reorder(order)
                        withTimeoutOrNull(APPEND_TIMEOUT) { target.queueState.first { state -> state.entries.map { it.key } == order } }
                    }
                }
            }
        } finally {
            if (feeding == tag) feeding = null
        }
    }

    /** The next page, asked again after a growing pause while it fails; null when there is none or the queue is no longer [current]. */
    private suspend fun loadPage(
        more: suspend () -> List<SpotifyTrack>?,
        current: () -> Boolean,
    ): List<SpotifyTrack>? {
        var pause = RETRY_PAUSE
        while (true) {
            try {
                return more()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                YtLogger.e(TAG, "Spotify page failed, asking again in $pause", e)
            }
            delay(pause)
            if (!current()) return null
            pause = minOf(pause * 2, MAX_RETRY_PAUSE)
        }
    }

    /** The matched songs in order, or null when YouTube answered for none of [batch] and it is pointless to go on. */
    private suspend fun resolveCounted(batch: List<SpotifyTrack>): List<SongItem>? {
        if (batch.isEmpty()) return emptyList()
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
        val RETRY_PAUSE = 2.seconds
        val MAX_RETRY_PAUSE = 60.seconds
    }
}

private fun MusicQueueState.tagged(tag: String): List<Long> = entries.filter { it.song.queueSourceTitle == tag }.map { it.key }

private fun MusicQueueState.has(tag: String): Boolean = entries.any { it.song.queueSourceTitle == tag }

private fun MusicQueueState.upcoming(tag: String): Int = entries.drop(currentIndex + 1).count { it.song.queueSourceTitle == tag }

/** The entry keys with [keys] moved in front of the first other entry tagged [tag]. */
private fun MusicQueueState.withInFront(
    tag: String,
    keys: List<Long>,
): List<Long> {
    val rest = entries.map { it.key } - keys.toSet()
    val first = entries.firstOrNull { it.song.queueSourceTitle == tag && it.key !in keys }?.key
    return rest.toMutableList().apply { addAll(indexOf(first).coerceAtLeast(0), keys) }
}

/** True when another queue was started in the meantime. Entry keys survive every edit except a replacement. */
private fun MusicQueueState.replacedSince(before: MusicQueueState): Boolean {
    val keys = before.entries.mapTo(hashSetOf()) { it.key }
    return keys.isNotEmpty() && entries.isNotEmpty() && entries.none { it.key in keys }
}
