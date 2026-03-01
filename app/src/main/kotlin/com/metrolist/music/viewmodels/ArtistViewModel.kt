/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.filterExplicit
import com.metrolist.innertube.models.filterVideoSongs
import com.metrolist.innertube.models.filterYoutubeShorts
import com.metrolist.innertube.pages.ArtistPage
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.constants.HideVideoSongsKey
import com.metrolist.music.constants.HideYoutubeShortsKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.extensions.filterExplicit
import com.metrolist.music.extensions.filterExplicitAlbums
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import com.metrolist.music.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import com.metrolist.music.extensions.filterVideoSongs as filterVideoSongsLocal

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ArtistViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    database: MusicDatabase,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val artistId = savedStateHandle.get<String>("artistId")!!
    var artistPage by mutableStateOf<ArtistPage?>(null)

    private val _isChannelSubscribed = MutableStateFlow(false)
    val isChannelSubscribed = _isChannelSubscribed.asStateFlow()

    val libraryArtist = database.artist(artistId)
        .stateIn(viewModelScope, SharingStarted.Lazily, null)
    val librarySongs = context.dataStore.data
        .map { (it[HideExplicitKey] ?: false) to (it[HideVideoSongsKey] ?: false) }
        .distinctUntilChanged()
        .flatMapLatest { (hideExplicit, hideVideoSongs) ->
            database.artistSongsPreview(artistId).map { it.filterExplicit(hideExplicit).filterVideoSongsLocal(hideVideoSongs) }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val libraryAlbums = context.dataStore.data
        .map { it[HideExplicitKey] ?: false }
        .distinctUntilChanged()
        .flatMapLatest { hideExplicit ->
            database.artistAlbumsPreview(artistId).map { it.filterExplicitAlbums(hideExplicit) }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        // Load artist page and reload when hide explicit setting changes
        viewModelScope.launch {
            context.dataStore.data
                .map {
                    Triple(
                        it[HideExplicitKey] ?: false,
                        it[HideVideoSongsKey] ?: false,
                        it[HideYoutubeShortsKey] ?: false
                    )
                }
                .distinctUntilChanged()
                .collect {
                    fetchArtistsFromYTM()
                }
        }
    }

    fun fetchArtistsFromYTM() {
        viewModelScope.launch {
            val hideExplicit = context.dataStore.get(HideExplicitKey, false)
            val hideVideoSongs = context.dataStore.get(HideVideoSongsKey, false)
            val hideYoutubeShorts = context.dataStore.get(HideYoutubeShortsKey, false)
            YouTube.artist(artistId)
                .onSuccess { page ->
                    val filteredSections = page.sections
                        .map { section ->
                            section.copy(items = section.items.filterExplicit(hideExplicit).filterVideoSongs(hideVideoSongs).filterYoutubeShorts(hideYoutubeShorts))
                        }
                        .filter { section -> section.items.isNotEmpty() }

                    artistPage = page.copy(sections = filteredSections)
                    _isChannelSubscribed.value = page.isSubscribed
                }.onFailure {
                    reportException(it)
                }
        }
    }

    fun toggleChannelSubscription(appContext: Context) {
        val channelId = artistPage?.artist?.channelId ?: artistId
        val isCurrentlySubscribed = _isChannelSubscribed.value

        Timber.d("[ARTIST_CHANNEL] toggleChannelSubscription - channelId: $channelId, isCurrentlySubscribed: $isCurrentlySubscribed")

        // Optimistic UI update
        _isChannelSubscribed.value = !isCurrentlySubscribed

        viewModelScope.launch(Dispatchers.IO) {
            // Use NonCancellable to ensure API call completes even if user navigates away
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                var success = false
                for (attempt in 1..3) {
                    YouTube.subscribeChannel(channelId, !isCurrentlySubscribed)
                        .onSuccess {
                            Timber.d("[ARTIST_CHANNEL] subscribeChannel API success on attempt $attempt")
                            success = true
                            com.metrolist.music.utils.PodcastRefreshTrigger.triggerRefresh()
                            kotlinx.coroutines.withContext(Dispatchers.Main) {
                                android.widget.Toast.makeText(
                                    appContext,
                                    if (!isCurrentlySubscribed) com.metrolist.music.R.string.subscribed_to_channel
                                    else com.metrolist.music.R.string.unsubscribed_from_channel,
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                        .onFailure { e ->
                            Timber.e(e, "[ARTIST_CHANNEL] subscribeChannel API failed on attempt $attempt")
                        }
                    if (success) break
                    if (attempt < 3) kotlinx.coroutines.delay(500)
                }

                if (!success) {
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            appContext,
                            com.metrolist.music.R.string.error_subscribe_channel,
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }
}
