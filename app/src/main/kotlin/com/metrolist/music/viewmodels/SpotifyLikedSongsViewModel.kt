/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.playback.SpotifyYouTubeMapper
import com.metrolist.spotify.Spotify
import com.metrolist.spotify.models.SpotifyTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class SpotifyLikedSongsViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    database: MusicDatabase,
) : ViewModel() {
    val mapper = SpotifyYouTubeMapper(database)

    private val _tracks = MutableStateFlow<List<SpotifyTrack>>(emptyList())
    val tracks = _tracks.asStateFlow()

    private val _total = MutableStateFlow(0)
    val total = _total.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading = _isLoading.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    init {
        loadLikedSongs()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _isRefreshing.value = true
            loadLikedSongsInternal()
            _isRefreshing.value = false
        }
    }

    private fun loadLikedSongs() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            loadLikedSongsInternal()
        }
    }

    private suspend fun loadLikedSongsInternal() {
        _error.value = null

        Spotify.likedSongs(limit = PAGE_SIZE, offset = 0).onSuccess { paging ->
            val firstPage = paging.items
                .map { it.track }
                .filter { !it.isLocal }

            _total.value = paging.total

            // Emit first page immediately so the UI shows content within ~500ms
            _tracks.value = firstPage
            _isLoading.value = false

            val remaining = paging.total - paging.items.size
            if (remaining <= 0) {
                Timber.d("SpotifyLikedSongs: Loaded ${firstPage.size} tracks (all in first page)")
                return@onSuccess
            }

            // Fetch remaining pages in parallel batches to avoid rate-limiting.
            // GROUP_SIZE concurrent requests at a time, emitting results progressively.
            val allTracks = firstPage.toMutableList()
            val pageCount = (remaining + PAGE_SIZE - 1) / PAGE_SIZE
            val offsets = (0 until pageCount).map { paging.items.size + it * PAGE_SIZE }

            for (batch in offsets.chunked(PARALLEL_GROUP_SIZE)) {
                val results = coroutineScope {
                    batch.map { offset ->
                        async { Spotify.likedSongs(limit = PAGE_SIZE, offset = offset) }
                    }.awaitAll()
                }

                var failed = false
                for (result in results) {
                    result.onSuccess { page ->
                        allTracks.addAll(page.items.map { it.track }.filter { !it.isLocal })
                    }.onFailure { e ->
                        Timber.e(e, "Failed to load liked songs page")
                        failed = true
                    }
                }

                // Emit progressively so the UI updates as pages arrive
                _tracks.value = allTracks.toList()

                if (failed) break
            }

            Timber.d("SpotifyLikedSongs: Loaded ${allTracks.size} tracks (total=${paging.total})")
        }.onFailure { e ->
            _error.value = e.message ?: "Failed to load liked songs"
            _isLoading.value = false
            Timber.e(e, "Failed to load Spotify liked songs")
        }
    }

    fun retry() = loadLikedSongs()

    companion object {
        private const val PAGE_SIZE = 50
        /** How many pages to fetch in parallel per batch. Keeps rate-limit risk low. */
        private const val PARALLEL_GROUP_SIZE = 5
    }
}
