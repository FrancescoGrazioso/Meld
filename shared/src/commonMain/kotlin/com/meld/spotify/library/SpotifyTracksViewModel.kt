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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SpotifyTracksUiState(
    val session: SpotifyConnectionState,
    val info: SpotifyCollectionInfo? = null,
    val tracks: SpotifyPagerState<SpotifyTrackItem> = SpotifyPagerState(),
    val preparingShuffle: Boolean = false,
    val editFailure: SpotifyError? = null,
    /** A playlist whose entries the signed-in user may change: their own, or one Spotify lets them contribute to. */
    val canEdit: Boolean = false,
    /** A playlist the signed-in user may rename and describe. */
    val canEditDetails: Boolean = false,
)

/** Episodes, local files and removed tracks have no [SpotifyTrackItem.track]; Spotify marks the rest it cannot play. */
val SpotifyTrackItem.playableTrack: SpotifyTrack? get() = track?.takeIf { it.playable != false }

/**
 * A playlist, the liked songs, an album or an artist's top tracks. Without a [playback] the list
 * can still be browsed and [canPlay] is false. It lives as long as [scope], which is the screen's:
 * a shuffle still loading when the screen leaves is dropped, the pages it loaded stay in the store.
 * Edits go through [editor], which outlives the screen; without one nothing can be edited.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpotifyTracksViewModel(
    private val scope: CoroutineScope,
    private val store: SpotifyLibraryStore,
    connection: SpotifyConnection,
    private val playback: SpotifyPlayback?,
    private val type: SpotifyCollectionType,
    private val id: String = "",
    private val editor: SpotifyPlaylistEditor? = null,
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
            editor?.failures?.map { it[id]?.error } ?: flowOf(null),
            ::uiState,
        ).stateIn(
            scope,
            SharingStarted.Eagerly,
            // What the store already holds: a screen that comes back draws its list, and so keeps its scroll, in the first frame.
            uiState(connection.state.value, collection.info.value, collection.tracks.state.value, false, null),
        )

    private fun uiState(
        session: SpotifyConnectionState,
        info: SpotifyCollectionInfo?,
        tracks: SpotifyPagerState<SpotifyTrackItem>,
        preparingShuffle: Boolean,
        editFailure: SpotifyError?,
    ): SpotifyTracksUiState {
        val editable = editor != null && type == SpotifyCollectionType.PLAYLIST && session is SpotifyConnectionState.Connected
        val owned = canEditPlaylist(info?.ownerId, session)
        return SpotifyTracksUiState(
            session,
            info,
            tracks,
            preparingShuffle,
            editFailure,
            // The owner always: Spotify's answer only ever adds the playlists of others.
            canEdit = editable && (owned || info?.canEditItems == true),
            canEditDetails = editable && (info?.canEditDetails ?: owned),
        )
    }

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

    /** Reordering needs every page, see [SpotifyPlaylistEditor.move]; a failure shows in the list. */
    fun loadAll() = collection.tracks.loadAllInBackground()

    /** Removes this one entry, whichever copy of its track it is. */
    fun remove(item: SpotifyTrackItem) {
        item.uid?.let { editor?.remove(id, it) }
    }

    /** False when the move was not taken up and nothing will change. */
    fun move(
        uid: String,
        beforeUid: String?,
    ): Boolean = editor?.move(id, uid, beforeUid) != null

    /** Sends only what differs from what is shown; a blank name is not a name. */
    fun editDetails(
        name: String,
        description: String,
    ) {
        val info = collection.info.value ?: return
        val newName = name.trim().takeIf { it.isNotEmpty() && it != info.title }
        val newDescription = description.trim().takeIf { it != info.description.orEmpty() }
        if (newName != null || newDescription != null) editor?.details(id, newName, newDescription)
    }

    fun retryEdit() {
        editor?.retry(id)
    }

    fun dismissEditFailure() {
        editor?.dismissFailure(id)
    }

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
        playback?.play(tracks, start, title, more = more(pager, items))
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
     * The batches after the [handedOver] entries, null after the last. A page that fails
     * throws its [SpotifyError]; it is asked for again on the next call.
     */
    private fun more(
        pager: SpotifyPager<SpotifyTrackItem>,
        handedOver: List<SpotifyTrackItem>,
    ): suspend () -> List<SpotifyTrack>? {
        var taken = handedOver.size
        var last = handedOver.lastOrNull()

        suspend fun batch(): List<SpotifyTrack>? {
            while (true) {
                val items = pager.state.value.items
                // Continues after the last entry handed over, wherever an edit of the playlist has left it.
                val from = items.indexOfFirst { it === last }.takeIf { it >= 0 }?.plus(1) ?: taken.coerceAtMost(items.size)
                if (from < items.size) {
                    val tracks = items.drop(from).mapNotNull { it.playableTrack }
                    taken = items.size
                    last = items.last()
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
