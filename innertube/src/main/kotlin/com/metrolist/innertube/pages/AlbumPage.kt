package com.metrolist.innertube.pages

import com.metrolist.innertube.models.Album
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.MusicResponsiveListItemRenderer
import com.metrolist.innertube.models.MusicShelfRenderer
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.getContinuation
import com.metrolist.innertube.models.oddElements
import com.metrolist.innertube.models.response.BrowseResponse
import com.metrolist.innertube.models.splitBySeparator
import com.metrolist.innertube.utils.parseTime

data class AlbumPage(
    val album: AlbumItem,
    val songs: List<SongItem>,
    val otherVersions: List<AlbumItem>,
    // Only a fully parsed track list with exhausted continuations may replace cached tracks.
    val isSongListComplete: Boolean = false,
) {
    companion object {
        internal suspend fun loadSongs(
            response: BrowseResponse,
            album: AlbumItem?,
            fetchContinuation: suspend (String) -> BrowseResponse,
        ): List<SongItem> {
            val shelf = response.contents?.twoColumnBrowseResultsRenderer?.secondaryContents
                ?.sectionListRenderer?.contents?.firstOrNull {
                    it.musicPlaylistShelfRenderer != null || it.musicShelfRenderer != null
                }
            var contents = shelf?.musicPlaylistShelfRenderer?.contents
                ?: shelf?.musicShelfRenderer?.contents
                ?: error("Missing album track shelf")
            var continuations = shelf?.musicPlaylistShelfRenderer?.continuations
                ?: shelf?.musicShelfRenderer?.continuations
            val songs = mutableListOf<SongItem>()
            val seenContinuations = mutableSetOf<String>()

            while (true) {
                check(contents.isNotEmpty()) { "Empty album track page" }
                songs += parseSongs(contents, album)
                val token = contents.getContinuation() ?: continuations?.getContinuation()
                if (token == null) {
                    check(contents.none { it.continuationItemRenderer != null } && continuations.isNullOrEmpty()) {
                        "Missing album continuation token"
                    }
                    break
                }
                check(token.isNotBlank() && seenContinuations.add(token)) {
                    "Repeated or empty album continuation token"
                }
                check(seenContinuations.size <= 50) { "Album continuation limit exceeded" }
                val next = fetchContinuation(token)
                val playlistContinuation = next.continuationContents?.musicPlaylistShelfContinuation
                val shelfContinuation = next.continuationContents?.musicShelfContinuation
                val appended = next.onResponseReceivedActions
                    ?.mapNotNull { it.appendContinuationItemsAction?.continuationItems }.orEmpty()
                check(appended.size <= 1) { "Ambiguous album continuation contents" }
                contents = appended.singleOrNull()
                    ?: playlistContinuation?.contents
                    ?: shelfContinuation?.contents
                    ?: error("Missing album continuation contents")
                continuations = playlistContinuation?.continuations ?: shelfContinuation?.continuations
            }
            check(songs.map { it.id }.distinct().size == songs.size) { "Duplicate album track IDs" }
            return songs
        }

        private fun parseSongs(
            contents: List<MusicShelfRenderer.Content>,
            album: AlbumItem?,
        ): List<SongItem> = contents.mapNotNull { content ->
            val renderer = content.musicResponsiveListItemRenderer
            if (renderer != null) {
                checkNotNull(getSong(renderer, album)) { "Unparseable album track" }
            } else {
                check(content.continuationItemRenderer != null) { "Unrecognized album track row" }
                null
            }
        }

        fun getSong(renderer: MusicResponsiveListItemRenderer, album: AlbumItem? = null): SongItem? {
            // Extract library tokens using the new method that properly handles multiple toggle items
            val libraryTokens = PageHelper.extractLibraryTokensFromMenuItems(renderer.menu?.menuRenderer?.items)

            return SongItem(
                id = renderer.playlistItemData?.videoId
                    ?: renderer.navigationEndpoint?.watchEndpoint?.videoId
                    ?: renderer.overlay?.musicItemThumbnailOverlayRenderer
                        ?.content?.musicPlayButtonRenderer
                        ?.playNavigationEndpoint?.watchEndpoint?.videoId
                    ?: renderer.flexColumns.firstOrNull()
                        ?.musicResponsiveListItemFlexColumnRenderer
                        ?.text?.runs?.firstOrNull()
                        ?.navigationEndpoint?.watchEndpoint?.videoId
                    ?: return null,
                title = PageHelper.extractRuns(renderer.flexColumns, "MUSIC_VIDEO").firstOrNull()?.text ?: return null,
                artists = PageHelper.extractRuns(renderer.flexColumns, "MUSIC_PAGE_TYPE_ARTIST").map{
                    Artist(
                        name = it.text,
                        id = it.navigationEndpoint?.browseEndpoint?.browseId
                    )
                }.ifEmpty {
                    // Label-uploaded albums (e.g. "OLAK5uy_…" art tracks) name the performing
                    // artist as a plain-text run with no artist link, while the album header
                    // strapline is the record label / distributor channel. Prefer that
                    // plain-text artist over inheriting the label as the track artist.
                    renderer.flexColumns.getOrNull(1)
                        ?.musicResponsiveListItemFlexColumnRenderer?.text?.runs
                        ?.splitBySeparator()?.firstOrNull()?.oddElements()
                        ?.map { Artist(name = it.text, id = it.navigationEndpoint?.browseEndpoint?.browseId) }
                        ?.filter { it.name.isNotBlank() }
                        ?.takeIf { it.isNotEmpty() }
                    // Final fallback: inherit the album artist when the row has no artist at all.
                        ?: album?.artists ?: emptyList()
                },
                album = album?.let {
                    Album(it.title, it.browseId)
                } ?: renderer.flexColumns.getOrNull(2)?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()?.let {
                    Album(
                        name = it.text,
                        id = it.navigationEndpoint?.browseEndpoint?.browseId!!
                    )
                }!!,
                duration = renderer.fixedColumns?.firstOrNull()
                    ?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()
                    ?.text?.parseTime() ?: return null,
                musicVideoType = renderer.musicVideoType,
                thumbnail = renderer.thumbnail?.getThumbnailUrl() ?: album?.thumbnail!!,
                explicit = renderer.badges?.find {
                    it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                } != null,
                libraryAddToken = libraryTokens.addToken,
                libraryRemoveToken = libraryTokens.removeToken
            )
        }
    }
}
