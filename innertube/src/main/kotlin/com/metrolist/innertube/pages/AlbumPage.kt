package com.metrolist.innertube.pages

import com.metrolist.innertube.models.Album
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.MusicResponsiveHeaderRenderer
import com.metrolist.innertube.models.MusicResponsiveListItemRenderer
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.getItems
import com.metrolist.innertube.models.oddElements
import com.metrolist.innertube.models.response.BrowseResponse
import com.metrolist.innertube.models.splitBySeparator
import com.metrolist.innertube.utils.parseTime

data class AlbumPage(
    val album: AlbumItem,
    val songs: List<SongItem>,
    val otherVersions: List<AlbumItem>,
) {
    companion object {
        fun getPlaylistId(response: BrowseResponse): String? {
            var playlistId = response.microformat?.microformatDataRenderer?.urlCanonical?.substringAfterLast('=')
            if (playlistId == null) {
                playlistId = response.header?.musicDetailHeaderRenderer?.menu?.menuRenderer?.topLevelButtons?.firstOrNull()
                    ?.buttonRenderer?.navigationEndpoint?.watchPlaylistEndpoint?.playlistId
            }
            return playlistId
        }

        fun getTitle(response: BrowseResponse): String? {
            val title = getHeader(response)?.title ?: response.header?.musicDetailHeaderRenderer?.title
            return title?.runs?.firstOrNull()?.text
        }

        fun getYear(response: BrowseResponse): Int? {
            val title = getHeader(response)?.subtitle ?: response.header?.musicDetailHeaderRenderer?.subtitle
            return title?.runs?.lastOrNull()?.text?.toIntOrNull()
        }

        fun getThumbnail(response: BrowseResponse): String? {
            return response.background?.musicThumbnailRenderer?.getThumbnailUrl()
                ?: response.header?.musicDetailHeaderRenderer?.thumbnail?.croppedSquareThumbnailRenderer?.getThumbnailUrl()
        }

        fun getArtists(response: BrowseResponse): List<Artist> {
            val artists = getHeader(response)?.straplineTextOne?.runs?.oddElements()?.map {
                Artist(
                    name = it.text,
                    id = it.navigationEndpoint?.browseEndpoint?.browseId
                )
            } ?: response.header?.musicDetailHeaderRenderer?.subtitle?.runs?.splitBySeparator()?.getOrNull(1)?.oddElements()?.map {
                Artist(
                    name = it.text,
                    id = it.navigationEndpoint?.browseEndpoint?.browseId
                )
            } ?: emptyList()

            return artists
        }

        private fun getHeader(response: BrowseResponse): MusicResponsiveHeaderRenderer? {
            val tabs = response.contents?.singleColumnBrowseResultsRenderer?.tabs
                ?: response.contents?.twoColumnBrowseResultsRenderer?.tabs
            val section =
                tabs?.firstOrNull()?.tabRenderer?.content?.sectionListRenderer?.contents?.firstOrNull()
            val header = section?.musicResponsiveHeaderRenderer
            return header
        }

        fun getSongs(response: BrowseResponse, album: AlbumItem): List<SongItem> {
            val tabs = response.contents?.singleColumnBrowseResultsRenderer?.tabs
                ?: response.contents?.twoColumnBrowseResultsRenderer?.tabs
            val shelfRenderer = tabs?.firstOrNull()?.tabRenderer?.content?.sectionListRenderer?.contents?.firstOrNull()?.musicShelfRenderer
                ?: response.contents?.twoColumnBrowseResultsRenderer?.secondaryContents?.sectionListRenderer?.contents?.firstOrNull()?.musicShelfRenderer

            val songs = shelfRenderer?.contents?.getItems()?.mapNotNull {
                getSong(it, album)
            }
            return songs ?: emptyList()
        }

        fun getSong(renderer: MusicResponsiveListItemRenderer, album: AlbumItem? = null): SongItem? {
            val libraryTokens = PageHelper.extractLibraryTokensFromMenuItems(renderer.menu?.menuRenderer?.items)
            val id = renderer.playlistItemData?.videoId ?: return null
            val title = PageHelper.extractRuns(renderer.flexColumns, "MUSIC_VIDEO").firstOrNull()?.text ?: return null

            val artists = PageHelper.extractRuns(renderer.flexColumns, "MUSIC_PAGE_TYPE_ARTIST").map {
                Artist(
                    name = it.text,
                    id = it.navigationEndpoint?.browseEndpoint?.browseId,
                )
            }.ifEmpty {
                album?.artists ?: emptyList()
            }

            val resolvedAlbum = album?.let { Album(it.title, it.browseId) } ?: run {
                val albumRun = renderer.flexColumns.getOrNull(2)
                    ?.musicResponsiveListItemFlexColumnRenderer
                    ?.text
                    ?.runs
                    ?.firstOrNull()
                val albumId = albumRun?.navigationEndpoint?.browseEndpoint?.browseId
                val albumName = albumRun?.text
                if (!albumId.isNullOrBlank() && !albumName.isNullOrBlank()) {
                    Album(name = albumName, id = albumId)
                } else {
                    null
                }
            } ?: return null

            val duration = renderer.fixedColumns?.firstOrNull()
                ?.musicResponsiveListItemFlexColumnRenderer
                ?.text
                ?.runs
                ?.firstOrNull()
                ?.text
                ?.parseTime() ?: return null

            return SongItem(
                id = id,
                title = title,
                artists = artists,
                album = resolvedAlbum,
                duration = duration,
                musicVideoType = renderer.musicVideoType,
                thumbnail = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl() ?: album?.thumbnail.orEmpty(),
                explicit = renderer.badges?.any {
                    it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                } == true,
                libraryAddToken = libraryTokens.addToken,
                libraryRemoveToken = libraryTokens.removeToken,
            )
        }
    }
}
