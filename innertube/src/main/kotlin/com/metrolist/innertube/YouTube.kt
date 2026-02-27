package com.metrolist.innertube

import com.metrolist.innertube.models.AccountInfo
import com.metrolist.innertube.models.YTItem
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.BrowseEndpoint
import com.metrolist.innertube.models.GridRenderer
import com.metrolist.innertube.models.MediaInfo
import com.metrolist.innertube.models.MusicResponsiveListItemRenderer
import com.metrolist.innertube.models.MusicTwoRowItemRenderer
import com.metrolist.innertube.models.MusicCarouselShelfRenderer
import com.metrolist.innertube.models.MusicShelfRenderer
import com.metrolist.innertube.models.SectionListRenderer
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.PodcastItem
import com.metrolist.innertube.models.EpisodeItem
import com.metrolist.innertube.models.SearchSuggestions
import com.metrolist.innertube.models.Run
import com.metrolist.innertube.models.Runs
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.TasteArtist
import com.metrolist.innertube.models.TasteProfile
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_ATV
import com.metrolist.innertube.models.YouTubeClient
import com.metrolist.innertube.models.YouTubeClient.Companion.WEB
import com.metrolist.innertube.models.YouTubeClient.Companion.WEB_REMIX
import com.metrolist.innertube.models.YouTubeLocale
import com.metrolist.innertube.models.getContinuation
import com.metrolist.innertube.models.getItems
import com.metrolist.innertube.models.oddElements
import com.metrolist.innertube.models.response.AccountMenuResponse
import com.metrolist.innertube.models.response.BrowseResponse
import com.metrolist.innertube.models.response.CreatePlaylistResponse
import com.metrolist.innertube.models.response.EditPlaylistResponse
import com.metrolist.innertube.models.response.FeedbackResponse
import com.metrolist.innertube.models.response.GetQueueResponse
import com.metrolist.innertube.models.response.GetSearchSuggestionsResponse
import com.metrolist.innertube.models.response.GetTranscriptResponse
import com.metrolist.innertube.models.response.ImageUploadResponse
import com.metrolist.innertube.models.response.NextResponse
import com.metrolist.innertube.models.response.PlayerResponse
import com.metrolist.innertube.models.response.SearchResponse
import com.metrolist.innertube.pages.AlbumPage
import com.metrolist.innertube.pages.ArtistItemsContinuationPage
import com.metrolist.innertube.pages.ArtistItemsPage
import com.metrolist.innertube.pages.ArtistPage
import com.metrolist.innertube.pages.ChartsPage
import com.metrolist.innertube.pages.BrowseResult
import com.metrolist.innertube.pages.ExplorePage
import com.metrolist.innertube.pages.HistoryPage
import com.metrolist.innertube.pages.HomePage
import com.metrolist.innertube.pages.LibraryContinuationPage
import com.metrolist.innertube.pages.LibraryPage
import com.metrolist.innertube.pages.MoodAndGenres
import com.metrolist.innertube.pages.NewReleaseAlbumPage
import com.metrolist.innertube.pages.NextPage
import com.metrolist.innertube.pages.NextResult
import com.metrolist.innertube.pages.PageHelper
import com.metrolist.innertube.pages.PlaylistContinuationPage
import com.metrolist.innertube.pages.PlaylistPage
import com.metrolist.innertube.pages.PodcastPage
import com.metrolist.innertube.pages.RelatedPage
import com.metrolist.innertube.pages.SearchPage
import com.metrolist.innertube.pages.SearchResult
import com.metrolist.innertube.pages.SearchSuggestionPage
import com.metrolist.innertube.pages.SearchSummary
import com.metrolist.innertube.pages.SearchSummaryPage
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.net.Proxy
import kotlin.random.Random
import timber.log.Timber

/**
 * Parse useful data with [InnerTube] sending requests.
 * Modified from [ViMusic](https://github.com/vfsfitvnm/ViMusic)
 */
object YouTube {
    private val innerTube = InnerTube()

    var locale: YouTubeLocale
        get() = innerTube.locale
        set(value) {
            innerTube.locale = value
        }
    var visitorData: String?
        get() = innerTube.visitorData
        set(value) {
            innerTube.visitorData = value
        }
    var dataSyncId: String?
        get() = innerTube.dataSyncId
        set(value) {
            innerTube.dataSyncId = value
        }
    var cookie: String?
        get() = innerTube.cookie
        set(value) {
            innerTube.cookie = value
        }
    var proxy: Proxy?
        get() = innerTube.proxy
        set(value) {
            innerTube.proxy = value
        }

    var proxyAuth: String?
        get() = innerTube.proxyAuth
        set(value) {
            innerTube.proxyAuth = value
        }
    var useLoginForBrowse: Boolean
        get() = innerTube.useLoginForBrowse
        set(value) {
            innerTube.useLoginForBrowse = value
        }

    suspend fun searchSuggestions(query: String): Result<SearchSuggestions> = runCatching {
        val response = innerTube.getSearchSuggestions(WEB_REMIX, query).body<GetSearchSuggestionsResponse>()
        SearchSuggestions(
            queries = response.contents?.getOrNull(0)?.searchSuggestionsSectionRenderer?.contents?.mapNotNull { content ->
                content.searchSuggestionRenderer?.suggestion?.runs?.joinToString(separator = "") { it.text }
            }.orEmpty(),
            recommendedItems = response.contents?.getOrNull(1)?.searchSuggestionsSectionRenderer?.contents?.mapNotNull {
                it.musicResponsiveListItemRenderer?.let { renderer ->
                    SearchSuggestionPage.fromMusicResponsiveListItemRenderer(renderer)
                }
            }.orEmpty()
        )
    }

    suspend fun searchSummary(query: String): Result<SearchSummaryPage> = runCatching {
        val response = innerTube.search(WEB_REMIX, query).body<SearchResponse>()
        val allSummaries = mutableListOf<SearchSummary>()

        response.contents?.tabbedSearchResultsRenderer?.tabs?.firstOrNull()
            ?.tabRenderer?.content?.sectionListRenderer?.contents?.forEach { section ->
                if (section.musicCardShelfRenderer != null) {
                    // Top result card - keep as single section
                    val items = listOfNotNull(SearchSummaryPage.fromMusicCardShelfRenderer(section.musicCardShelfRenderer))
                        .plus(
                            section.musicCardShelfRenderer.contents
                                ?.mapNotNull { it.musicResponsiveListItemRenderer }
                                ?.mapNotNull(SearchSummaryPage.Companion::fromMusicResponsiveListItemRenderer)
                                .orEmpty()
                        )
                        .distinctBy { it.id }

                    if (items.isNotEmpty()) {
                        allSummaries.add(SearchSummary(
                            title = section.musicCardShelfRenderer.header?.musicCardShelfHeaderBasicRenderer?.title?.runs?.firstOrNull()?.text
                                ?: YouTubeConstants.DEFAULT_TOP_RESULT,
                            items = items
                        ))
                    }
                } else if (section.musicShelfRenderer != null) {
                    val items = section.musicShelfRenderer.contents?.getItems()
                        ?.mapNotNull { SearchSummaryPage.fromMusicResponsiveListItemRenderer(it) }
                        ?.distinctBy { it.id }
                        ?: emptyList()

                    if (items.isEmpty()) return@forEach

                    val apiTitle = section.musicShelfRenderer.title?.runs?.firstOrNull()?.text

                    if (apiTitle != null) {
                        // API provided a title, use single section
                        allSummaries.add(SearchSummary(title = apiTitle, items = items))
                    } else {
                        // No title - group items by type into separate sections
                        val grouped = items.groupBy { item ->
                            when (item) {
                                is EpisodeItem -> "Episodes"
                                is PodcastItem -> "Podcasts"
                                is AlbumItem -> "Albums"
                                is ArtistItem -> "Artists"
                                is PlaylistItem -> "Playlists"
                                is SongItem -> when {
                                    item.isEpisode -> "Episodes"
                                    item.isVideoSong -> "Videos"
                                    else -> "Songs"
                                }
                                else -> YouTubeConstants.DEFAULT_OTHER_RESULTS
                            }
                        }

                        // Add each group as a separate section in a logical order
                        val sectionOrder = listOf("Songs", "Videos", "Albums", "Artists", "Playlists", "Podcasts", "Episodes", YouTubeConstants.DEFAULT_OTHER_RESULTS)
                        sectionOrder.forEach { sectionName ->
                            grouped[sectionName]?.let { groupItems ->
                                if (groupItems.isNotEmpty()) {
                                    allSummaries.add(SearchSummary(title = sectionName, items = groupItems))
                                }
                            }
                        }
                    }
                }
            }

        // Merge sections with the same title
        val mergedSummaries = allSummaries
            .groupBy { it.title }
            .map { (title, sections) ->
                SearchSummary(
                    title = title,
                    items = sections.flatMap { it.items }.distinctBy { it.id }
                )
            }
            // Reorder to maintain logical order
            .sortedBy { summary ->
                when (summary.title) {
                    YouTubeConstants.DEFAULT_TOP_RESULT -> 0
                    "Songs" -> 1
                    "Videos" -> 2
                    "Albums" -> 3
                    "Artists" -> 4
                    "Playlists" -> 5
                    "Podcasts" -> 6
                    "Episodes" -> 7
                    else -> 8
                }
            }

        SearchSummaryPage(summaries = mergedSummaries)
    }

    suspend fun search(query: String, filter: SearchFilter): Result<SearchResult> = runCatching {
        val response = innerTube.search(WEB_REMIX, query, filter.value).body<SearchResponse>()
        SearchResult(
            items = response.contents?.tabbedSearchResultsRenderer?.tabs?.firstOrNull()
                ?.tabRenderer?.content?.sectionListRenderer?.contents?.lastOrNull()
                ?.musicShelfRenderer?.contents?.getItems()?.mapNotNull {
                    SearchPage.toYTItem(it)
                }.orEmpty(),
            continuation = response.contents?.tabbedSearchResultsRenderer?.tabs?.firstOrNull()
                ?.tabRenderer?.content?.sectionListRenderer?.contents?.lastOrNull()
                ?.musicShelfRenderer?.continuations?.getContinuation()
        )
    }

    suspend fun searchContinuation(continuation: String): Result<SearchResult> = runCatching {
        val response = innerTube.search(WEB_REMIX, continuation = continuation).body<SearchResponse>()
        val items = response.continuationContents?.musicShelfContinuation?.contents
            ?.mapNotNull {
                SearchPage.toYTItem(it.musicResponsiveListItemRenderer)
            } ?: emptyList()
        SearchResult(
            items = items,
            continuation = if (items.isEmpty()) null else response.continuationContents?.musicShelfContinuation?.continuations?.getContinuation()
        )
    }

    suspend fun album(browseId: String, withSongs: Boolean = true): Result<AlbumPage> = runCatching {
        val response = innerTube.browse(WEB_REMIX, browseId).body<BrowseResponse>()
        if (browseId.contains("FEmusic_library_privately_owned_release_detail")) {
            val playlistId =
                response.header?.musicDetailHeaderRenderer?.menu?.menuRenderer?.topLevelButtons?.firstOrNull()?.buttonRenderer?.navigationEndpoint?.watchPlaylistEndpoint?.playlistId!!
            val albumItem = AlbumItem(
                browseId = browseId,
                playlistId = playlistId,
                title = response.header.musicDetailHeaderRenderer.title.runs?.firstOrNull()?.text!!,
                artists = response.header.musicDetailHeaderRenderer.subtitle.runs?.filter { it.navigationEndpoint != null }?.map {
                    Artist(
                        name = it.text,
                        id = it.navigationEndpoint?.browseEndpoint?.browseId
                    )
                },
                year = response.header.musicDetailHeaderRenderer.subtitle.runs?.lastOrNull()?.text?.toIntOrNull(),
                thumbnail = response.header.musicDetailHeaderRenderer.thumbnail.croppedSquareThumbnailRenderer?.thumbnail?.thumbnails?.lastOrNull()!!.url,
                explicit = false, // TODO: Extract explicit badge for albums from YouTube response
            )
            return@runCatching AlbumPage(
                album = albumItem,
                songs = response.contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()?.tabRenderer?.content?.sectionListRenderer?.contents?.firstOrNull()?.musicShelfRenderer?.contents?.getItems()?.mapNotNull {
                    AlbumPage.getSong(it, albumItem)
                }!!.toMutableList(),
                otherVersions = emptyList()
            )
        } else {
            val playlistId =
                response.microformat?.microformatDataRenderer?.urlCanonical?.substringAfterLast('=')!!
            val albumItem = AlbumItem(
                browseId = browseId,
                playlistId = playlistId,
                title = response.contents?.twoColumnBrowseResultsRenderer?.tabs?.firstOrNull()?.tabRenderer?.content?.sectionListRenderer?.contents?.firstOrNull()?.musicResponsiveHeaderRenderer?.title?.runs?.firstOrNull()?.text!!,
                artists = response.contents.twoColumnBrowseResultsRenderer.tabs.firstOrNull()?.tabRenderer?.content?.sectionListRenderer?.contents?.firstOrNull()?.musicResponsiveHeaderRenderer?.straplineTextOne?.runs?.oddElements()
                    ?.map {
                        Artist(
                            name = it.text,
                            id = it.navigationEndpoint?.browseEndpoint?.browseId
                        )
                    }!!,
                year = response.contents.twoColumnBrowseResultsRenderer.tabs.firstOrNull()?.tabRenderer?.content?.sectionListRenderer?.contents?.firstOrNull()?.musicResponsiveHeaderRenderer?.subtitle?.runs?.lastOrNull()?.text?.toIntOrNull(),
                thumbnail = response.contents.twoColumnBrowseResultsRenderer.tabs.firstOrNull()?.tabRenderer?.content?.sectionListRenderer?.contents?.firstOrNull()?.musicResponsiveHeaderRenderer?.thumbnail?.musicThumbnailRenderer?.thumbnail?.thumbnails?.lastOrNull()?.url!!,
                explicit = false, // TODO: Extract explicit badge for albums from YouTube response
            )
            return@runCatching AlbumPage(
                album = albumItem,
                songs = if (withSongs) albumSongs(
                    playlistId, albumItem
                ).getOrThrow() else emptyList(),
                otherVersions = response.contents.twoColumnBrowseResultsRenderer.secondaryContents?.sectionListRenderer?.contents?.getOrNull(
                    1
                )?.musicCarouselShelfRenderer?.contents
                    ?.mapNotNull { it.musicTwoRowItemRenderer }
                    ?.mapNotNull(NewReleaseAlbumPage::fromMusicTwoRowItemRenderer)
                    .orEmpty()
            )
        }
    }

    suspend fun albumSongs(playlistId: String, album: AlbumItem? = null): Result<List<SongItem>> = runCatching {
        var response = innerTube.browse(WEB_REMIX, "VL$playlistId").body<BrowseResponse>()
        val songs = response.contents?.twoColumnBrowseResultsRenderer
            ?.secondaryContents?.sectionListRenderer
            ?.contents?.firstOrNull()
            ?.musicPlaylistShelfRenderer?.contents?.getItems()
            ?.mapNotNull {
                AlbumPage.getSong(it, album)
            }!!
            .toMutableList()
        var continuation = response.contents.twoColumnBrowseResultsRenderer.secondaryContents.sectionListRenderer
            .contents.firstOrNull()?.musicPlaylistShelfRenderer?.contents?.getContinuation()
        val seenContinuations = mutableSetOf<String>()
        var requestCount = 0
        val maxRequests = 50 // Prevent excessive API calls
        
        while (continuation != null && requestCount < maxRequests) {
            // Prevent infinite loops by tracking seen continuations
            if (continuation in seenContinuations) {
                break
            }
            seenContinuations.add(continuation)
            requestCount++
            
            response = innerTube.browse(
                client = WEB_REMIX,
                continuation = continuation,
            ).body<BrowseResponse>()
            songs += response.onResponseReceivedActions?.firstOrNull()?.appendContinuationItemsAction?.continuationItems?.getItems()?.mapNotNull {
                AlbumPage.getSong(it, album)
            }.orEmpty()
            continuation = response.continuationContents?.musicPlaylistShelfContinuation?.continuations?.getContinuation()
        }
        songs
    }

    suspend fun artist(browseId: String): Result<ArtistPage> = runCatching {
        val response = innerTube.browse(WEB_REMIX, browseId).body<BrowseResponse>()

        fun mapRuns(runs: List<Run>?): List<Run>? = runs?.map { run ->
            Run(
                text = run.text,
                navigationEndpoint = run.navigationEndpoint
            )
        }

        val descriptionRuns = response.contents?.sectionListRenderer?.contents
            ?.firstOrNull { it.musicDescriptionShelfRenderer != null }
            ?.musicDescriptionShelfRenderer?.description?.runs
            ?.let(::mapRuns)
            ?: response.header?.musicImmersiveHeaderRenderer?.description?.runs?.let(::mapRuns)

        ArtistPage(
            artist = ArtistItem(
                id = browseId,
                title = response.header?.musicImmersiveHeaderRenderer?.title?.runs?.firstOrNull()?.text
                    ?: response.header?.musicVisualHeaderRenderer?.title?.runs?.firstOrNull()?.text
                    ?: response.header?.musicHeaderRenderer?.title?.runs?.firstOrNull()?.text!!,
                thumbnail = response.header?.musicImmersiveHeaderRenderer?.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl()
                    ?: response.header?.musicVisualHeaderRenderer?.foregroundThumbnail?.musicThumbnailRenderer?.getThumbnailUrl()
                    ?: response.header?.musicDetailHeaderRenderer?.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl(),
                channelId = response.header?.musicImmersiveHeaderRenderer?.subscriptionButton?.subscribeButtonRenderer?.channelId,
                playEndpoint = response.contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()
                    ?.tabRenderer?.content?.sectionListRenderer?.contents?.firstOrNull()?.musicShelfRenderer
                    ?.contents?.firstOrNull()?.musicResponsiveListItemRenderer?.overlay?.musicItemThumbnailOverlayRenderer
                    ?.content?.musicPlayButtonRenderer?.playNavigationEndpoint?.watchEndpoint,
                shuffleEndpoint = response.header?.musicImmersiveHeaderRenderer?.playButton?.buttonRenderer?.navigationEndpoint?.watchEndpoint
                    ?: response.contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()?.tabRenderer?.content?.sectionListRenderer
                        ?.contents?.firstOrNull()?.musicShelfRenderer?.contents?.firstOrNull()?.musicResponsiveListItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint,
                radioEndpoint = response.header?.musicImmersiveHeaderRenderer?.startRadioButton?.buttonRenderer?.navigationEndpoint?.watchEndpoint
            ),
            sections = response.contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()
                ?.tabRenderer?.content?.sectionListRenderer?.contents
                ?.mapNotNull(ArtistPage::fromSectionListRendererContent)!!,
            description = descriptionRuns?.joinToString(separator = "") { it.text },
                subscriberCountText = response.header?.musicImmersiveHeaderRenderer?.subscriptionButton2
                    ?.subscribeButtonRenderer?.subscriberCountWithSubscribeText?.runs?.firstOrNull()?.text
                    ?: response.header?.musicImmersiveHeaderRenderer?.subscriptionButton?.subscribeButtonRenderer
                        ?.longSubscriberCountText?.runs?.firstOrNull()?.text
                    ?: response.header?.musicImmersiveHeaderRenderer?.subscriptionButton?.subscribeButtonRenderer
                        ?.shortSubscriberCountText?.runs?.firstOrNull()?.text,
            monthlyListenerCount = response.header?.musicImmersiveHeaderRenderer?.monthlyListenerCount?.runs?.firstOrNull()?.text,
            descriptionRuns = descriptionRuns
        )
    }

    suspend fun artistItems(endpoint: BrowseEndpoint): Result<ArtistItemsPage> = runCatching {
        val response = innerTube.browse(WEB_REMIX, endpoint.browseId, endpoint.params).body<BrowseResponse>()
        val sectionContent = response.contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()
            ?.tabRenderer?.content?.sectionListRenderer?.contents?.firstOrNull()
        
        val gridRenderer = sectionContent?.gridRenderer
        val musicCarouselShelfRenderer = sectionContent?.musicCarouselShelfRenderer
        val musicPlaylistShelfRenderer = sectionContent?.musicPlaylistShelfRenderer
        val musicShelfRenderer = sectionContent?.musicShelfRenderer
        
        when {
            gridRenderer != null -> {
                ArtistItemsPage(
                    title = gridRenderer.header?.gridHeaderRenderer?.title?.runs?.firstOrNull()?.text.orEmpty(),
                    items = gridRenderer.items.mapNotNull {
                        it.musicTwoRowItemRenderer?.let { renderer ->
                            ArtistItemsPage.fromMusicTwoRowItemRenderer(renderer)
                        }
                    },
                    continuation = gridRenderer.continuations?.getContinuation()
                )
            }
            musicCarouselShelfRenderer != null -> {
                ArtistItemsPage(
                    title = musicCarouselShelfRenderer.header?.musicCarouselShelfBasicHeaderRenderer?.title?.runs?.firstOrNull()?.text.orEmpty(),
                    items = musicCarouselShelfRenderer.contents.mapNotNull { content ->
                        content.musicTwoRowItemRenderer?.let { renderer ->
                            ArtistItemsPage.fromMusicTwoRowItemRenderer(renderer)
                        } ?: content.musicResponsiveListItemRenderer?.let { renderer ->
                            ArtistItemsPage.fromMusicResponsiveListItemRenderer(renderer)
                        }
                    },
                    continuation = null
                )
            }
            musicShelfRenderer != null -> {
                ArtistItemsPage(
                    title = musicShelfRenderer.title?.runs?.firstOrNull()?.text 
                        ?: response.header?.musicHeaderRenderer?.title?.runs?.firstOrNull()?.text 
                        ?: "",
                    items = musicShelfRenderer.contents?.getItems()?.mapNotNull {
                        ArtistItemsPage.fromMusicResponsiveListItemRenderer(it)
                    } ?: emptyList(),
                    continuation = musicShelfRenderer.continuations?.getContinuation()
                )
            }
            else -> {
                ArtistItemsPage(
                    title = response.header?.musicHeaderRenderer?.title?.runs?.firstOrNull()?.text ?: "",
                    items = musicPlaylistShelfRenderer?.contents?.getItems()?.mapNotNull {
                        ArtistItemsPage.fromMusicResponsiveListItemRenderer(it)
                    } ?: emptyList(),
                    continuation = musicPlaylistShelfRenderer?.contents?.getContinuation()
                )
            }
        }
    }

    suspend fun artistItemsContinuation(continuation: String): Result<ArtistItemsContinuationPage> = runCatching {
        val response = innerTube.browse(WEB_REMIX, continuation = continuation).body<BrowseResponse>()

        when {
            response.continuationContents?.gridContinuation != null -> {
                val gridContinuation = response.continuationContents.gridContinuation
                val items = gridContinuation.items.mapNotNull {
                    it.musicTwoRowItemRenderer?.let { renderer ->
                        ArtistItemsPage.fromMusicTwoRowItemRenderer(renderer)
                    }
                }
                ArtistItemsContinuationPage(
                    items = items,
                    continuation = if (items.isEmpty()) null else gridContinuation.continuations?.getContinuation()
                )
            }

            response.continuationContents?.musicPlaylistShelfContinuation != null -> {
                val musicPlaylistShelfContinuation = response.continuationContents.musicPlaylistShelfContinuation
                val items = musicPlaylistShelfContinuation.contents.getItems().mapNotNull {
                    ArtistItemsPage.fromMusicResponsiveListItemRenderer(it)
                }
                ArtistItemsContinuationPage(
                    items = items,
                    continuation = if (items.isEmpty()) null else musicPlaylistShelfContinuation.continuations?.getContinuation()
                )
            }

            else -> {
                val continuationItems = response.onResponseReceivedActions?.firstOrNull()
                    ?.appendContinuationItemsAction?.continuationItems
                val items = continuationItems?.getItems()?.mapNotNull {
                    ArtistItemsPage.fromMusicResponsiveListItemRenderer(it)
                } ?: emptyList()
                ArtistItemsContinuationPage(
                    items = items,
                    continuation = if (items.isEmpty()) null else continuationItems?.getContinuation()
                )
            }
        }
    }

    suspend fun playlist(playlistId: String): Result<PlaylistPage> = runCatching {
        val response = innerTube.browse(
            client = WEB_REMIX,
            browseId = "VL$playlistId",
            setLogin = true
        ).body<BrowseResponse>()
        val base = response.contents?.twoColumnBrowseResultsRenderer?.tabs?.firstOrNull()?.tabRenderer?.content?.sectionListRenderer?.contents?.firstOrNull()
        val header = base?.musicResponsiveHeaderRenderer ?: base?.musicEditablePlaylistDetailHeaderRenderer?.header?.musicResponsiveHeaderRenderer

        val editable = base?.musicEditablePlaylistDetailHeaderRenderer != null

        PlaylistPage(
            playlist = PlaylistItem(
                id = playlistId,
                title = header?.title?.runs?.firstOrNull()?.text!!,
                author = header.straplineTextOne?.runs?.firstOrNull()?.let {
                    Artist(
                        name = it.text,
                        id = it.navigationEndpoint?.browseEndpoint?.browseId
                    )
                },
                songCountText = header.secondSubtitle?.runs?.firstOrNull()?.text,
                thumbnail = header.thumbnail?.musicThumbnailRenderer?.thumbnail?.thumbnails?.lastOrNull()?.url!!,
                playEndpoint = null,
                shuffleEndpoint = header.buttons.lastOrNull()?.menuRenderer?.items?.firstOrNull()?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint!!,
                radioEndpoint = header.buttons.getOrNull(2)?.menuRenderer?.items?.find {
                    it.menuNavigationItemRenderer?.icon?.iconType == "MIX"
                }?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint,
                isEditable = editable
            ),
            songs = response.contents?.twoColumnBrowseResultsRenderer?.secondaryContents?.sectionListRenderer
                ?.contents?.firstOrNull()?.musicPlaylistShelfRenderer?.contents?.getItems()?.mapNotNull {
                    PlaylistPage.fromMusicResponsiveListItemRenderer(it)
                } ?: emptyList(),
            songsContinuation = response.contents?.twoColumnBrowseResultsRenderer?.secondaryContents?.sectionListRenderer
                ?.contents?.firstOrNull()?.musicPlaylistShelfRenderer?.contents?.getContinuation()
                ?: response.contents?.twoColumnBrowseResultsRenderer?.secondaryContents?.sectionListRenderer
                    ?.contents?.firstOrNull()?.musicPlaylistShelfRenderer?.continuations?.getContinuation(),
            continuation = response.contents?.twoColumnBrowseResultsRenderer?.secondaryContents?.sectionListRenderer
                ?.continuations?.getContinuation()
        )
    }

    suspend fun playlistContinuation(continuation: String): Result<PlaylistContinuationPage> = runCatching {
        val response = innerTube.browse(
            client = WEB_REMIX,
            continuation = continuation,
            setLogin = true
        ).body<BrowseResponse>()

        val mainContents: List<MusicShelfRenderer.Content> = response.continuationContents?.sectionListContinuation?.contents
            ?.mapNotNull { content: SectionListRenderer.Content -> content.musicPlaylistShelfRenderer?.contents }
            ?.flatten()
            ?: emptyList()

        val shelfContents: List<MusicShelfRenderer.Content> =
            response.continuationContents?.musicPlaylistShelfContinuation?.contents ?: emptyList()

        val appendedContents: List<MusicShelfRenderer.Content> = response.onResponseReceivedActions
            ?.firstOrNull()
            ?.appendContinuationItemsAction
            ?.continuationItems
            .orEmpty()

        val allContents = mainContents + shelfContents + appendedContents

        val songs = allContents
            .mapNotNull { content: MusicShelfRenderer.Content -> content.musicResponsiveListItemRenderer }
            .mapNotNull { renderer -> PlaylistPage.fromMusicResponsiveListItemRenderer(renderer) }

        val nextContinuation = if (songs.isEmpty()) null else {
            response.continuationContents
                ?.sectionListContinuation
                ?.continuations
                ?.getContinuation()
                ?: response.continuationContents
                    ?.musicPlaylistShelfContinuation
                    ?.continuations
                    ?.getContinuation()
                ?: response.continuationContents
                    ?.musicShelfContinuation
                    ?.continuations
                    ?.getContinuation()
                ?: response.onResponseReceivedActions
                    ?.firstOrNull()
                    ?.appendContinuationItemsAction
                    ?.continuationItems
                    ?.getContinuation()
        }

        PlaylistContinuationPage(
            songs = songs,
            continuation = nextContinuation
        )
    }

    suspend fun podcast(podcastId: String): Result<PodcastPage> = podcastWithDebug(podcastId) { }

    suspend fun podcastWithDebug(podcastId: String, log: (String) -> Unit): Result<PodcastPage> = runCatching {
        Timber.d("Fetching podcast with ID: $podcastId")
        val response = innerTube.browse(
            client = WEB_REMIX,
            browseId = podcastId,
            setLogin = true
        ).body<BrowseResponse>()

        Timber.d("Response received, twoColumnBrowseResultsRenderer: ${response.contents?.twoColumnBrowseResultsRenderer != null}")
        Timber.d("singleColumnBrowseResultsRenderer: ${response.contents?.singleColumnBrowseResultsRenderer != null}")

        // Try twoColumn first (standard layout)
        var header = response.contents?.twoColumnBrowseResultsRenderer?.tabs?.firstOrNull()
            ?.tabRenderer?.content?.sectionListRenderer?.contents?.firstOrNull()
            ?.musicResponsiveHeaderRenderer

        // Fallback to singleColumn layout
        if (header == null) {
            header = response.contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()
                ?.tabRenderer?.content?.sectionListRenderer?.contents?.firstOrNull()
                ?.musicResponsiveHeaderRenderer
            Timber.d("Using singleColumn layout, header found: ${header != null}")
        }

        Timber.d("Header title: ${header?.title?.runs?.firstOrNull()?.text}")

        // Debug: Log button structure
        header?.buttons?.forEachIndexed { i, button ->
            Timber.d("[PODCAST] Button[$i]: menuRenderer=${button.menuRenderer != null}, toggleButtonRenderer=${button.toggleButtonRenderer != null}, playButtonRenderer=${button.musicPlayButtonRenderer != null}")
            button.menuRenderer?.items?.forEachIndexed { j, item ->
                Timber.d("[PODCAST] Button[$i].menuItems[$j]: toggle=${item.toggleMenuServiceItemRenderer?.defaultIcon?.iconType}, nav=${item.menuNavigationItemRenderer?.icon?.iconType}")
                // Check for SUBSCRIBE button (like artists have)
                if (item.toggleMenuServiceItemRenderer?.defaultIcon?.iconType == "SUBSCRIBE") {
                    val channelIds = item.toggleMenuServiceItemRenderer?.defaultServiceEndpoint?.subscribeEndpoint?.channelIds
                    Timber.d("[PODCAST] Found SUBSCRIBE button! channelIds=$channelIds")
                }
            }
            button.toggleButtonRenderer?.let { toggle ->
                Timber.d("[PODCAST] Button[$i].toggleButtonRenderer: defaultIcon=${toggle.defaultIcon?.iconType}, defaultToken=${toggle.defaultServiceEndpoint?.feedbackEndpoint?.feedbackToken?.take(30)}, subscribeChannelIds=${toggle.defaultServiceEndpoint?.subscribeEndpoint?.channelIds}")
            }
        }

        // Extract channelId for subscription (like artists)
        val channelId = header?.buttons?.flatMap { button ->
            button.menuRenderer?.items ?: emptyList()
        }?.find {
            it.toggleMenuServiceItemRenderer?.defaultIcon?.iconType == "SUBSCRIBE"
        }?.toggleMenuServiceItemRenderer?.defaultServiceEndpoint?.subscribeEndpoint?.channelIds?.firstOrNull()
        Timber.d("[PODCAST] Extracted channelId for subscription: $channelId")

        // Extract library tokens from the header's menu buttons OR toggle buttons
        var libraryTokens = header?.buttons?.flatMap { button ->
            button.menuRenderer?.items ?: emptyList()
        }?.let { menuItems ->
            PageHelper.extractLibraryTokensFromMenuItems(menuItems)
        }

        // Also check for standalone toggle buttons (used by some podcasts)
        if (libraryTokens?.addToken == null && libraryTokens?.removeToken == null) {
            header?.buttons?.forEach { button ->
                button.toggleButtonRenderer?.let { toggle ->
                    val iconType = toggle.defaultIcon?.iconType
                    if (iconType != null && PageHelper.isLibraryIcon(iconType)) {
                        val defaultToken = toggle.defaultServiceEndpoint?.feedbackEndpoint?.feedbackToken
                        val toggledToken = toggle.toggledServiceEndpoint?.feedbackEndpoint?.feedbackToken
                        libraryTokens = if (PageHelper.isAddLibraryIcon(iconType)) {
                            // BOOKMARK_BORDER: default=add, toggled=remove
                            PageHelper.LibraryFeedbackTokens(defaultToken, toggledToken)
                        } else {
                            // BOOKMARK: default=remove, toggled=add
                            PageHelper.LibraryFeedbackTokens(toggledToken, defaultToken)
                        }
                        Timber.d("[PODCAST] Found toggle button with library tokens - add: ${libraryTokens?.addToken != null}, remove: ${libraryTokens?.removeToken != null}")
                    }
                }
            }
        }
        Timber.d("[PODCAST] Library tokens - add: ${libraryTokens?.addToken != null}, remove: ${libraryTokens?.removeToken != null}")

        val podcastItem = PodcastItem(
            id = podcastId,
            title = header?.title?.runs?.firstOrNull()?.text ?: "",
            author = header?.straplineTextOne?.runs?.firstOrNull()?.let {
                Artist(
                    name = it.text,
                    id = it.navigationEndpoint?.browseEndpoint?.browseId
                )
            },
            episodeCountText = header?.secondSubtitle?.runs?.firstOrNull()?.text,
            thumbnail = header?.thumbnail?.musicThumbnailRenderer?.thumbnail?.thumbnails?.lastOrNull()?.url,
            playEndpoint = header?.buttons?.find {
                it.menuRenderer?.items?.firstOrNull()?.menuNavigationItemRenderer?.icon?.iconType == "PLAY_ARROW"
            }?.menuRenderer?.items?.firstOrNull()?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint,
            shuffleEndpoint = header?.buttons?.find {
                it.menuRenderer?.items?.any { item -> item.menuNavigationItemRenderer?.icon?.iconType == "MUSIC_SHUFFLE" } == true
            }?.menuRenderer?.items?.find { it.menuNavigationItemRenderer?.icon?.iconType == "MUSIC_SHUFFLE" }
                ?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint,
            libraryAddToken = libraryTokens?.addToken,
            libraryRemoveToken = libraryTokens?.removeToken,
            channelId = channelId,
        )

        // Try twoColumn for episodes
        val secondaryContents = response.contents?.twoColumnBrowseResultsRenderer?.secondaryContents
        Timber.d("secondaryContents null: ${secondaryContents == null}")
        Timber.d("secondaryContents.sectionListRenderer null: ${secondaryContents?.sectionListRenderer == null}")
        Timber.d("sectionListRenderer.contents size: ${secondaryContents?.sectionListRenderer?.contents?.size ?: 0}")

        secondaryContents?.sectionListRenderer?.contents?.forEachIndexed { index, content ->
            Timber.d("Content[$index]: musicShelfRenderer=${content.musicShelfRenderer != null}, musicPlaylistShelfRenderer=${content.musicPlaylistShelfRenderer != null}, gridRenderer=${content.gridRenderer != null}")
            content.musicShelfRenderer?.let { shelf ->
                Timber.d("musicShelfRenderer.contents size: ${shelf.contents?.size ?: 0}")
            }
            content.musicPlaylistShelfRenderer?.let { shelf ->
                Timber.d("musicPlaylistShelfRenderer.contents size: ${shelf.contents.size}")
            }
        }

        var episodeContents = secondaryContents?.sectionListRenderer
            ?.contents?.firstOrNull()?.musicShelfRenderer?.contents

        // Try musicPlaylistShelfRenderer
        if (episodeContents == null) {
            episodeContents = secondaryContents?.sectionListRenderer
                ?.contents?.firstOrNull()?.musicPlaylistShelfRenderer?.contents
            Timber.d("Trying musicPlaylistShelfRenderer: ${episodeContents?.size ?: 0}")
        }

        // Fallback to singleColumn
        if (episodeContents == null) {
            episodeContents = response.contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()
                ?.tabRenderer?.content?.sectionListRenderer?.contents
                ?.find { it.musicShelfRenderer != null }?.musicShelfRenderer?.contents
            Timber.d("Using singleColumn for episodes, found: ${episodeContents?.size ?: 0}")
        }

        Timber.d("Episode contents count: ${episodeContents?.size ?: 0}")

        // Get episodes from musicMultiRowListItemRenderer (used for podcasts)
        val multiRowItems = episodeContents?.mapNotNull { it.musicMultiRowListItemRenderer } ?: emptyList()
        Timber.d("multiRowItems count: ${multiRowItems.size}")

        multiRowItems.take(2).forEachIndexed { idx, renderer ->
            Timber.d("Episode[$idx] title: ${renderer.title?.runs?.firstOrNull()?.text}")
            Timber.d("Episode[$idx] subtitle: ${renderer.subtitle?.runs?.map { it.text }}")
            Timber.d("Episode[$idx] videoId: ${renderer.onTap?.watchEndpoint?.videoId}")
            Timber.d("Episode[$idx] thumbnail: ${renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl()}")
        }

        val episodes = multiRowItems.mapNotNull { renderer ->
            PodcastPage.fromMusicMultiRowListItemRenderer(renderer, podcastItem)
        }

        Timber.d("Parsed episodes: ${episodes.size}")

        PodcastPage(
            podcast = podcastItem,
            episodes = episodes,
            continuation = response.contents?.twoColumnBrowseResultsRenderer?.secondaryContents?.sectionListRenderer
                ?.contents?.firstOrNull()?.musicShelfRenderer?.continuations?.getContinuation()
                ?: response.contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()
                    ?.tabRenderer?.content?.sectionListRenderer?.contents
                    ?.find { it.musicShelfRenderer != null }?.musicShelfRenderer?.continuations?.getContinuation()
        )
    }

    suspend fun home(continuation: String? = null, params: String? = null): Result<HomePage> = runCatching {
        Timber.d("home() called with continuation=$continuation, params=$params")
        if (continuation != null) {
            return@runCatching homeContinuation(continuation).getOrThrow()
        }

        val response = innerTube.browse(WEB_REMIX, browseId = "FEmusic_home", params = params).body<BrowseResponse>()
        Timber.d("home() response received")
        val continuation = response.contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()
            ?.tabRenderer?.content?.sectionListRenderer?.continuations?.getContinuation()
        val sectionListRender = response.contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()
            ?.tabRenderer?.content?.sectionListRenderer
        Timber.d("home() sectionListRender contents size: ${sectionListRender?.contents?.size ?: 0}")
        val carousels = sectionListRender?.contents?.mapNotNull { it.musicCarouselShelfRenderer } ?: emptyList()
        Timber.d("home() carousels count: ${carousels.size}")
        val sections = carousels.mapNotNull {
            HomePage.Section.fromMusicCarouselShelfRenderer(it)
        }.toMutableList()
        Timber.d("home() sections parsed: ${sections.size}")
        val chips = sectionListRender?.header?.chipCloudRenderer?.chips?.mapNotNull { HomePage.Chip.fromChipCloudChipRenderer(it) }
        Timber.d("home() chips: ${chips?.size ?: 0}")
        HomePage(chips, sections, continuation)
    }

    private suspend fun homeContinuation(continuation: String): Result<HomePage> = runCatching {
        val response =
            innerTube.browse(WEB_REMIX, continuation = continuation).body<BrowseResponse>()
        val continuation =
            response.continuationContents?.sectionListContinuation?.continuations?.getContinuation()
        HomePage(
            null,
            response.continuationContents?.sectionListContinuation?.contents
            ?.mapNotNull { it.musicCarouselShelfRenderer }
            ?.mapNotNull {
                HomePage.Section.fromMusicCarouselShelfRenderer(it)
            }.orEmpty(), continuation
        )
    }

    suspend fun explore(): Result<ExplorePage> = runCatching {
        val response = innerTube.browse(WEB_REMIX, browseId = "FEmusic_explore").body<BrowseResponse>()
        ExplorePage(
            newReleaseAlbums = response.contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()?.tabRenderer?.content?.sectionListRenderer?.contents?.find {
                it.musicCarouselShelfRenderer?.header?.musicCarouselShelfBasicHeaderRenderer?.moreContentButton?.buttonRenderer?.navigationEndpoint?.browseEndpoint?.browseId == "FEmusic_new_releases_albums"
            }?.musicCarouselShelfRenderer?.contents
                ?.mapNotNull { it.musicTwoRowItemRenderer }
                ?.mapNotNull(NewReleaseAlbumPage::fromMusicTwoRowItemRenderer).orEmpty(),
            moodAndGenres = response.contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()?.tabRenderer?.content?.sectionListRenderer?.contents?.find {
                it.musicCarouselShelfRenderer?.header?.musicCarouselShelfBasicHeaderRenderer?.moreContentButton?.buttonRenderer?.navigationEndpoint?.browseEndpoint?.browseId == "FEmusic_moods_and_genres"
            }?.musicCarouselShelfRenderer?.contents
                ?.mapNotNull { it.musicNavigationButtonRenderer }
                ?.mapNotNull(MoodAndGenres.Companion::fromMusicNavigationButtonRenderer)
                .orEmpty()
        )
    }

    suspend fun newReleaseAlbums(): Result<List<AlbumItem>> = runCatching {
        val response = innerTube.browse(WEB_REMIX, browseId = "FEmusic_new_releases_albums").body<BrowseResponse>()
        response.contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()?.tabRenderer?.content?.sectionListRenderer?.contents?.firstOrNull()?.gridRenderer?.items
            ?.mapNotNull { it.musicTwoRowItemRenderer }
            ?.mapNotNull(NewReleaseAlbumPage::fromMusicTwoRowItemRenderer)
            .orEmpty()
    }

    suspend fun moodAndGenres(): Result<List<MoodAndGenres>> = runCatching {
        val response = innerTube.browse(WEB_REMIX, browseId = "FEmusic_moods_and_genres").body<BrowseResponse>()
        response.contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()?.tabRenderer?.content?.sectionListRenderer?.contents!!
            .mapNotNull(MoodAndGenres.Companion::fromSectionListRendererContent)
    }

    suspend fun browse(browseId: String, params: String?): Result<BrowseResult> = runCatching {
        // Use authentication for library endpoints
        val needsLogin = browseId.startsWith("FEmusic_library") || browseId == "VLSE" || browseId == "VLRDPN"
        val response = innerTube.browse(WEB_REMIX, browseId = browseId, params = params, setLogin = needsLogin).body<BrowseResponse>()
        val sectionContents = response.contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()?.tabRenderer?.content?.sectionListRenderer?.contents
        BrowseResult(
            title = response.header?.musicHeaderRenderer?.title?.runs?.firstOrNull()?.text,
            items = sectionContents?.mapNotNull { content ->
                when {
                    content.gridRenderer != null -> {
                        BrowseResult.Item(
                            title = content.gridRenderer.header?.gridHeaderRenderer?.title?.runs?.firstOrNull()?.text,
                            items = content.gridRenderer.items
                                .mapNotNull(GridRenderer.Item::musicTwoRowItemRenderer)
                                .mapNotNull { renderer ->
                                    // Try LibraryPage first (more lenient for library endpoints), fall back to RelatedPage
                                    LibraryPage.fromMusicTwoRowItemRenderer(renderer)
                                        ?: RelatedPage.fromMusicTwoRowItemRenderer(renderer)
                                }
                        )
                    }

                    content.musicCarouselShelfRenderer != null -> {
                        BrowseResult.Item(
                            title = content.musicCarouselShelfRenderer.header?.musicCarouselShelfBasicHeaderRenderer?.title?.runs?.firstOrNull()?.text,
                            items = content.musicCarouselShelfRenderer.contents
                                .mapNotNull(MusicCarouselShelfRenderer.Content::musicTwoRowItemRenderer)
                                .mapNotNull { renderer ->
                                    LibraryPage.fromMusicTwoRowItemRenderer(renderer)
                                        ?: RelatedPage.fromMusicTwoRowItemRenderer(renderer)
                                }
                        )
                    }

                    content.musicShelfRenderer != null -> {
                        BrowseResult.Item(
                            title = content.musicShelfRenderer.title?.runs?.firstOrNull()?.text,
                            items = content.musicShelfRenderer.contents
                                ?.mapNotNull(MusicShelfRenderer.Content::musicResponsiveListItemRenderer)
                                ?.mapNotNull(LibraryPage.Companion::fromMusicResponsiveListItemRenderer)
                                ?: emptyList()
                        )
                    }

                    content.musicPlaylistShelfRenderer != null -> {
                        BrowseResult.Item(
                            title = null, // MusicPlaylistShelfRenderer doesn't have a title
                            items = content.musicPlaylistShelfRenderer.contents.getItems()
                                .mapNotNull(LibraryPage.Companion::fromMusicResponsiveListItemRenderer)
                        )
                    }

                    else -> null
                }
            }.orEmpty()
        )
    }

    suspend fun library(browseId: String, tabIndex: Int = 0): Result<LibraryPage> {
        println("[UPLOAD_DEBUG] library() called with browseId=$browseId, tabIndex=$tabIndex")
        return runCatching {
            val response = innerTube.browse(
                client = WEB_REMIX,
                browseId = browseId,
                setLogin = true
            ).body<BrowseResponse>()

            val tabs = response.contents?.singleColumnBrowseResultsRenderer?.tabs
            println("[UPLOAD_DEBUG] tabs count: ${tabs?.size ?: 0}")

            // Debug: log the structure for uploaded songs browseId
            if (browseId == "FEmusic_library_privately_owned_tracks") {
                println("[UPLOAD_DEBUG] Raw response.contents: ${response.contents}")
                tabs?.forEachIndexed { idx, tab ->
                    println("[UPLOAD_DEBUG] Tab $idx: tabRenderer.content null? ${tab.tabRenderer.content == null}")
                    println("[UPLOAD_DEBUG] Tab $idx: sectionListRenderer null? ${tab.tabRenderer.content?.sectionListRenderer == null}")
                    println("[UPLOAD_DEBUG] Tab $idx: sectionListRenderer.contents size: ${tab.tabRenderer.content?.sectionListRenderer?.contents?.size ?: 0}")
                    tab.tabRenderer.content?.sectionListRenderer?.contents?.forEachIndexed { cIdx, content ->
                        println("[UPLOAD_DEBUG] Tab $idx Content $cIdx: gridRenderer=${content.gridRenderer != null}, musicShelfRenderer=${content.musicShelfRenderer != null}")
                    }
                }
            }

            val contents = if (tabs != null && tabs.size >= tabIndex) {
                tabs[tabIndex].tabRenderer.content?.sectionListRenderer?.contents?.firstOrNull()
            }
            else {
                println("[UPLOAD_DEBUG] No tabs or tabIndex out of range")
                null
            }

            println("[UPLOAD_DEBUG] contents null? ${contents == null}")
            println("[UPLOAD_DEBUG] gridRenderer null? ${contents?.gridRenderer == null}")
            println("[UPLOAD_DEBUG] musicShelfRenderer null? ${contents?.musicShelfRenderer == null}")

            when {
                contents?.gridRenderer != null -> {
                    val gridItems = contents.gridRenderer.items
                    println("[UPLOAD_DEBUG] gridRenderer items count: ${gridItems.size}")
                    val twoRowItems = gridItems.mapNotNull(GridRenderer.Item::musicTwoRowItemRenderer)
                    println("[UPLOAD_DEBUG] musicTwoRowItemRenderer count: ${twoRowItems.size}")
                    val parsedItems = twoRowItems.mapNotNull { LibraryPage.fromMusicTwoRowItemRenderer(it) }
                    println("[UPLOAD_DEBUG] Successfully parsed items: ${parsedItems.size}")
                    LibraryPage(
                        items = parsedItems,
                        continuation = contents.gridRenderer.continuations?.getContinuation()
                    )
                }

                else -> { // contents?.musicShelfRenderer != null
                    val shelfContents = contents?.musicShelfRenderer?.contents
                    println("[UPLOAD_DEBUG] musicShelfRenderer contents count: ${shelfContents?.size ?: 0}")
                    if (shelfContents == null) {
                        println("[UPLOAD_DEBUG] ERROR: musicShelfRenderer contents is null!")
                        throw IllegalStateException("No content found for browseId=$browseId")
                    }
                    val listItemRenderers = shelfContents.mapNotNull(MusicShelfRenderer.Content::musicResponsiveListItemRenderer)
                    println("[UPLOAD_DEBUG] musicResponsiveListItemRenderer count: ${listItemRenderers.size}")

                    listItemRenderers.forEachIndexed { index, renderer ->
                        println("[UPLOAD_DEBUG] Item $index: isSong=${renderer.isSong}, isArtist=${renderer.isArtist}, isAlbum=${renderer.isAlbum}, isPlaylist=${renderer.isPlaylist}")
                        println("[UPLOAD_DEBUG] Item $index: playlistItemData=${renderer.playlistItemData}")
                        println("[UPLOAD_DEBUG] Item $index: flexColumns count=${renderer.flexColumns.size}")
                        renderer.flexColumns.forEachIndexed { colIdx, col ->
                            println("[UPLOAD_DEBUG] Item $index flexColumn $colIdx: ${col.musicResponsiveListItemFlexColumnRenderer.text?.runs?.map { it.text }}")
                        }
                        println("[UPLOAD_DEBUG] Item $index: thumbnail=${renderer.thumbnail?.musicThumbnailRenderer?.thumbnail}")
                    }

                    val parsedItems = listItemRenderers.mapNotNull { renderer ->
                        val result = LibraryPage.fromMusicResponsiveListItemRenderer(renderer)
                        if (result == null) {
                            println("[UPLOAD_DEBUG] Failed to parse renderer: videoId=${renderer.playlistItemData?.videoId}")
                        }
                        result
                    }
                    println("[UPLOAD_DEBUG] Successfully parsed items: ${parsedItems.size}")
                    parsedItems.filterIsInstance<SongItem>().forEach { song ->
                        println("[UPLOAD_DEBUG] Parsed song: id=${song.id}, title=${song.title}, artists=${song.artists.map { it.name }}")
                    }
                    LibraryPage(
                        items = parsedItems,
                        continuation = contents.musicShelfRenderer.continuations?.getContinuation()
                    )
                }
            }
        }.onFailure { e ->
            println("[UPLOAD_DEBUG] library() EXCEPTION for browseId=$browseId: ${e::class.simpleName}: ${e.message}")
            e.printStackTrace()
        }
    }

    suspend fun libraryContinuation(continuation: String) = runCatching {
        val response = innerTube.browse(
            client = WEB_REMIX,
            continuation = continuation,
            setLogin = true
        ).body<BrowseResponse>()

        val contents = response.continuationContents

        when {
            contents?.gridContinuation != null -> {
                LibraryContinuationPage(
                    items = contents.gridContinuation.items
                        .mapNotNull (GridRenderer.Item::musicTwoRowItemRenderer)
                        .mapNotNull { LibraryPage.fromMusicTwoRowItemRenderer(it) },
                    continuation = contents.gridContinuation.continuations?.getContinuation()
                )
            }

            else -> { // contents?.musicShelfContinuation != null
                LibraryContinuationPage(
                    items = contents?.musicShelfContinuation?.contents!!
                        .mapNotNull (MusicShelfRenderer.Content::musicResponsiveListItemRenderer)
                        .mapNotNull { LibraryPage.fromMusicResponsiveListItemRenderer(it) },
                    continuation = contents.musicShelfContinuation.continuations?.getContinuation()
                )
            }
        }
    }

    suspend fun libraryRecentActivity(): Result<LibraryPage> = runCatching {
        val continuation = LibraryFilter.FILTER_RECENT_ACTIVITY.value

        val response = innerTube.browse(
            client = WEB_REMIX,
            continuation = continuation,
            setLogin = true
        ).body<BrowseResponse>()

        val gridItems = response.continuationContents?.sectionListContinuation?.contents?.firstOrNull()
            ?.gridRenderer?.items
        
        if (gridItems == null) {
            return@runCatching LibraryPage(
                items = emptyList(),
                continuation = null
            )
        }
        
        val items = gridItems.mapNotNull {
            it.musicTwoRowItemRenderer?.let { renderer ->
                LibraryPage.fromMusicTwoRowItemRenderer(renderer)
            }
        }.toMutableList()

        /*
         * We need to fetch the artist page when accessing the library because it allows to have
         * a proper playEndpoint, which is needed to correctly report the playing indicator in
         * the home page.
         *
         * Despite this, we need to use the old thumbnail because it's the proper format for a
         * square picture, which is what we need.
         */
        items.forEachIndexed { index, item ->
            if (item is ArtistItem) {
                artist(item.id).getOrNull()?.artist?.let { fetchedArtist ->
                    items[index] = fetchedArtist.copy(thumbnail = item.thumbnail)
                }
            }
        }

        LibraryPage(
            items = items,
            continuation = null
        )
    }

    suspend fun getChartsPage(continuation: String? = null): Result<ChartsPage> = runCatching {
        val response = innerTube.browse(
            client = WEB_REMIX,
            browseId = "FEmusic_charts",
            params = "ggMGCgQIgAQ%3D",
            continuation = continuation
        ).body<BrowseResponse>()

        val sections = mutableListOf<ChartsPage.ChartSection>()
    
        response.contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()
            ?.tabRenderer?.content?.sectionListRenderer?.contents?.forEach { content ->
            
                content.musicCarouselShelfRenderer?.let { renderer ->
                    val title = renderer.header?.musicCarouselShelfBasicHeaderRenderer?.title?.runs?.firstOrNull()?.text
                        ?: return@forEach
                
                    val items = renderer.contents.mapNotNull { item ->
                        when {
                            item.musicResponsiveListItemRenderer != null -> 
                                convertToChartItem(item.musicResponsiveListItemRenderer)
                            item.musicTwoRowItemRenderer != null -> 
                                convertMusicTwoRowItem(item.musicTwoRowItemRenderer)
                            else -> null
                        }
                    }.filterNotNull()
                
                    if (items.isNotEmpty()) {
                        sections.add(
                            ChartsPage.ChartSection(
                                title = title,
                                items = items,
                                chartType = determineChartType(title)
                            )
                        )
                    }
                }
            
                content.gridRenderer?.let { renderer ->
                    val title = renderer.header?.gridHeaderRenderer?.title?.runs?.firstOrNull()?.text
                        ?: return@let
                
                    val items = renderer.items.mapNotNull { item ->
                        item.musicTwoRowItemRenderer?.let { renderer ->
                            convertMusicTwoRowItem(renderer)
                        }
                    }.filterNotNull()
                
                    if (items.isNotEmpty()) {
                        sections.add(
                            ChartsPage.ChartSection(
                                title = title,
                                items = items,
                                chartType = ChartsPage.ChartType.NEW_RELEASES
                            )
                        )
                    }
                }
            }

        ChartsPage(
            sections = sections,
            continuation = response.continuationContents?.sectionListContinuation?.continuations?.getContinuation()
        )
    }

    private fun determineChartType(title: String): ChartsPage.ChartType {
        return when {
            title.contains("Trending", ignoreCase = true) -> ChartsPage.ChartType.TRENDING
            title.contains("Top", ignoreCase = true) -> ChartsPage.ChartType.TOP
            else -> ChartsPage.ChartType.GENRE
        }
    }

    private fun convertToChartItem(renderer: MusicResponsiveListItemRenderer): YTItem? {
        return try {
            when {
                renderer.flexColumns.size >= 3 && renderer.playlistItemData?.videoId != null -> {
                    val firstColumn = renderer.flexColumns.getOrNull(0)
                        ?.musicResponsiveListItemFlexColumnRenderer
                        ?.text ?: return null
                
                    val secondColumn = renderer.flexColumns.getOrNull(1)
                        ?.musicResponsiveListItemFlexColumnRenderer
                        ?.text ?: return null

                    val titleRun = firstColumn.runs?.firstOrNull() ?: return null
                    val title = titleRun.text.takeIf { it.isNotBlank() } ?: return null

                    val artists = secondColumn.runs?.mapNotNull { run ->
                        run.text.takeIf { it.isNotBlank() }?.let { name ->
                            Artist(
                                name = name,
                                id = run.navigationEndpoint?.browseEndpoint?.browseId
                            )
                        }
                    } ?: emptyList()

                    val thirdColumn = renderer.flexColumns.getOrNull(2)
                        ?.musicResponsiveListItemFlexColumnRenderer
                        ?.text

                    SongItem(
                        id = renderer.playlistItemData.videoId,
                        title = title,
                        artists = artists,
                        thumbnail = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                        musicVideoType = renderer.musicVideoType,
                        explicit = renderer.badges?.any { 
                            it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE" 
                        } == true,
                        chartPosition = thirdColumn?.runs?.firstOrNull()?.text?.toIntOrNull(),
                        chartChange = thirdColumn?.runs?.getOrNull(1)?.text
                    )
                }
                else -> null
            }
        } catch (e: Exception) {
            println("Error converting chart item: ${e.message}\n${Json.encodeToString(renderer)}")
            null
        }
    }

    private fun convertMusicTwoRowItem(renderer: MusicTwoRowItemRenderer): YTItem? {
        return try {
            when {
                renderer.isSong -> {
                    val subtitle = renderer.subtitle?.runs ?: return null
                    SongItem(
                        id = renderer.navigationEndpoint.watchEndpoint?.videoId ?: return null,
                        title = renderer.title.runs?.firstOrNull()?.text ?: return null,
                        artists = subtitle.mapNotNull {
                            it.navigationEndpoint?.browseEndpoint?.browseId?.let { id ->
                                Artist(name = it.text, id = id)
                            }
                        },
                        thumbnail = renderer.thumbnailRenderer.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                        musicVideoType = renderer.musicVideoType,
                        explicit = renderer.subtitleBadges?.any {
                            it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                        } == true
                    )
                }
                renderer.isAlbum -> {
                    AlbumItem(
                        browseId = renderer.navigationEndpoint.browseEndpoint?.browseId ?: return null,
                        playlistId = renderer.thumbnailOverlay?.musicItemThumbnailOverlayRenderer?.content
                            ?.musicPlayButtonRenderer?.playNavigationEndpoint
                            ?.watchPlaylistEndpoint?.playlistId ?: return null,
                        title = renderer.title.runs?.firstOrNull()?.text ?: return null,
                        artists = renderer.subtitle?.runs?.oddElements()?.drop(1)?.mapNotNull {
                            it.navigationEndpoint?.browseEndpoint?.browseId?.let { id ->
                                Artist(name = it.text, id = id)
                            }
                        },
                        year = renderer.subtitle?.runs?.lastOrNull()?.text?.toIntOrNull(),
                        thumbnail = renderer.thumbnailRenderer.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                        explicit = renderer.subtitleBadges?.any {
                            it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                        } == true
                    )
                }
                else -> null
            }
        } catch (e: Exception) {
            println("Error converting two row item: ${e.message}\n${Json.encodeToString(renderer)}")
            null
        }
    }

    suspend fun musicHistory() = runCatching {
        val response = innerTube.browse(
            client = WEB_REMIX,
            browseId = "FEmusic_history",
            setLogin = true
        ).body<BrowseResponse>()

        HistoryPage(
            sections = response.contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()
                ?.tabRenderer?.content?.sectionListRenderer?.contents
                ?.mapNotNull {
                    it.musicShelfRenderer?.let { musicShelfRenderer ->
                        HistoryPage.fromMusicShelfRenderer(musicShelfRenderer)
                    }
                }
        )
    }

    /**
     * Fetch podcast discovery/recommendations page.
     * Returns sections like "Popular shows", "Popular episodes", category sections.
     */
    suspend fun podcastDiscover(): Result<HomePage> = runCatching {
        val response = innerTube.browse(
            client = WEB_REMIX,
            browseId = "FEmusic_non_music_audio",
            setLogin = true
        ).body<BrowseResponse>()

        val sectionListRenderer = response.contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()
            ?.tabRenderer?.content?.sectionListRenderer
        val carousels = sectionListRenderer?.contents?.mapNotNull { it.musicCarouselShelfRenderer } ?: emptyList()
        val sections = carousels.mapNotNull {
            HomePage.Section.fromMusicCarouselShelfRenderer(it)
        }
        val chips = sectionListRenderer?.header?.chipCloudRenderer?.chips?.mapNotNull {
            HomePage.Chip.fromChipCloudChipRenderer(it)
        }
        val continuation = sectionListRenderer?.continuations?.getContinuation()

        HomePage(chips, sections, continuation)
    }

    suspend fun likeVideo(videoId: String, like: Boolean) = runCatching {
        if (like)
            innerTube.likeVideo(WEB_REMIX, videoId)
        else
            innerTube.unlikeVideo(WEB_REMIX, videoId)
    }

    suspend fun likePlaylist(playlistId: String, like: Boolean) = runCatching {
        if (like)
            innerTube.likePlaylist(WEB_REMIX, playlistId)
        else
            innerTube.unlikePlaylist(WEB_REMIX, playlistId)
    }

    suspend fun subscribeChannel(channelId: String, subscribe: Boolean) = runCatching {
        if (subscribe)
            innerTube.subscribeChannel(WEB_REMIX, channelId)
        else
            innerTube.unsubscribeChannel(WEB_REMIX, channelId)
    }

    /**
     * Save a podcast show to library.
     * Uses likePlaylist API. Podcast IDs are "MPSP<playlistId>".
     */
    suspend fun savePodcast(podcastId: String, save: Boolean) = runCatching {
        val playlistId = podcastId.removePrefix("MPSP")
        Timber.d("[PODCAST_API] savePodcast: podcastId=$podcastId, playlistId=$playlistId, save=$save")
        if (save)
            innerTube.likePlaylist(WEB_REMIX, playlistId)
        else
            innerTube.unlikePlaylist(WEB_REMIX, playlistId)
    }

    /**
     * Add episode to "Episodes for Later" playlist (SE).
     */
    suspend fun addEpisodeToSavedEpisodes(videoId: String) = runCatching {
        innerTube.addToPlaylist(WEB_REMIX, "SE", videoId)
    }

    /**
     * Remove episode from "Episodes for Later" playlist (SE).
     * Note: setVideoId is required for removal and must be obtained from the playlist response.
     */
    suspend fun removeEpisodeFromSavedEpisodes(videoId: String, setVideoId: String) = runCatching {
        innerTube.removeFromPlaylist(WEB_REMIX, "SE", videoId, setVideoId)
    }

    suspend fun libraryPodcastChannels(): Result<LibraryPage> {
        Timber.d("[PODCAST_API] libraryPodcastChannels: calling browse with FEmusic_library_non_music_audio_channels_list")
        return runCatching {
            val response = innerTube.browse(
                client = WEB_REMIX,
                browseId = "FEmusic_library_non_music_audio_channels_list",
                setLogin = true
            ).body<BrowseResponse>()

            val contents = response.contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()
                ?.tabRenderer?.content?.sectionListRenderer?.contents?.firstOrNull()

            val items = when {
                contents?.gridRenderer != null -> {
                    contents.gridRenderer.items
                        .mapNotNull(GridRenderer.Item::musicTwoRowItemRenderer)
                        .mapNotNull { LibraryPage.fromMusicTwoRowItemRenderer(it) }
                }
                contents?.musicShelfRenderer != null -> {
                    contents.musicShelfRenderer.contents
                        ?.mapNotNull(MusicShelfRenderer.Content::musicResponsiveListItemRenderer)
                        ?.mapNotNull { LibraryPage.fromMusicResponsiveListItemRenderer(it) }
                        ?: emptyList()
                }
                else -> emptyList()
            }

            LibraryPage(
                items = items,
                continuation = null
            )
        }.also { result ->
            result.onFailure { e -> Timber.e(e, "[PODCAST_API] libraryPodcastChannels FAILED") }
            result.onSuccess { Timber.d("[PODCAST_API] libraryPodcastChannels SUCCESS: ${it.items.size} items") }
        }
    }

    suspend fun libraryPodcastEpisodes(): Result<LibraryPage> {
        Timber.d("[PODCAST_API] libraryPodcastEpisodes: calling browse with FEmusic_library_non_music_audio_list")
        return runCatching {
            val response = innerTube.browse(
                client = WEB_REMIX,
                browseId = "FEmusic_library_non_music_audio_list",
                setLogin = true
            ).body<BrowseResponse>()

            val contents = response.contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()
                ?.tabRenderer?.content?.sectionListRenderer?.contents?.firstOrNull()

            val items = when {
                contents?.gridRenderer != null -> {
                    contents.gridRenderer.items
                        .mapNotNull(GridRenderer.Item::musicTwoRowItemRenderer)
                        .mapNotNull { LibraryPage.fromMusicTwoRowItemRenderer(it) }
                }
                contents?.musicShelfRenderer != null -> {
                    contents.musicShelfRenderer.contents
                        ?.mapNotNull(MusicShelfRenderer.Content::musicResponsiveListItemRenderer)
                        ?.mapNotNull { LibraryPage.fromMusicResponsiveListItemRenderer(it) }
                        ?: emptyList()
                }
                else -> emptyList()
            }

            LibraryPage(
                items = items,
                continuation = null
            )
        }.also { result ->
            result.onFailure { e -> Timber.e(e, "[PODCAST_API] libraryPodcastEpisodes FAILED") }
            result.onSuccess { Timber.d("[PODCAST_API] libraryPodcastEpisodes SUCCESS: ${it.items.size} items") }
        }
    }

    /**
     * Fetch saved podcast shows from library.
     * Uses FEmusic_library_non_music_audio_list and filters to only PodcastItem.
     */
    suspend fun savedPodcastShows(): Result<List<PodcastItem>> = runCatching {
        val libraryPage = libraryPodcastEpisodes().getOrThrow()
        libraryPage.items.filterIsInstance<PodcastItem>()
    }

    /**
     * Fetch "New Episodes" auto-playlist (VLRDPN).
     * Returns new episodes from saved/subscribed podcasts.
     */
    suspend fun newEpisodes(): Result<List<SongItem>> {
        Timber.d("[PODCAST_API] newEpisodes: calling browse with VLRDPN")
        return runCatching {
            val response = innerTube.browse(
                client = WEB_REMIX,
                browseId = "VLRDPN",
                setLogin = true
            ).body<BrowseResponse>()

            response.contents?.twoColumnBrowseResultsRenderer?.secondaryContents?.sectionListRenderer
                ?.contents?.firstOrNull()?.musicShelfRenderer?.contents
                ?.mapNotNull { it.musicMultiRowListItemRenderer }
                ?.map { renderer ->
                    SongItem(
                        id = renderer.onTap?.watchEndpoint?.videoId ?: "",
                        title = renderer.title?.runs?.firstOrNull()?.text ?: "",
                        artists = renderer.subtitle?.runs?.mapNotNull { run ->
                            run.navigationEndpoint?.browseEndpoint?.let { endpoint ->
                                Artist(name = run.text, id = endpoint.browseId)
                            }
                        } ?: emptyList(),
                        album = null,
                        duration = null,
                        thumbnail = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl() ?: "",
                        isEpisode = true,
                    )
                } ?: emptyList()
        }.also { result ->
            result.onFailure { e -> Timber.e(e, "[PODCAST_API] newEpisodes FAILED") }
            result.onSuccess { Timber.d("[PODCAST_API] newEpisodes SUCCESS: ${it.size} items") }
        }
    }

    /**
     * Fetch "Episodes for Later" playlist (VLSE).
     * Returns manually saved episodes.
     */
    suspend fun episodesForLater(): Result<List<SongItem>> = runCatching {
        Timber.d("[PODCAST_API] episodesForLater: calling browse with VLSE")
        val response = innerTube.browse(
            client = WEB_REMIX,
            browseId = "VLSE",
            setLogin = true
        ).body<BrowseResponse>()

        // VLSE uses musicPlaylistShelfRenderer, not musicShelfRenderer
        val contents = response.contents?.twoColumnBrowseResultsRenderer?.secondaryContents?.sectionListRenderer
            ?.contents?.firstOrNull()

        val shelfContents = contents?.musicPlaylistShelfRenderer?.contents
            ?: contents?.musicShelfRenderer?.contents

        // Parse musicResponsiveListItemRenderer (standard playlist format)
        shelfContents?.mapNotNull { it.musicResponsiveListItemRenderer }
            ?.mapNotNull { renderer ->
                val videoId = renderer.playlistItemData?.videoId ?: return@mapNotNull null
                val setVideoId = renderer.playlistItemData?.playlistSetVideoId
                val title = renderer.flexColumns.firstOrNull()
                    ?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()?.text
                    ?: return@mapNotNull null
                val artistRun = renderer.flexColumns.getOrNull(1)
                    ?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()
                SongItem(
                    id = videoId,
                    title = title,
                    artists = artistRun?.let { listOf(Artist(name = it.text, id = it.navigationEndpoint?.browseEndpoint?.browseId)) } ?: emptyList(),
                    album = null,
                    duration = null,
                    thumbnail = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl() ?: "",
                    setVideoId = setVideoId,
                    isEpisode = true,
                )
            } ?: emptyList()
    }

    /**
     * Fetch "Continue Listening" / Resume Playback.
     * Returns partially played episodes for resumption.
     */
    suspend fun continueListening(): Result<List<SongItem>> = runCatching {
        val response = innerTube.browse(
            client = WEB_REMIX,
            browseId = "FEmusic_listening_review",
            setLogin = true
        ).body<BrowseResponse>()

        response.contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()
            ?.tabRenderer?.content?.sectionListRenderer?.contents
            ?.flatMap { section ->
                section.musicShelfRenderer?.contents?.mapNotNull { content ->
                    content.musicResponsiveListItemRenderer?.let { renderer ->
                        val videoId = renderer.playlistItemData?.videoId ?: return@mapNotNull null
                        val title = renderer.flexColumns.firstOrNull()
                            ?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()?.text
                            ?: return@mapNotNull null
                        val artistRun = renderer.flexColumns.getOrNull(1)
                            ?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()
                        SongItem(
                            id = videoId,
                            title = title,
                            artists = artistRun?.let { listOf(Artist(name = it.text, id = it.navigationEndpoint?.browseEndpoint?.browseId)) } ?: emptyList(),
                            album = null,
                            duration = null,
                            thumbnail = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl() ?: "",
                            isEpisode = true,
                        )
                    }
                } ?: emptyList()
            } ?: emptyList()
    }

    suspend fun getChannelId(browseId: String): String {
        artist(browseId).onSuccess {
            return it.artist.channelId ?: ""
        }
        return ""
    }

    suspend fun addToPlaylist(playlistId: String, videoId: String) = runCatching {
        innerTube.addToPlaylist(WEB_REMIX, playlistId, videoId)
    }

    suspend fun addPlaylistToPlaylist(playlistId: String, addPlaylistId: String) = runCatching {
        innerTube.addPlaylistToPlaylist(WEB_REMIX, playlistId, addPlaylistId)
    }

    suspend fun removeFromPlaylist(playlistId: String, videoId: String, setVideoId: String) = runCatching {
        innerTube.removeFromPlaylist(WEB_REMIX, playlistId, videoId, setVideoId)
    }

    suspend fun moveSongPlaylist(playlistId: String, setVideoId: String, successorSetVideoId: String?) = runCatching {
        innerTube.moveSongPlaylist(WEB_REMIX, playlistId, setVideoId, successorSetVideoId)
    }

    fun createPlaylist(title: String) = runBlocking {
        innerTube.createPlaylist(WEB_REMIX, title).body<CreatePlaylistResponse>().playlistId
    }

    suspend fun renamePlaylist(playlistId: String, name: String) = runCatching {
        innerTube.renamePlaylist(WEB_REMIX, playlistId, name)
    }

    suspend fun uploadCustomThumbnailLink(playlistId: String, image: ByteArray) = runCatching {
        val uploadUrl = innerTube.getUploadCustomThumbnailLink(WEB_REMIX, image.size).headers["x-guploader-uploadid"]
        val blobReq = innerTube.uploadCustomThumbnail(
            WEB_REMIX,
            uploadUrl!!,
            image
        )
        val blobId = Json.decodeFromString<ImageUploadResponse>(blobReq.bodyAsText()).encryptedBlobId
        innerTube.setThumbnailPlaylist(WEB_REMIX, playlistId, blobId).body<EditPlaylistResponse>().newHeader?.musicEditablePlaylistDetailHeaderRenderer?.header?.musicResponsiveHeaderRenderer?.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl()
    }

    suspend fun removeThumbnailPlaylist(playlistId: String) = runCatching {
        innerTube.removeThumbnailPlaylist(WEB_REMIX, playlistId).body<EditPlaylistResponse>().newHeader?.musicEditablePlaylistDetailHeaderRenderer?.header?.musicResponsiveHeaderRenderer?.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl()
    }

    suspend fun deletePlaylist(playlistId: String) = runCatching {
        innerTube.deletePlaylist(WEB_REMIX, playlistId)
    }

    suspend fun player(videoId: String, playlistId: String? = null, client: YouTubeClient, signatureTimestamp: Int? = null, poToken: String? = null): Result<PlayerResponse> = runCatching {
        innerTube.player(client, videoId, playlistId, signatureTimestamp, poToken).body<PlayerResponse>()
    }

    suspend fun registerPlayback(playlistId: String? = null, playbackTracking: String) = runCatching {
        val cpn = (1..16).map {
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"[Random.Default.nextInt(
                0,
                64
            )]
        }.joinToString("")

        val playbackUrl = playbackTracking.replace(
            "https://s.youtube.com",
            "https://music.youtube.com",
        )

        innerTube.registerPlayback(
            url = playbackUrl,
            playlistId = playlistId,
            cpn = cpn
        )
    }

    suspend fun next(endpoint: WatchEndpoint, continuation: String? = null): Result<NextResult> = runCatching {
        val response = innerTube.next(
            WEB_REMIX,
            endpoint.videoId,
            endpoint.playlistId,
            endpoint.playlistSetVideoId,
            endpoint.index,
            endpoint.params,
            continuation).body<NextResponse>()
        val playlistPanelRenderer = response.continuationContents?.playlistPanelContinuation
            ?: response.contents.singleColumnMusicWatchNextResultsRenderer?.tabbedRenderer
                ?.watchNextTabbedResultsRenderer?.tabs?.get(0)?.tabRenderer?.content?.musicQueueRenderer
                ?.content?.playlistPanelRenderer!!
        val title = response.contents.singleColumnMusicWatchNextResultsRenderer?.tabbedRenderer
            ?.watchNextTabbedResultsRenderer?.tabs?.get(0)?.tabRenderer?.content?.musicQueueRenderer
            ?.header?.musicQueueHeaderRenderer?.subtitle?.runs?.firstOrNull()?.text
        val items = playlistPanelRenderer.contents.mapNotNull { content ->
            content.playlistPanelVideoRenderer
                ?.let(NextPage::fromPlaylistPanelVideoRenderer)
                ?.let { it to content.playlistPanelVideoRenderer.selected }
        }
        val songs = items.map { it.first }
        val currentIndex = items.indexOfFirst { it.second }.takeIf { it != -1 }

        // load automix items
        playlistPanelRenderer.contents.lastOrNull()?.automixPreviewVideoRenderer?.content?.automixPlaylistVideoRenderer?.navigationEndpoint?.watchPlaylistEndpoint?.let { watchPlaylistEndpoint ->
            return@runCatching next(watchPlaylistEndpoint).getOrThrow().let { result ->
                result.copy(
                    title = title,
                    items = songs + result.items,
                    lyricsEndpoint = response.contents.singleColumnMusicWatchNextResultsRenderer?.tabbedRenderer?.watchNextTabbedResultsRenderer?.tabs?.getOrNull(1)?.tabRenderer?.endpoint?.browseEndpoint,
                    relatedEndpoint = response.contents.singleColumnMusicWatchNextResultsRenderer?.tabbedRenderer?.watchNextTabbedResultsRenderer?.tabs?.getOrNull(2)?.tabRenderer?.endpoint?.browseEndpoint,
                    currentIndex = currentIndex,
                    endpoint = watchPlaylistEndpoint
                )
            }
        }
        NextResult(
            title = title,
            items = songs,
            currentIndex = currentIndex,
            lyricsEndpoint = response.contents.singleColumnMusicWatchNextResultsRenderer?.tabbedRenderer?.watchNextTabbedResultsRenderer?.tabs?.getOrNull(1)?.tabRenderer?.endpoint?.browseEndpoint,
            relatedEndpoint = response.contents.singleColumnMusicWatchNextResultsRenderer?.tabbedRenderer?.watchNextTabbedResultsRenderer?.tabs?.getOrNull(2)?.tabRenderer?.endpoint?.browseEndpoint,
            continuation = playlistPanelRenderer.continuations?.getContinuation(),
            endpoint = endpoint
        )
    }

    suspend fun lyrics(endpoint: BrowseEndpoint): Result<String?> = runCatching {
        val response = innerTube.browse(WEB_REMIX, endpoint.browseId, endpoint.params).body<BrowseResponse>()
        response.contents?.sectionListRenderer?.contents
            ?.firstOrNull { it.musicDescriptionShelfRenderer != null }
            ?.musicDescriptionShelfRenderer?.description?.runs
            ?.joinToString(separator = "") { it.text }
    }

    suspend fun related(endpoint: BrowseEndpoint): Result<RelatedPage> = runCatching {
        val response = innerTube.browse(WEB_REMIX, endpoint.browseId).body<BrowseResponse>()
        val songs = mutableListOf<SongItem>()
        val albums = mutableListOf<AlbumItem>()
        val artists = mutableListOf<ArtistItem>()
        val playlists = mutableListOf<PlaylistItem>()
        response.contents?.sectionListRenderer?.contents?.forEach { sectionContent ->
            sectionContent.musicCarouselShelfRenderer?.contents?.forEach { content ->
                when (val item = content.musicResponsiveListItemRenderer?.let(RelatedPage.Companion::fromMusicResponsiveListItemRenderer)
                    ?: content.musicTwoRowItemRenderer?.let(RelatedPage.Companion::fromMusicTwoRowItemRenderer)) {
                    is SongItem -> if (content.musicResponsiveListItemRenderer?.overlay
                            ?.musicItemThumbnailOverlayRenderer?.content
                            ?.musicPlayButtonRenderer?.playNavigationEndpoint
                            ?.watchEndpoint?.watchEndpointMusicSupportedConfigs
                            ?.watchEndpointMusicConfig?.musicVideoType == MUSIC_VIDEO_TYPE_ATV
                    ) songs.add(item)

                    is AlbumItem -> albums.add(item)
                    is ArtistItem -> artists.add(item)
                    is PlaylistItem -> playlists.add(item)
                    is PodcastItem, is EpisodeItem -> {}
                    null -> {}
                }
            }
        }
        RelatedPage(songs, albums, artists, playlists)
    }

    suspend fun queue(videoIds: List<String>? = null, playlistId: String? = null): Result<List<SongItem>> = runCatching {
        if (videoIds != null) {
            assert(videoIds.size <= MAX_GET_QUEUE_SIZE) // Max video limit
        }
        innerTube.getQueue(WEB_REMIX, videoIds, playlistId).body<GetQueueResponse>().queueDatas
            .mapNotNull {
                it.content.playlistPanelVideoRenderer?.let { renderer ->
                    NextPage.fromPlaylistPanelVideoRenderer(renderer)
                }
            }
    }

    suspend fun transcript(videoId: String): Result<String> = runCatching {
        val response = innerTube.getTranscript(WEB, videoId).body<GetTranscriptResponse>()
        response.actions?.firstOrNull()?.updateEngagementPanelAction?.content?.transcriptRenderer?.body?.transcriptBodyRenderer?.cueGroups?.joinToString(separator = "\n") { group ->
            val time = group.transcriptCueGroupRenderer.cues[0].transcriptCueRenderer.startOffsetMs
            val text = group.transcriptCueGroupRenderer.cues[0].transcriptCueRenderer.cue.simpleText
                .trim('♪')
                .trim(' ')
            "[%02d:%02d.%03d]$text".format(time / 60000, (time / 1000) % 60, time % 1000)
        }!!
    }

    suspend fun visitorData(): Result<String> = runCatching {
        Json.parseToJsonElement(innerTube.getSwJsData().bodyAsText().substring(5))
            .jsonArray[0]
            .jsonArray[2]
            .jsonArray.first {
                (it as? JsonPrimitive)?.contentOrNull?.let { candidate ->
                    VISITOR_DATA_REGEX.containsMatchIn(candidate)
                } ?: false
            }
            .jsonPrimitive.content
    }

    suspend fun accountInfo(): Result<AccountInfo> = runCatching {
        innerTube.accountMenu(WEB_REMIX).body<AccountMenuResponse>()
            .actions[0].openPopupAction.popup.multiPageMenuRenderer
            .header?.activeAccountHeaderRenderer
            ?.toAccountInfo()!!
    }

    suspend fun feedback(tokens: List<String>): Result<Boolean> = runCatching {
        innerTube.feedback(WEB_REMIX, tokens).body<FeedbackResponse>().feedbackResponses.all { it.isProcessed }
    }

    /**
     * Add a song to library by fetching fresh feedback tokens from the next endpoint
     * This is more reliable than using cached tokens which might be stale
     */
    suspend fun addSongToLibrary(videoId: String): Result<Boolean> = runCatching {
        // Get fresh song data with menu tokens using next endpoint
        val nextResult = next(WatchEndpoint(videoId = videoId)).getOrThrow()
        val song = nextResult.items.find { it.id == videoId }
            ?: throw Exception("Song not found in next response")
        
        val addToken = song.libraryAddToken
            ?: throw Exception("Add to library token not available")
        
        feedback(listOf(addToken)).getOrThrow()
    }

    /**
     * Remove a song from library by fetching fresh feedback tokens from the next endpoint
     */
    suspend fun removeSongFromLibrary(videoId: String): Result<Boolean> = runCatching {
        // Get fresh song data with menu tokens using next endpoint
        val nextResult = next(WatchEndpoint(videoId = videoId)).getOrThrow()
        val song = nextResult.items.find { it.id == videoId }
            ?: throw Exception("Song not found in next response")
        
        val removeToken = song.libraryRemoveToken
            ?: throw Exception("Remove from library token not available")
        
        feedback(listOf(removeToken)).getOrThrow()
    }

    /**
     * Toggle song library status - adds if not in library, removes if in library
     * Uses fresh tokens fetched from the API for reliability
     */
    suspend fun toggleSongLibrary(videoId: String, addToLibrary: Boolean): Result<Boolean> = runCatching {
        if (addToLibrary) {
            addSongToLibrary(videoId).getOrThrow()
        } else {
            removeSongFromLibrary(videoId).getOrThrow()
        }
    }

    suspend fun getMediaInfo(videoId: String): Result<MediaInfo> = runCatching {
        return innerTube.getMediaInfo(videoId)
    }

    suspend fun getTasteProfile(): Result<TasteProfile> = runCatching {
        // Browse the taste builder page
        // Note: Full parsing requires additional model support for musicTastebuilderShelfRenderer
        // This returns an empty profile for now - can be enhanced when models are added
        innerTube.browse(
            client = WEB_REMIX,
            browseId = "FEmusic_tastebuilder",
            setLogin = true
        ).body<BrowseResponse>()

        TasteProfile(artists = emptyMap())
    }

    suspend fun setTasteProfile(selectedArtists: List<String>, allArtists: Map<String, TasteArtist>): Result<Unit> = runCatching {
        val selectedValues = selectedArtists.mapNotNull { allArtists[it]?.selectionValue }
        val impressionValues = allArtists.values.map { it.impressionValue }

        if (selectedValues.isNotEmpty()) {
            feedback(selectedValues + impressionValues).getOrThrow()
        }
    }

    suspend fun removeHistoryItems(feedbackTokens: List<String>): Result<Boolean> = runCatching {
        feedback(feedbackTokens).getOrThrow()
    }

    @JvmInline
    value class SearchFilter(val value: String) {
        companion object {
            val FILTER_SONG = SearchFilter("EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D")
            val FILTER_VIDEO = SearchFilter("EgWKAQIQAWoKEAkQChAFEAMQBA%3D%3D")
            val FILTER_ALBUM = SearchFilter("EgWKAQIYAWoKEAkQChAFEAMQBA%3D%3D")
            val FILTER_ARTIST = SearchFilter("EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D")
            val FILTER_FEATURED_PLAYLIST = SearchFilter("EgeKAQQoADgBagwQDhAKEAMQBRAJEAQ%3D")
            val FILTER_COMMUNITY_PLAYLIST = SearchFilter("EgeKAQQoAEABagoQAxAEEAoQCRAF")
            val FILTER_PODCAST = SearchFilter("EgWKAQJQAWoKEAkQChAFEAMQBA%3D%3D")
            val FILTER_EPISODE = SearchFilter("EgWKAQJYAWoKEAkQChAFEAMQBA%3D%3D")
        }
    }

    @JvmInline
    value class LibraryFilter(val value: String) {
        companion object {
            val FILTER_RECENT_ACTIVITY = LibraryFilter("4qmFsgIrEhdGRW11c2ljX2xpYnJhcnlfbGFuZGluZxoQZ2dNR0tnUUlCaEFCb0FZQg%3D%3D")
            val FILTER_RECENTLY_PLAYED = LibraryFilter("4qmFsgIrEhdGRW11c2ljX2xpYnJhcnlfbGFuZGluZxoQZ2dNR0tnUUlCUkFCb0FZQg%3D%3D")
            val FILTER_PLAYLISTS_ALPHABETICAL = LibraryFilter("4qmFsgIrEhdGRW11c2ljX2xpa2VkX3BsYXlsaXN0cxoQZ2dNR0tnUUlBUkFBb0FZQg%3D%3D")
            val FILTER_PLAYLISTS_RECENTLY_SAVED = LibraryFilter("4qmFsgIrEhdGRW11c2ljX2xpa2VkX3BsYXlsaXN0cxoQZ2dNR0tnUUlBQkFCb0FZQg%3D%3D")
        }
    }

    const val MAX_GET_QUEUE_SIZE = 1000

    private val VISITOR_DATA_REGEX = Regex("^Cg[t|s]")

    fun getNewPipeStreamUrls(videoId: String): List<Pair<Int, String>> {
        return NewPipeExtractor.newPipePlayer(videoId)
    }

    suspend fun newPipePlayer(
        videoId: String,
        tempRes: PlayerResponse,
    ): PlayerResponse? {
        if (tempRes.playabilityStatus.status != "OK") {
            return null
        }

        val streamsList = getNewPipeStreamUrls(videoId)
        if (streamsList.isEmpty()) return null

        val decodedSigResponse = tempRes.copy(
            streamingData = tempRes.streamingData?.copy(
                formats = tempRes.streamingData.formats?.map { format ->
                    format.copy(
                        url = streamsList.find { it.first == format.itag }?.second ?: format.url,
                    )
                },
                adaptiveFormats = tempRes.streamingData.adaptiveFormats.map { adaptiveFormat ->
                    adaptiveFormat.copy(
                        url = streamsList.find { it.first == adaptiveFormat.itag }?.second ?: adaptiveFormat.url,
                    )
                },
            ),
        )

        val urlList = (
            decodedSigResponse.streamingData?.adaptiveFormats?.mapNotNull { it.url }?.toMutableList() ?: mutableListOf()
        ).apply {
            decodedSigResponse.streamingData?.formats?.mapNotNull { it.url }?.let { addAll(it) }
        }

        return if (urlList.isNotEmpty()) {
            decodedSigResponse
        } else {
            null
        }
    }
}
