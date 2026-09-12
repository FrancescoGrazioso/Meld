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
import com.metrolist.innertube.models.SongItem
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
import com.metrolist.music.constants.SpotifyAccessTokenKey
import com.metrolist.music.utils.SpotifyTokenManager
import com.metrolist.music.constants.UseSpotifySearchKey
import com.metrolist.music.models.ItemsPage
import com.metrolist.music.utils.SearchRoutes
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
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
    val query = SearchRoutes.decodeQuery(savedStateHandle.get<String>("query").orEmpty())
    val filter = MutableStateFlow<YouTube.SearchFilter?>(null)
    var summaryPage by mutableStateOf<SearchSummaryPage?>(null)
    val viewStateMap = mutableStateMapOf<String, ItemsPage?>()

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
    private suspend fun resolveSearchMetadata(items: List<YTItem>): List<YTItem> =
        coroutineScope {
            val knownDurations =
                items
                    .filterIsInstance<SongItem>()
                    .mapNotNull { song -> song.duration?.let { song.id to it } }
                    .toMap()
            val missingDurationIds =
                items
                    .filterIsInstance<SongItem>()
                    .filter { it.duration == null && it.id !in knownDurations }
                    .map { it.id }
                    .distinct()
            val resolvedArtists = async { YouTube.resolveArtistIds(items) }
            val fetchedDurations =
                async {
                    if (missingDurationIds.isEmpty()) {
                        emptyMap()
                    } else {
                        YouTube
                            .queue(videoIds = missingDurationIds)
                            .getOrDefault(emptyList())
                            .mapNotNull { song -> song.duration?.let { song.id to it } }
                            .toMap()
                    }
                }
            val durations = knownDurations + fetchedDurations.await()

            resolvedArtists.await().map { item ->
                if (item is SongItem && item.duration == null) {
                    item.copy(duration = durations[item.id])
                } else {
                    item
                }
            }
        }

    private suspend fun loadSummaryPage() {
        if (summaryPage == null) {
            YouTube
                .searchSummary(query)
                .onSuccess { page ->
                    val resolvedItems = resolveSearchMetadata(page.summaries.flatMap { it.items })
                    var offset = 0
                    val resolvedSummaries =
                        page.summaries.map { summary ->
                            val nextOffset = offset + summary.items.size
                            val resolvedSummary = summary.copy(items = resolvedItems.subList(offset, nextOffset))
                            offset = nextOffset
                            resolvedSummary
                        }
                    val resolvedPage = page.copy(summaries = resolvedSummaries)
                    val hideExplicit = context.dataStore.get(HideExplicitKey, false)
                    val hideVideoSongs = context.dataStore.get(HideVideoSongsKey, false)
                    val hideYoutubeShorts = context.dataStore.get(HideYoutubeShortsKey, false)
                    summaryPage =
                        resolvedPage
                            .filterExplicit(hideExplicit)
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
            filter.collect { filter ->
                if (filter == null) {
                    loadSummaryPage()
                } else if (filter == YouTube.SearchFilter.FILTER_EPISODE) {
                    // The FILTER_EPISODE API returns episodes in a format that differs from the
                    // summary search: playlistItemData is absent and the subtitle structure is
                    // different, making reliable isEpisode detection fail for many items.
                    // Reuse the "Episodes" section from the summary page instead — it is already
                    // parsed correctly by fromMusicResponsiveListItemRenderer and guaranteed to
                    // show the same results as the episodes section in the "All" filter.
                    if (viewStateMap[filter.value] == null) {
                        loadSummaryPage()
                        summaryPage?.let { page ->
                            val episodes = page.summaries
                                .firstOrNull { it.title == "Episodes" }
                                ?.items
                                .orEmpty()
                            viewStateMap[filter.value] = ItemsPage(episodes, null)
                        }
                    }
                } else {
                    if (viewStateMap[filter.value] == null) {
                        YouTube
                            .search(query, filter)
                            .onSuccess { result ->
                                val resolvedItems = resolveSearchMetadata(result.items)
                                val hideExplicit = context.dataStore.get(HideExplicitKey, false)
                                val hideVideoSongs = context.dataStore.get(HideVideoSongsKey, false)
                                val hideYoutubeShorts = context.dataStore.get(HideYoutubeShortsKey, false)
                                viewStateMap[filter.value] =
                                    ItemsPage(
                                        resolvedItems
                                            .distinctBy { it.id }
                                            .filterExplicit(hideExplicit)
                                            .filterVideoSongs(hideVideoSongs)
                                            .filterYoutubeShorts(hideYoutubeShorts),
                                        result.continuation,
                                    )
                            }.onFailure {
                                reportException(it)
                            }
                    }
                }
            }
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
        } else {
            loadMoreYouTube()
        }
    }

    private fun loadMoreYouTube() {
        val filterValue = filter.value?.value ?: return
        viewModelScope.launch {
            val viewState = viewStateMap[filterValue] ?: return@launch
            val continuation = viewState.continuation ?: return@launch
            val searchResult =
                YouTube.searchContinuation(continuation).getOrNull() ?: return@launch
            val resolvedItems = resolveSearchMetadata(searchResult.items)
            val hideExplicit = context.dataStore.get(HideExplicitKey, false)
            val hideVideoSongs = context.dataStore.get(HideVideoSongsKey, false)
            val hideYoutubeShorts = context.dataStore.get(HideYoutubeShortsKey, false)
            val newItems = resolvedItems
                .filterExplicit(hideExplicit)
                .filterVideoSongs(hideVideoSongs)
                .filterYoutubeShorts(hideYoutubeShorts)
            viewStateMap[filterValue] = ItemsPage(
                (viewState.items + newItems).distinctBy { it.id },
                searchResult.continuation
            )
        }
    }

    private fun loadMoreSpotify() {
        val filterType = spotifyFilter.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val viewState = viewStateMap[filterType] ?: return@launch
            val continuation = viewState.continuation ?: return@launch

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
        }
    }

}
