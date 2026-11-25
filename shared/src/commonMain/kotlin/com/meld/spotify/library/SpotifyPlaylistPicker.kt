package com.meld.spotify.library

import com.meld.spotify.SpotifyConnection
import com.meld.spotify.SpotifyConnectionState
import com.meld.spotify.SpotifyError
import com.meld.spotify.SpotifyLibraryItem
import com.meld.spotify.SpotifyPlaylist
import com.meld.spotify.SpotifyTrack
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * "Add to Spotify playlist" for one song. [track] is null while the song is looked up on Spotify and when
 * it is [notFound] there. [playlists] are the ones the user may edit; [containing] those known to have
 * the track already, which is only known of playlists opened in this session.
 */
data class SpotifyPickerState(
    val title: String,
    val track: SpotifyTrack? = null,
    /** The whole list being added, when it is more than [track]. */
    val tracks: List<SpotifyTrack> = emptyList(),
    val notFound: Boolean = false,
    val loading: Boolean = true,
    val error: SpotifyError? = null,
    val query: String = "",
    val playlists: List<SpotifyPlaylist> = emptyList(),
    val containing: Set<String> = emptySet(),
    val adding: String? = null,
    val added: Set<String> = emptySet(),
) {
    val shown: List<SpotifyPlaylist>
        get() = playlists.filter { it.name.contains(query.trim(), ignoreCase = true) }
}

/** What [SpotifyPickerState.adding] holds while the playlist is still being made. */
internal const val NEW_PLAYLIST = ""

/**
 * The one "Add to Spotify playlist" dialog of the app: any menu opens it, the host draws it once.
 * [findOnSpotify] is the like sync's lookup of a YouTube song on Spotify, null when it has no confident match.
 */
class SpotifyPlaylistPicker(
    private val store: SpotifyLibraryStore,
    private val editor: SpotifyPlaylistEditor,
    private val remote: SpotifyPlaylistRemote,
    private val connection: SpotifyConnection,
    private val findOnSpotify: suspend (SongItem) -> SpotifyTrack?,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow<SpotifyPickerState?>(null)
    private var load: (suspend () -> Unit)? = null
    private var job: Job? = null

    /** Null while the dialog is closed. */
    val state: StateFlow<SpotifyPickerState?> = mutableState.asStateFlow()

    fun open(song: SongItem) = open(song.title) { listOfNotNull(song.takeUnless { it.isLocal }?.let { findOnSpotify(it) }) }

    fun open(track: SpotifyTrack) = open(track.name) { listOf(track) }

    /** For every track of a list named [title]; [tracks] loads them and may throw a [SpotifyError]. */
    fun openAll(
        title: String,
        tracks: suspend () -> List<SpotifyTrack>,
    ) = open(title, tracks)

    fun close() {
        job?.cancel()
        mutableState.value = null
    }

    /** After a failed lookup or a playlist list that did not load. */
    fun retry() {
        val load = load ?: return
        job?.cancel()
        mutableState.update { it?.copy(loading = true, error = null) }
        job = scope.launch { load() }
    }

    fun setQuery(query: String) = mutableState.update { it?.copy(query = query) }

    /** The edit is the editor's from here on: closing the dialog does not take it back. */
    fun add(playlist: SpotifyPlaylist) {
        val current = mutableState.value ?: return
        val track = current.track ?: return
        if (current.adding != null) return
        mutableState.value = current.copy(adding = playlist.id, error = null)
        scope.launch {
            val error = if (current.tracks.size > 1) editor.addAll(playlist.id, current.tracks) else editor.add(playlist.id, track).await()
            // Reported here; the playlist's own screen would otherwise show it again later.
            if (error != null) editor.dismissFailure(playlist.id)
            mutableState.update { state ->
                if (state?.track != track) return@update state
                state.copy(adding = null, error = error, added = if (error == null) state.added + playlist.id else state.added)
            }
        }
    }

    /** Makes a playlist named after the track, as Spotify's own "new playlist" on a song does, and adds the track to it. */
    fun addToNew() {
        val current = mutableState.value ?: return
        val track = current.track ?: return
        if (current.adding != null) return
        mutableState.value = current.copy(adding = NEW_PLAYLIST, error = null)
        scope.launch {
            var created: SpotifyPlaylist? = null
            val error =
                try {
                    val name = if (current.tracks.size > 1) current.title else track.name
                    val id = remote.create(name)
                    val owner = (connection.state.value as? SpotifyConnectionState.Connected)?.id
                    created = SpotifyPlaylist(id, "spotify:playlist:$id", name, ownerId = owner, trackCount = 0)
                    store.playlists(null).refresh()
                    if (current.tracks.size > 1) editor.addAll(id, current.tracks) else editor.add(id, track).await()?.also { editor.dismissFailure(id) }
                } catch (error: SpotifyError) {
                    error
                }
            mutableState.update { state ->
                if (state?.track != track) return@update state
                state.copy(
                    adding = null,
                    error = error,
                    playlists = listOfNotNull(created) + state.playlists,
                    added = if (error == null) state.added + listOfNotNull(created?.id) else state.added,
                )
            }
        }
    }

    private fun open(
        title: String,
        find: suspend () -> List<SpotifyTrack>,
    ) {
        job?.cancel()
        mutableState.value = SpotifyPickerState(title)
        val load: suspend () -> Unit = {
            try {
                val found = find()
                val track = found.firstOrNull()
                if (track == null) {
                    mutableState.update { it?.copy(loading = false, notFound = true) }
                } else {
                    mutableState.update { it?.copy(track = track, tracks = found) }
                    val shared = editableIds(track.uri, null)
                    val session = connection.state.value
                    val playlists = playlists(null).distinctBy { it.id }.filter { canEditPlaylist(it.ownerId, session) || it.id in shared }
                    // Of a whole list it is not said which playlists have it already.
                    val containing = playlists.filter { found.size == 1 && contains(it.id, track) }.mapTo(hashSetOf()) { it.id }
                    mutableState.update { it?.copy(loading = false, playlists = playlists, containing = containing) }
                }
            } catch (error: SpotifyError) {
                mutableState.update { it?.copy(loading = false, error = error) }
            }
        }
        this.load = load
        job = scope.launch { load() }
    }

    /** Every playlist of the library, folders included; what is cached is not asked for again. */
    private suspend fun playlists(folderUri: String?): List<SpotifyPlaylist> =
        store.playlists(folderUri).loadAll().flatMap {
            when (it) {
                is SpotifyLibraryItem.Playlist -> listOf(it.playlist)
                is SpotifyLibraryItem.Folder -> playlists(it.folder.uri)
            }
        }

    /**
     * The playlists Spotify lets the user add [itemUri] to, which is how those of others they contribute to are
     * known. Only ever adds to the user's own: when Spotify does not answer, the picker offers those alone.
     */
    private suspend fun editableIds(
        itemUri: String,
        folderUri: String?,
    ): Set<String> {
        val ids = hashSetOf<String>()
        try {
            var offset: Int? = 0
            while (offset != null) {
                val page = remote.editable(itemUri, folderUri, offset)
                page.items.forEach {
                    when (it) {
                        is SpotifyLibraryItem.Playlist -> ids += it.playlist.id
                        is SpotifyLibraryItem.Folder -> ids += editableIds(itemUri, it.folder.uri)
                    }
                }
                offset = page.nextOffset
            }
        } catch (error: SpotifyError) {
            // What was read so far still counts.
        }
        return ids
    }

    private fun contains(
        playlistId: String,
        track: SpotifyTrack,
    ) = store
        .cachedPlaylist(playlistId)
        ?.tracks
        ?.state
        ?.value
        ?.items
        ?.any { it.uri == track.uri } == true
}
