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
import com.metrolist.music.playback.SoundCloudYouTubeMapper
import com.metrolist.soundcloud.SoundCloud
import com.metrolist.soundcloud.models.SoundCloudPlaylist
import com.metrolist.soundcloud.models.SoundCloudTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class SoundCloudPlaylistViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    database: MusicDatabase,
) : ViewModel() {
    val playlistId: Long = savedStateHandle.get<Long>("playlistId")
        ?: throw IllegalArgumentException("playlistId is required")
    val mapper = SoundCloudYouTubeMapper(database)

    private val _playlist = MutableStateFlow<SoundCloudPlaylist?>(null)
    val playlist = _playlist.asStateFlow()

    private val _tracks = MutableStateFlow<List<SoundCloudTrack>>(emptyList())
    val tracks = _tracks.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading = _isLoading.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

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
            _isLoading.value = false
        }
    }

    private suspend fun loadPlaylistInternal() {
        _error.value = null

        SoundCloud.playlist(playlistId).onSuccess { pl ->
            _playlist.value = pl
            _tracks.value = pl.tracks
        }.onFailure { e ->
            Timber.e(e, "Failed to load SoundCloud playlist")
            _error.value = e.message ?: "Failed to load playlist info"
        }
    }

    fun retry() = loadPlaylist()
}
