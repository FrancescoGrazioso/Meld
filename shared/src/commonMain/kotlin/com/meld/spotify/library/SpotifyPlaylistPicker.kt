package com.meld.spotify.library

import com.meld.spotify.SpotifyConnection
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

/**
 * The one "Add to Spotify playlist" dialog of the app: any menu opens it, the host draws it once.
 * [findOnSpotify] is the like sync's lookup of a YouTube song on Spotify, null when it has no confident match.
 */
class SpotifyPlaylistPicker(
    private val store: SpotifyLibraryStore,
    private val editor: SpotifyPlaylistEditor,
    private val connection: SpotifyConnection,
    private val findOnSpotify: suspend (SongItem) -> SpotifyTrack?,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow<SpotifyPickerState?>(null)
    private var load: (suspend () -> Unit)? = null
    private var job: Job? = null

    /** Null while the dialog is closed. */
    val state: StateFlow<SpotifyPickerState?> = mutableState.asStateFlow()

    fun open(song: SongItem) = open(song.title) { song.takeUnless { it.isLocal }?.let { findOnSpotify(it) } }

    fun open(track: SpotifyTrack) = open(track.name) { track }

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
            val error = editor.add(playlist.id, track).await()
            // Reported here; the playlist's own screen would otherwise show it again later.
            if (error != null) editor.dismissFailure(playlist.id)
            mutableState.update { state ->
                if (state?.track != track) return@update state
                state.copy(adding = null, error = error, added = if (error == null) state.added + playlist.id else state.added)
            }
        }
    }

    private fun open(
        title: String,
        find: suspend () -> SpotifyTrack?,
    ) {
        job?.cancel()
        mutableState.value = SpotifyPickerState(title)
        val load: suspend () -> Unit = {
            try {
                val track = find()
                if (track == null) {
                    mutableState.update { it?.copy(loading = false, notFound = true) }
                } else {
                    mutableState.update { it?.copy(track = track) }
                    val playlists = playlists(null).distinctBy { it.id }.filter { canEditPlaylist(it.ownerId, connection.state.value) }
                    val containing = playlists.filter { contains(it.id, track) }.mapTo(hashSetOf()) { it.id }
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
