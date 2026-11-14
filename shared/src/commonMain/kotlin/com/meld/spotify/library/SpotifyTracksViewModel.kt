package com.meld.spotify.library

import com.meld.spotify.SpotifyConnection
import com.meld.spotify.SpotifyConnectionState
import com.meld.spotify.SpotifyError
import com.meld.spotify.SpotifyTrack
import com.meld.spotify.SpotifyTrackItem
import com.meld.spotify.playback.SpotifyPlayback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SpotifyTracksUiState(
    val session: SpotifyConnectionState,
    val info: SpotifyCollectionInfo? = null,
    val tracks: SpotifyPagerState<SpotifyTrackItem> = SpotifyPagerState(),
    val preparingShuffle: Boolean = false,
)

/** Episodes, local files and removed tracks have no [SpotifyTrackItem.track]; Spotify marks the rest it cannot play. */
val SpotifyTrackItem.playableTrack: SpotifyTrack? get() = track?.takeIf { it.playable != false }

/**
 * A playlist, the liked songs, an album or an artist's top tracks. Without a [playback] the list
 * can still be browsed and [canPlay] is false.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpotifyTracksViewModel(
    private val scope: CoroutineScope,
    private val store: SpotifyLibraryStore,
    connection: SpotifyConnection,
    private val playback: SpotifyPlayback?,
    private val type: SpotifyCollectionType,
    private val id: String = "",
) {
    private val preparingShuffle = MutableStateFlow(false)
    private val collection get() = store.collection(type, id)
    private val connected = connection.state.map { it is SpotifyConnectionState.Connected }.distinctUntilChanged()

    val canPlay: Boolean get() = playback != null

    val state: StateFlow<SpotifyTracksUiState> =
        combine(
            connection.state,
            store.generation.flatMapLatest { collection.info },
            store.generation.flatMapLatest { collection.tracks.state },
            preparingShuffle,
            ::SpotifyTracksUiState,
        ).stateIn(scope, SharingStarted.Eagerly, SpotifyTracksUiState(connection.state.value))

    init {
        scope.launch {
            combine(store.generation, connected) { _, connected -> connected }.collect { connected ->
                if (connected &&
                    collection.tracks.state.value.items
                        .isEmpty()
                ) {
                    collection.tracks.loadMore()
                }
            }
        }
    }

    /** Next page, and the retry of a page that failed. */
    fun loadMore() = collection.tracks.loadMore()

    fun refresh() = collection.tracks.refresh()

    /**
     * Plays the loaded tracks from the entry at [itemIndex] (the first playable one when null); the
     * pages not loaded yet follow through `more`, so nothing waits for the whole list.
     */
    fun play(
        itemIndex: Int? = null,
        title: String? = null,
    ) {
        val pager = collection.tracks
        val items = pager.state.value.items
        if (itemIndex != null && items.getOrNull(itemIndex)?.playableTrack == null) return
        val tracks = items.mapNotNull { it.playableTrack }
        if (tracks.isEmpty()) return
        val start = items.take(itemIndex ?: 0).count { it.playableTrack != null }
        playback?.play(tracks, start, title, more = more(pager, items.size))
    }

    /** Shuffling needs the whole list, so the remaining pages are loaded first; a failure shows in the list. */
    fun shuffle(title: String? = null) {
        if (playback == null || preparingShuffle.value) return
        preparingShuffle.value = true
        scope.launch {
            try {
                val tracks = collection.tracks.loadAll().mapNotNull { it.playableTrack }
                if (tracks.isNotEmpty()) playback.play(tracks, tracks.indices.random(), title, shuffle = true)
            } catch (error: SpotifyError) {
                // Already in the pager's state, where the list shows it with a retry.
            } finally {
                preparingShuffle.value = false
            }
        }
    }

    fun playNext(track: SpotifyTrack) {
        playback?.playNext(listOf(track))
    }

    fun addToQueue(track: SpotifyTrack) {
        playback?.addToQueue(listOf(track))
    }

    /**
     * The batches after the first [handedOver] entries, null after the last. A page that fails
     * throws its [SpotifyError]; it is asked for again on the next call.
     */
    private fun more(
        pager: SpotifyPager<SpotifyTrackItem>,
        handedOver: Int,
    ): suspend () -> List<SpotifyTrack>? {
        var taken = handedOver

        suspend fun batch(): List<SpotifyTrack>? {
            while (true) {
                val items = pager.state.value.items
                if (taken < items.size) {
                    val tracks = items.drop(taken).mapNotNull { it.playableTrack }
                    taken = items.size
                    // A page of episodes or removed tracks is not the end of the list.
                    if (tracks.isNotEmpty()) return tracks
                } else {
                    if (!pager.next()) return null
                }
            }
        }
        return { batch() }
    }
}
