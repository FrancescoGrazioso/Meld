package com.meld.spotify.library

import com.meld.spotify.SpotifyAlbum
import com.meld.spotify.SpotifyArtist
import com.meld.spotify.SpotifyConnection
import com.meld.spotify.SpotifyConnectionState
import com.meld.spotify.SpotifyImage
import com.meld.spotify.SpotifyLibraryItem
import com.meld.spotify.SpotifyPage
import com.meld.spotify.SpotifyTrack
import com.meld.spotify.SpotifyTrackItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch

/** What the header of a track list shows. [albums] is only filled for an artist. */
data class SpotifyCollectionInfo(
    val title: String,
    val owner: String? = null,
    val imageUrl: String? = null,
    val trackCount: Int? = null,
    val albums: List<SpotifyAlbum> = emptyList(),
)

class SpotifyCollection(
    val info: StateFlow<SpotifyCollectionInfo?>,
    val tracks: SpotifyPager<SpotifyTrackItem>,
)

enum class SpotifyLibraryTab {
    PLAYLISTS,
    ALBUMS,
    ARTISTS,
}

/** How the library lists are shown; kept here so it survives leaving the screen. */
data class SpotifyLibraryView(
    val tab: SpotifyLibraryTab = SpotifyLibraryTab.PLAYLISTS,
    val byName: Boolean = false,
    val descending: Boolean = false,
)

/**
 * Everything read from Spotify in this session, so reopening a screen does not ask again. Emptied
 * whenever the account connects or disconnects; [generation] then changes so screens pick up the
 * new lists. [scope] must be a supervisor scope: a failed page must not cancel the others.
 */
// shortcut: nothing is evicted, add a size bound if memory use of very large libraries becomes a problem.
class SpotifyLibraryStore(
    private val library: SpotifyLibrary,
    connection: SpotifyConnection,
    private val scope: CoroutineScope,
) {
    private val cache = MutableStateFlow(emptyMap<String, Any>())
    private val mutableGeneration = MutableStateFlow(0)

    val generation: StateFlow<Int> = mutableGeneration.asStateFlow()
    val view = MutableStateFlow(SpotifyLibraryView())

    init {
        scope.launch {
            connection.state
                .map { it is SpotifyConnectionState.Connected }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    cache.value = emptyMap()
                    mutableGeneration.update { it + 1 }
                }
        }
    }

    fun playlists(folderUri: String?): SpotifyPager<SpotifyLibraryItem> =
        cached("playlists:${folderUri.orEmpty()}") { SpotifyPager(scope) { library.playlists(folderUri, it) } }

    fun albums(): SpotifyPager<SpotifyAlbum> = cached("albums") { SpotifyPager(scope) { library.albums(it) } }

    fun artists(): SpotifyPager<SpotifyArtist> = cached("artists") { SpotifyPager(scope) { library.artists(it) } }

    fun collection(
        type: SpotifyCollectionType,
        id: String,
    ): SpotifyCollection =
        cached("$type:$id") {
            val info = MutableStateFlow<SpotifyCollectionInfo?>(null)
            SpotifyCollection(info, SpotifyPager(scope) { offset -> page(type, id, offset, info) })
        }

    private suspend fun page(
        type: SpotifyCollectionType,
        id: String,
        offset: Int,
        info: MutableStateFlow<SpotifyCollectionInfo?>,
    ): SpotifyPage<SpotifyTrackItem> =
        when (type) {
            SpotifyCollectionType.LIKED -> {
                library.likedSongs(offset)
            }

            SpotifyCollectionType.PLAYLIST -> {
                val page = library.playlist(id, offset)
                val playlist = page.playlist
                info.value =
                    SpotifyCollectionInfo(
                        title = playlist.name,
                        owner = playlist.ownerName,
                        imageUrl = playlist.images.url(HEADER_IMAGE_SIZE),
                        trackCount = playlist.trackCount ?: page.tracks.total,
                    )
                page.tracks
            }

            SpotifyCollectionType.ALBUM -> {
                val album = library.album(id)
                info.value =
                    SpotifyCollectionInfo(
                        title = album.name,
                        owner = album.artists.joinToString { it.name }.ifEmpty { null },
                        imageUrl = album.images.url(HEADER_IMAGE_SIZE),
                        trackCount = album.tracks.size,
                    )
                album.tracks.asPage()
            }

            SpotifyCollectionType.ARTIST -> {
                val overview = library.artist(id)
                info.value =
                    SpotifyCollectionInfo(
                        title = overview.artist.name,
                        imageUrl = overview.artist.images.url(HEADER_IMAGE_SIZE),
                        albums = overview.albums,
                    )
                overview.topTracks.asPage()
            }
        }

    private fun List<SpotifyTrack>.asPage() = SpotifyPage(map { SpotifyTrackItem(null, it.uri, null, it) }, size, null)

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> cached(
        key: String,
        create: () -> T,
    ): T {
        cache.value[key]?.let { return it as T }
        val created = create()
        return cache.updateAndGet { if (key in it) it else it + (key to created) }.getValue(key) as T
    }

    private companion object {
        const val HEADER_IMAGE_SIZE = 300
    }
}

/** The smallest image at least [minSize] wide, else the largest one; Spotify does not order them reliably. */
fun List<SpotifyImage>.url(minSize: Int): String? =
    (filter { (it.width ?: 0) >= minSize }.minByOrNull { it.width ?: 0 } ?: maxByOrNull { it.width ?: 0 })?.url
