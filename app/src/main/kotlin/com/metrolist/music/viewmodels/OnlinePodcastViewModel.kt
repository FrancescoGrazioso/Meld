package com.metrolist.music.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.EpisodeItem
import com.metrolist.innertube.models.PodcastItem
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.PodcastEntity
import com.metrolist.music.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.LocalDateTime
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class OnlinePodcastViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    val database: MusicDatabase,
) : ViewModel() {
    private val podcastId = savedStateHandle.get<String>("podcastId")!!

    val podcast = MutableStateFlow<PodcastItem?>(null)
    val episodes = MutableStateFlow<List<EpisodeItem>>(emptyList())

    val libraryPodcast = podcast.flatMapLatest { p ->
        p?.let { database.podcast(it.id) } ?: flowOf(null)
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    private val _isLoading = MutableStateFlow(true)
    val isLoading = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    init {
        Timber.d("ViewModel init with podcastId: $podcastId")
        fetchPodcastData()
    }

    private fun fetchPodcastData() {
        viewModelScope.launch(Dispatchers.IO) {
            Timber.d("fetchPodcastData called for: $podcastId")
            _isLoading.value = true
            _error.value = null

            YouTube.podcastWithDebug(podcastId) { msg ->
                Timber.d(msg)
            }
                .onSuccess { podcastPage ->
                    Timber.d("Success! Podcast: ${podcastPage.podcast.title}, Episodes: ${podcastPage.episodes.size}")
                    podcast.value = podcastPage.podcast
                    episodes.value = podcastPage.episodes
                    _isLoading.value = false
                }.onFailure { throwable ->
                    Timber.e(throwable, "Failed to load podcast: ${throwable.message}")
                    _error.value = throwable.message ?: "Failed to load podcast"
                    _isLoading.value = false
                    reportException(throwable)
                }
        }
    }

    /**
     * Toggle saving podcast to library.
     * Uses YouTube.savePodcast() which calls the like/like endpoint with playlistId.
     */
    fun toggleSubscription(context: android.content.Context) {
        val currentPodcast = podcast.value ?: return
        val existingEntity = libraryPodcast.value
        val isCurrentlySaved = existingEntity?.inLibrary == true

        Timber.d("[PODCAST_LIB] toggleSubscription called - podcastId: ${currentPodcast.id}")
        Timber.d("[PODCAST_LIB] isCurrentlySaved: $isCurrentlySaved")

        viewModelScope.launch(Dispatchers.IO) {
            // Optimistic UI update - update local database first
            database.transaction {
                if (existingEntity != null) {
                    update(existingEntity.toggleBookmark())
                } else {
                    insert(
                        PodcastEntity(
                            id = currentPodcast.id,
                            title = currentPodcast.title,
                            author = currentPodcast.author?.name,
                            thumbnailUrl = currentPodcast.thumbnail,
                            bookmarkedAt = LocalDateTime.now(),
                        )
                    )
                }
            }

            // Use savePodcast API to save/unsave podcast show
            YouTube.savePodcast(currentPodcast.id, !isCurrentlySaved).onSuccess {
                Timber.d("[PODCAST_LIB] savePodcast API success!")
            }.onFailure { e ->
                Timber.e(e, "[PODCAST_LIB] savePodcast API failed")
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        context,
                        if (isCurrentlySaved) com.metrolist.music.R.string.error_podcast_unsubscribe
                        else com.metrolist.music.R.string.error_podcast_subscribe,
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    /**
     * Legacy method - now calls toggleSubscription
     */
    fun toggleLibrary(context: android.content.Context) = toggleSubscription(context)

    fun retry() {
        fetchPodcastData()
    }
}
