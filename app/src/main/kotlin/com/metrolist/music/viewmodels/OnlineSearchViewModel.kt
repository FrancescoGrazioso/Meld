/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.YTItem
import com.metrolist.innertube.models.filterExplicit
import com.metrolist.innertube.models.filterVideoSongs
import com.metrolist.innertube.models.filterYoutubeShorts
import com.metrolist.innertube.pages.SearchSummary
import com.metrolist.innertube.pages.SearchSummaryPage
import com.metrolist.music.constants.EnableSpotifyKey
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.constants.HideVideoSongsKey
import com.metrolist.music.constants.HideYoutubeShortsKey
import com.metrolist.music.R
import com.metrolist.music.constants.SpotifyAccessTokenKey
import com.metrolist.music.utils.SpotifyTokenManager
import com.metrolist.music.constants.UseSpotifySearchKey
import com.metrolist.music.models.ItemsPage
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import com.metrolist.music.utils.reportException
import com.metrolist.music.utils.toAlbumItem
import com.metrolist.music.utils.toArtistItem
import com.metrolist.music.utils.toPlaylistItem
import com.metrolist.music.utils.toSongItem
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.playback.SpotifyYouTubeMapper
import com.metrolist.spotify.Spotify
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

@HiltViewModel
class OnlineSearchViewModel
@Inject
constructor(
    @ApplicationContext val context: Context,
    savedStateHandle: SavedStateHandle,
    database: MusicDatabase,
) : ViewModel() {

    val spotifyYouTubeMapper = SpotifyYouTubeMapper(database)
    val query = try {
        URLDecoder.decode(savedStateHandle.get<String>("query")!!, "UTF-8")
    } catch (e: IllegalArgumentException) {
        savedStateHandle.get<String>("query")!!
    }
    val filter = MutableStateFlow<YouTube.SearchFilter?>(null)
    var summaryPage by mutableStateOf<SearchSummaryPage?>(null)
    val viewStateMap = mutableStateMapOf<String, ItemsPage?>()

    // The YouTube search-summary endpoint is intentionally lightweight, but it only
    // represents the first/top results for each section and does not expose a
    // continuation token. "All" therefore uses the real paginated category
    // endpoints and merges their pages into deterministic sections.
    private var allInitialized = false
    private var allLoadJob: Job? = null
    private val inFlightRequests = ConcurrentHashMap.newKeySet<String>()

    private fun allFilterSpecs(hideVideoSongs: Boolean): List<Pair<YouTube.SearchFilter, String>> {
        val specs = mutableListOf(
            YouTube.SearchFilter.FILTER_SONG to context.getString(R.string.filter_songs),
        )
        if (!hideVideoSongs) {
            specs += YouTube.SearchFilter.FILTER_VIDEO to context.getString(R.string.filter_videos)
        }
        specs += listOf(
            YouTube.SearchFilter.FILTER_ALBUM to context.getString(R.string.filter_albums),
            YouTube.SearchFilter.FILTER_ARTIST to context.getString(R.string.filter_artists),
            YouTube.SearchFilter.FILTER_COMMUNITY_PLAYLIST to context.getString(R.string.filter_community_playlists),
            YouTube.SearchFilter.FILTER_FEATURED_PLAYLIST to context.getString(R.string.filter_featured_playlists),
            YouTube.SearchFilter.FILTER_PODCAST to context.getString(R.string.filter_podcasts),
            YouTube.SearchFilter.FILTER_EPISODE to context.getString(R.string.filter_episodes),
            YouTube.SearchFilter.FILTER_PROFILE to context.getString(R.string.filter_profiles),
        )
        return specs
    }

    private fun applySearchFilters(items: List<YTItem>): List<YTItem> {
        val hideExplicit = context.dataStore.get(HideExplicitKey, false)
        val hideVideoSongs = context.dataStore.get(HideVideoSongsKey, false)
        val hideYoutubeShorts = context.dataStore.get(HideYoutubeShortsKey, false)
        return items
            .distinctBy { it.id }
            .filterExplicit(hideExplicit)
            .filterVideoSongs(hideVideoSongs)
            .filterYoutubeShorts(hideYoutubeShorts)
    }

    private fun rebuildAllSummary() {
        val hideVideoSongs = context.dataStore.get(HideVideoSongsKey, false)
        val summaries = allFilterSpecs(hideVideoSongs).mapNotNull { (filter, title) ->
            val items = viewStateMap[filter.value]?.items.orEmpty()
            items.takeIf { it.isNotEmpty() }?.let { SearchSummary(title = title, items = it) }
        }
        summaryPage = SearchSummaryPage(summaries = summaries)
    }

    fun hasMoreAll(): Boolean {
        val hideVideoSongs = context.dataStore.get(HideVideoSongsKey, false)
        return allFilterSpecs(hideVideoSongs).any { (filter, _) ->
            viewStateMap[filter.value]?.continuation != null
        }
    }

    private suspend fun loadAllPage() {
        if (allInitialized) {
            rebuildAllSummary()
            return
        }

        coroutineScope {
            val hideVideoSongs = context.dataStore.get(HideVideoSongsKey, false)
            val specs = allFilterSpecs(hideVideoSongs)

        // Episode search has known parser incompatibilities on the dedicated endpoint;
        // use the already-parsed episode section from the summary response for that one
        // category while the rest use fully-paginated endpoints.
        val episodeSummaryDeferred = async(Dispatchers.IO) {
            YouTube.searchSummary(query).getOrNull()
        }

        val results = specs
            .filter { it.first != YouTube.SearchFilter.FILTER_EPISODE }
            .map { (filter, _) ->
                async(Dispatchers.IO) {
                    filter to YouTube.search(query, filter)
                        .getOrNull()
                }
            }
            .awaitAll()

        results.forEach { (filter, result) ->
            if (result != null) {
                viewStateMap[filter.value] =
                    ItemsPage(
                        items = applySearchFilters(result.items),
                        continuation = result.continuation,
                    )
            }
        }

        val episodeSummary = episodeSummaryDeferred.await()
        if (episodeSummary != null) {
            val filtered = episodeSummary
                .filterExplicit(context.dataStore.get(HideExplicitKey, false))
                .filterVideoSongs(hideVideoSongs)
                .filterYoutubeShorts(context.dataStore.get(HideYoutubeShortsKey, false))
            val episodeItems = filtered.summaries
                .firstOrNull { summary ->
                    summary.title.equals("Episodes", ignoreCase = true) ||
                        summary.title.equals(context.getString(R.string.filter_episodes), ignoreCase = true)
                }?.items.orEmpty()
            viewStateMap[YouTube.SearchFilter.FILTER_EPISODE.value] =
                ItemsPage(items = episodeItems.distinctBy { it.id }, continuation = null)
        }

            allInitialized = true
            rebuildAllSummary()
        }
    }


    /**
     * Whether this search is using Spotify as its source.
     * Exposed to the UI so it can show the correct filter chips.
     */
    val isSpotifySearch = MutableStateFlow(false)

    /**
     * Spotify-specific filter: maps to the API "type" parameter.
     * null = show all types (summary mode)
     */
    val spotifyFilter = MutableStateFlow<String?>(null)

    private suspend fun loadSummaryPage() {
        if (summaryPage == null) {
            YouTube
                .searchSummary(query)
                .onSuccess {
                    val hideExplicit = context.dataStore.get(HideExplicitKey, false)
                    val hideVideoSongs = context.dataStore.get(HideVideoSongsKey, false)
                    val hideYoutubeShorts = context.dataStore.get(HideYoutubeShortsKey, false)
                    summaryPage =
                        it.filterExplicit(hideExplicit)
                          .filterVideoSongs(hideVideoSongs)
                          .filterYoutubeShorts(hideYoutubeShorts)
                }.onFailure {
                    reportException(it)
                }
        }
    }


    init {
        viewModelScope.launch(Dispatchers.IO) {
            val useSpotify = shouldUseSpotifySearch()
            isSpotifySearch.value = useSpotify

            if (useSpotify) {
                initSpotifySearch()
            } else {
                initYouTubeSearch()
            }
        }
    }

    private suspend fun shouldUseSpotifySearch(): Boolean {
        val prefs = context.dataStore.data.first()
        val enabled = prefs[EnableSpotifyKey] ?: false
        val useForSearch = prefs[UseSpotifySearchKey] ?: false
        val hasToken = (prefs[SpotifyAccessTokenKey] ?: "").isNotEmpty()
        return enabled && useForSearch && hasToken
    }

    private fun initYouTubeSearch() {
        viewModelScope.launch {
            filter.collect { selectedFilter ->
                if (selectedFilter == null) {
                    loadAllPage()
                } else if (selectedFilter == YouTube.SearchFilter.FILTER_EPISODE) {
                    if (viewStateMap[selectedFilter.value] == null) {
                        loadAllPage()
                    }
                } else if (viewStateMap[selectedFilter.value] == null) {
                    loadSingleFilterPage(selectedFilter)
                }
            }
        }
    }

    private suspend fun loadSingleFilterPage(selectedFilter: YouTube.SearchFilter) {
        val requestKey = "search:${selectedFilter.value}"
        if (!inFlightRequests.add(requestKey)) return
        try {
            YouTube
                .search(query, selectedFilter)
                .onSuccess { result ->
                viewStateMap[selectedFilter.value] =
                    ItemsPage(
                        items = applySearchFilters(result.items),
                        continuation = result.continuation,
                    )
            }.onFailure {
                    reportException(it)
                }
        } finally {
            inFlightRequests.remove(requestKey)
        }
    }

    private fun initSpotifySearch() {
        viewModelScope.launch(Dispatchers.IO) {
            if (!SpotifyTokenManager.ensureAuthenticated()) {
                Timber.w("SearchVM: Spotify auth failed, falling back to YouTube")
                isSpotifySearch.value = false
                initYouTubeSearch()
                return@launch
            }

            // Load summary (all types) immediately
            loadSpotifySummary()

            // Observe Spotify filter changes for filtered searches
            spotifyFilter.collect { filterType ->
                if (filterType != null) {
                    loadSpotifyFiltered(filterType)
                }
            }
        }
    }

    private suspend fun loadSpotifySummary() {
        if (summaryPage != null) return

        val hideExplicit = context.dataStore.get(HideExplicitKey, false)

        // Try full search first; if deserialization fails (e.g. null playlist items),
        // retry without playlists as a fallback
        val result = Spotify.search(
            query = query,
            types = listOf("track", "album", "artist", "playlist"),
            limit = 10,
        ).getOrElse { firstError ->
            Timber.w(firstError, "SearchVM: Full Spotify search failed, retrying without playlists")
            Spotify.search(
                query = query,
                types = listOf("track", "album", "artist"),
                limit = 10,
            ).getOrElse { secondError ->
                Timber.e(secondError, "SearchVM: Spotify search failed completely")
                reportException(secondError)
                return
            }
        }

        val summaries = mutableListOf<SearchSummary>()

        result.tracks?.items?.filter { it.id.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }?.let { tracks ->
                val items: List<YTItem> = tracks
                    .filter { !hideExplicit || !it.explicit }
                    .map { it.toSongItem() }
                if (items.isNotEmpty()) summaries.add(SearchSummary(title = "Songs", items = items))
            }
        result.albums?.items?.filter { it.id.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }?.let { albums ->
                val items: List<YTItem> = albums.map { it.toAlbumItem() }
                if (items.isNotEmpty()) summaries.add(SearchSummary(title = "Albums", items = items))
            }
        result.artists?.items?.filter { it.id.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }?.let { artists ->
                val items: List<YTItem> = artists.map { it.toArtistItem() }
                if (items.isNotEmpty()) summaries.add(SearchSummary(title = "Artists", items = items))
            }
        result.playlists?.items?.filter { it.id.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }?.let { playlists ->
                val items: List<YTItem> = playlists.map { it.toPlaylistItem() }
                if (items.isNotEmpty()) summaries.add(SearchSummary(title = "Playlists", items = items))
            }

        summaryPage = SearchSummaryPage(summaries = summaries)
    }

    private suspend fun loadSpotifyFiltered(filterType: String) {
        if (viewStateMap[filterType] != null) return

        val hideExplicit = context.dataStore.get(HideExplicitKey, false)
        val offset = 0
        val limit = 20

        Spotify.search(
            query = query,
            types = listOf(filterType),
            limit = limit,
            offset = offset,
        ).onSuccess { result ->
            val items: List<YTItem> = when (filterType) {
                "track" -> result.tracks?.items
                    ?.filter { !hideExplicit || !it.explicit }
                    ?.map { it.toSongItem() } ?: emptyList()
                "album" -> result.albums?.items?.map { it.toAlbumItem() } ?: emptyList()
                "artist" -> result.artists?.items?.map { it.toArtistItem() } ?: emptyList()
                "playlist" -> result.playlists?.items?.map { it.toPlaylistItem() } ?: emptyList()
                else -> emptyList()
            }

            // Spotify paging: if we got limit items, there are likely more
            val hasMore = when (filterType) {
                "track" -> (result.tracks?.items?.size ?: 0) >= limit
                "album" -> (result.albums?.items?.size ?: 0) >= limit
                "artist" -> (result.artists?.items?.size ?: 0) >= limit
                "playlist" -> (result.playlists?.items?.size ?: 0) >= limit
                else -> false
            }

            viewStateMap[filterType] = ItemsPage(
                items = items.distinctBy { it.id },
                // Encode offset in continuation string for Spotify pagination
                continuation = if (hasMore) "spotify:$filterType:${offset + limit}" else null,
            )
        }.onFailure {
            Timber.e(it, "SearchVM: Spotify filtered search failed for type=$filterType")
            reportException(it)
        }
    }

    fun loadMore() {
        if (isSpotifySearch.value) {
            loadMoreSpotify()
        } else if (filter.value == null) {
            if (allLoadJob?.isActive == true) return
            allLoadJob =
                viewModelScope.launch {
                    val hideVideoSongs = context.dataStore.get(HideVideoSongsKey, false)
                    val specs = allFilterSpecs(hideVideoSongs)

                    val updates = coroutineScope {
                        specs.mapNotNull { (selectedFilter, _) ->
                            val current = viewStateMap[selectedFilter.value] ?: return@mapNotNull null
                            val continuation = current.continuation ?: return@mapNotNull null
                            val requestKey = "all-more:${selectedFilter.value}:$continuation"
                            if (!inFlightRequests.add(requestKey)) return@mapNotNull null
                            async(Dispatchers.IO) {
                                try {
                                    val result = YouTube.searchContinuation(continuation).getOrNull()
                                    selectedFilter to result
                                } finally {
                                    inFlightRequests.remove(requestKey)
                                }
                            }
                        }.awaitAll()
                    }

                    updates.forEach { (selectedFilter, result) ->
                        if (result != null) {
                            val current = viewStateMap[selectedFilter.value] ?: return@forEach
                            val merged = applySearchFilters(current.items + result.items)
                            viewStateMap[selectedFilter.value] =
                                ItemsPage(items = merged, continuation = result.continuation)
                        }
                    }
                    rebuildAllSummary()
                }
        } else {
            loadMoreYouTube()
        }
    }

    private fun loadMoreYouTube() {
        val filterValue = filter.value?.value ?: return
        viewModelScope.launch {
            val viewState = viewStateMap[filterValue] ?: return@launch
            val continuation = viewState.continuation ?: return@launch
            val requestKey = "more:$filterValue:$continuation"
            if (!inFlightRequests.add(requestKey)) return@launch
            try {
                val searchResult = YouTube.searchContinuation(continuation).getOrNull() ?: return@launch
                viewStateMap[filterValue] =
                    ItemsPage(
                        items = applySearchFilters(viewState.items + searchResult.items),
                        continuation = searchResult.continuation,
                    )
            } finally {
                inFlightRequests.remove(requestKey)
            }
        }
    }

    private fun loadMoreSpotify() {
        val filterType = spotifyFilter.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val viewState = viewStateMap[filterType] ?: return@launch
            val continuation = viewState.continuation ?: return@launch
            val requestKey = "spotify-more:$filterType:$continuation"
            if (!inFlightRequests.add(requestKey)) return@launch
            try {

            // Parse continuation: "spotify:type:offset"
            val parts = continuation.split(":")
            if (parts.size != 3) return@launch
            val offset = parts[2].toIntOrNull() ?: return@launch
            val limit = 20
            val hideExplicit = context.dataStore.get(HideExplicitKey, false)

            if (!SpotifyTokenManager.ensureAuthenticated()) return@launch

            Spotify.search(
                query = query,
                types = listOf(filterType),
                limit = limit,
                offset = offset,
            ).onSuccess { result ->
                val newItems: List<YTItem> = when (filterType) {
                    "track" -> result.tracks?.items
                        ?.filter { !hideExplicit || !it.explicit }
                        ?.map { it.toSongItem() } ?: emptyList()
                    "album" -> result.albums?.items?.map { it.toAlbumItem() } ?: emptyList()
                    "artist" -> result.artists?.items?.map { it.toArtistItem() } ?: emptyList()
                    "playlist" -> result.playlists?.items?.map { it.toPlaylistItem() } ?: emptyList()
                    else -> emptyList()
                }

                val hasMore = when (filterType) {
                    "track" -> (result.tracks?.items?.size ?: 0) >= limit
                    "album" -> (result.albums?.items?.size ?: 0) >= limit
                    "artist" -> (result.artists?.items?.size ?: 0) >= limit
                    "playlist" -> (result.playlists?.items?.size ?: 0) >= limit
                    else -> false
                }

                viewStateMap[filterType] = ItemsPage(
                    (viewState.items + newItems).distinctBy { it.id },
                    if (hasMore) "spotify:$filterType:${offset + limit}" else null,
                )
                }.onFailure {
                    Timber.e(it, "SearchVM: Spotify loadMore failed")
                    reportException(it)
                }
            } finally {
                inFlightRequests.remove(requestKey)
            }
        }
    }

}
