package com.meld.spotify.library

import com.meld.spotify.SpotifyConnection
import com.meld.spotify.SpotifyConnectionState
import com.meld.spotify.SpotifyError
import com.meld.spotify.SpotifyTrack
import com.meld.spotify.SpotifyTrackItem
import com.meld.spotify.download.SpotifyDownloadResult
import com.meld.spotify.download.SpotifyDownloads
import com.meld.spotify.playback.SpotifyTrackResolver
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
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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
    /** A playlist of the signed-in user's own, which they may delete. */
    val canDelete: Boolean = false,
    val deleting: Boolean = false,
    /** Set once Spotify has deleted the playlist: the screen has nothing left to show. */
    val deleted: Boolean = false,
    val deleteFailure: SpotifyError? = null,
    /** Someone else's playlist, an album or an artist: true when it is in the user's library, null while that is not known or does not apply. */
    val following: Boolean? = null,
    val followFailure: SpotifyError? = null,
    /** What is known of the tracks beyond what Spotify says: see [SpotifyTrackMarks]. */
    val marks: SpotifyTrackMarks = SpotifyTrackMarks(),
    /** Null until a download was asked for on this screen. */
    val download: SpotifyDownloadUi? = null,
)

/**
 * What Meld knows of Spotify tracks from having looked them up: [youtubeIds] is the song that plays for
 * a track, by the track's id; [notFound] are the tracks YouTube Music has no song for; [downloaded] are
 * the ids of the songs the app holds as downloads. A track not looked up yet is in neither.
 */
data class SpotifyTrackMarks(
    val youtubeIds: Map<String, String> = emptyMap(),
    val notFound: Set<String> = emptySet(),
    val downloaded: Set<String> = emptySet(),
) {
    fun isDownloaded(trackId: String): Boolean = youtubeIds[trackId]?.let { it in downloaded } == true
}

/** A download asked for on a track list: still looking the songs up while [result] is null. */
data class SpotifyDownloadUi(
    val done: Int = 0,
    val total: Int = 0,
    val result: SpotifyDownloadResult? = null,
)

private val SAVABLE = setOf(SpotifyCollectionType.PLAYLIST, SpotifyCollectionType.ALBUM, SpotifyCollectionType.ARTIST)

private data class Follow(
    val following: Boolean? = null,
    val busy: Boolean = false,
    val failure: SpotifyError? = null,
)

private data class Deletion(
    val running: Boolean = false,
    val done: Boolean = false,
    val failure: SpotifyError? = null,
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
    private val downloads: SpotifyDownloads? = null,
    resolver: SpotifyTrackResolver? = null,
) {
    private val preparingShuffle = MutableStateFlow(false)
    private val deletion = MutableStateFlow(Deletion())
    private val downloading = MutableStateFlow<SpotifyDownloadUi?>(null)
    private val follow = MutableStateFlow(Follow())
    private val marks =
        // Each side starts empty: the screen must not wait for the database or for the downloads to say something.
        combine(
            (resolver?.links() ?: flowOf(emptyMap())).onStart { emit(emptyMap()) },
            (downloads?.downloadedIds ?: flowOf(emptySet())).onStart { emit(emptySet()) },
        ) { links, downloaded ->
            val found = HashMap<String, String>()
            val notFound = HashSet<String>()
            links.forEach { (trackId, songId) -> if (songId != null) found[trackId] = songId else notFound += trackId }
            SpotifyTrackMarks(found, notFound, downloaded)
        }
    private val collection get() = store.collection(type, id)
    private val connected = connection.state.map { it is SpotifyConnectionState.Connected }.distinctUntilChanged()

    val canPlay: Boolean get() = playback != null

    /** False where the app has no downloads to hand the songs to. */
    val canDownload: Boolean get() = downloads != null

    val state: StateFlow<SpotifyTracksUiState> =
        combine(
            connection.state,
            store.generation.flatMapLatest { collection.info },
            store.generation.flatMapLatest { collection.tracks.state },
            preparingShuffle,
            editor?.failures?.map { it[id]?.error } ?: flowOf(null),
            ::uiState,
        ).combine(deletion) { ui, deletion ->
            ui.copy(deleting = deletion.running, deleted = deletion.done, deleteFailure = deletion.failure)
        }.combine(downloading) { ui, download -> ui.copy(download = download) }
            .combine(marks) { ui, marks -> ui.copy(marks = marks) }
            .combine(follow) { ui, follow ->
                // Not for the user's own playlist: that one is deleted, not un-followed.
                val applies = editor != null && type in SAVABLE && !ui.canDelete && ui.info != null
                ui.copy(following = follow.following.takeIf { applies }, followFailure = follow.failure)
            }
            .stateIn(
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
            canDelete = editable && owned,
        )
    }

    init {
        if (editor != null && type in SAVABLE) {
            scope.launch {
                connected.collect { connected ->
                    if (!connected) return@collect
                    try {
                        follow.update { it.copy(following = editor.inLibrary(type, id)) }
                    } catch (error: SpotifyError) {
                        // Not known: the screen then offers neither to follow nor to un-follow.
                    }
                }
            }
        }
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

    /** Puts the playlist, album or artist in the library, or takes it out; [SpotifyTracksUiState.following] changes once Spotify has answered. */
    fun toggleFollow() {
        val editor = editor ?: return
        val current = follow.value
        val following = current.following ?: return
        if (current.busy) return
        follow.value = current.copy(busy = true, failure = null)
        scope.launch {
            val failure = editor.setInLibrary(type, id, !following)
            follow.value = Follow(following = if (failure == null) !following else following, failure = failure)
        }
    }

    fun dismissFollowFailure() = follow.update { it.copy(failure = null) }

    /** Downloads every track of the list, loading the pages not shown yet first. */
    fun downloadAll() = download { collection.tracks.loadAll().mapNotNull { it.playableTrack } }

    fun download(track: SpotifyTrack) = download { listOf(track) }

    private fun download(tracks: suspend () -> List<SpotifyTrack>) {
        val downloads = downloads ?: return
        if (downloading.value?.result == null && downloading.value != null) return
        downloading.value = SpotifyDownloadUi()
        scope.launch {
            try {
                val result = downloads.download(tracks()) { done, total -> downloading.value = SpotifyDownloadUi(done, total) }
                downloading.value = SpotifyDownloadUi(result = result)
            } catch (error: SpotifyError) {
                // The list could not be loaded to the end; the pager shows that with its retry.
                downloading.value = null
            }
        }
    }

    fun dismissDownload() {
        if (downloading.value?.result != null) downloading.value = null
    }

    /** Asks Spotify to delete the playlist; [SpotifyTracksUiState.deleted] or [SpotifyTracksUiState.deleteFailure] says how it went. */
    fun delete() {
        val editor = editor ?: return
        if (deletion.value.running || deletion.value.done) return
        deletion.value = Deletion(running = true)
        scope.launch {
            val failure = editor.delete(id)
            deletion.value = Deletion(done = failure == null, failure = failure)
        }
    }

    fun dismissDeleteFailure() {
        deletion.value = Deletion()
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

    /**
     * Plays [shown], a searched or sorted view of the list, from [item]: what the user sees is what follows.
     * The view is of the pages loaded so far; nothing more is fetched behind it.
     */
    fun play(
        shown: List<SpotifyTrackItem>,
        item: SpotifyTrackItem,
        title: String? = null,
    ) {
        val start = item.playableTrack ?: return
        val tracks = shown.mapNotNull { it.playableTrack }
        playback?.play(tracks, tracks.indexOf(start).coerceAtLeast(0), title)
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
