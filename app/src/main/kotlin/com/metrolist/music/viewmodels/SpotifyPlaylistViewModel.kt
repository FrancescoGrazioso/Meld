/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.playback.SpotifyYouTubeMapper
import com.metrolist.spotify.Spotify
import com.metrolist.spotify.models.SpotifyPlaylist
import com.metrolist.spotify.models.SpotifyPlaylistTrack
import com.metrolist.spotify.models.SpotifyTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class SpotifyPlaylistViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    database: MusicDatabase,
) : ViewModel() {
    val playlistId: String = savedStateHandle.get<String>("playlistId")
        ?: throw IllegalArgumentException("playlistId is required")
    val mapper = SpotifyYouTubeMapper(database)

    private val _playlist = MutableStateFlow<SpotifyPlaylist?>(null)
    val playlist = _playlist.asStateFlow()

    private val _tracks = MutableStateFlow<List<SpotifyTrack>>(emptyList())
    val tracks = _tracks.asStateFlow()

    /** Raw playlist items including uid, needed for mutations */
    private val _playlistItems = MutableStateFlow<List<SpotifyPlaylistTrack>>(emptyList())

    private val _isLoading = MutableStateFlow(true)
    val isLoading = _isLoading.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _mutationError = MutableStateFlow<String?>(null)
    val mutationError = _mutationError.asStateFlow()

    fun clearMutationError() {
        _mutationError.value = null
    }

    init {
        loadPlaylist()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _isRefreshing.value = true
            loadPlaylistInternal()
            _isRefreshing.value = false
        }
    }

    private fun loadPlaylist() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            loadPlaylistInternal()
        }
    }

    private suspend fun loadPlaylistInternal() {
        _error.value = null

        Spotify.playlist(playlistId).onSuccess { pl ->
            _playlist.value = pl
        }.onFailure { e ->
            Timber.e(e, "Failed to load Spotify playlist metadata")
            _error.value = e.message ?: "Failed to load playlist info"
        }

        Spotify.playlistTracks(playlistId, limit = 100, offset = 0).onSuccess { paging ->
            val allItems = paging.items
                .filter { it.track != null && !it.isLocal }
                .toMutableList()

            var offset = allItems.size
            val total = paging.total
            while (offset < total) {
                Spotify.playlistTracks(playlistId, limit = 100, offset = offset)
                    .onSuccess { nextPage ->
                        val nextItems = nextPage.items
                            .filter { it.track != null && !it.isLocal }
                        allItems.addAll(nextItems)
                        offset += nextItems.size
                    }
                    .onFailure {
                        offset = total
                    }
            }

            _playlistItems.value = allItems
            _tracks.value = allItems.mapNotNull { it.track }
            _isLoading.value = false
        }.onFailure { e ->
            _error.value = e.message ?: "Failed to load playlist tracks"
            _isLoading.value = false
            Timber.e(e, "Failed to load Spotify playlist tracks")
        }
    }

    fun retry() = loadPlaylist()

    // ── Playlist Mutations ───────────────────────────────────────────────

    /**
     * Removes a track from the playlist using optimistic update:
     * the track is removed from the UI immediately, then the GQL mutation
     * is fired. On failure the track list is rolled back.
     */
    fun removeTrack(track: SpotifyTrack) {
        val matchingItem = _playlistItems.value.firstOrNull { it.track?.id == track.id }
        val uid = matchingItem?.uid
        val uri = track.uri ?: "spotify:track:${track.id}"

        if (uid == null) {
            Timber.w("removeTrack: uid not found for ${track.id}, attempting removal by URI only")
        }

        val previousItems = _playlistItems.value
        val previousTracks = _tracks.value

        _playlistItems.value = previousItems.filter { it.track?.id != track.id }
        _tracks.value = previousTracks.filter { it.id != track.id }

        viewModelScope.launch(Dispatchers.IO) {
            val ref = Spotify.PlaylistItemRef(uri = uri, uid = uid ?: "")
            Spotify.removeTracksFromPlaylist(playlistId, listOf(ref))
                .onSuccess {
                    Timber.d("removeTrack: successfully removed ${track.id} from $playlistId")
                }
                .onFailure { e ->
                    Timber.e(e, "removeTrack: failed to remove ${track.id}, rolling back")
                    _playlistItems.value = previousItems
                    _tracks.value = previousTracks
                    _mutationError.value = e.message ?: "Failed to remove track"
                }
        }
    }

    /**
     * Renames the playlist. Uses optimistic update on the local name.
     */
    fun renamePlaylist(newName: String) {
        val previousPlaylist = _playlist.value ?: return
        if (newName.isBlank() || newName == previousPlaylist.name) return

        _playlist.value = previousPlaylist.copy(name = newName)

        viewModelScope.launch(Dispatchers.IO) {
            Spotify.editPlaylistAttributes(playlistId, newName = newName)
                .onSuccess {
                    Timber.d("renamePlaylist: renamed $playlistId to '$newName'")
                }
                .onFailure { e ->
                    Timber.e(e, "renamePlaylist: failed, rolling back")
                    _playlist.value = previousPlaylist
                    _mutationError.value = e.message ?: "Failed to rename playlist"
                }
        }
    }

    /**
     * Adds tracks to the playlist (for "Add to Spotify playlist" flow).
     */
    fun addTracks(trackUris: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            Spotify.addTracksToPlaylist(playlistId, trackUris)
                .onSuccess {
                    Timber.d("addTracks: added ${trackUris.size} tracks to $playlistId")
                    loadPlaylist()
                }
                .onFailure { e ->
                    Timber.e(e, "addTracks: failed")
                    _mutationError.value = e.message ?: "Failed to add tracks"
                }
        }
    }

    /**
     * Finds the uid associated with a given track in this playlist.
     */
    fun getTrackUid(trackId: String): String? =
        _playlistItems.value.firstOrNull { it.track?.id == trackId }?.uid
}
