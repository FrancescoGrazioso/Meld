package com.meld.spotify.library

import com.meld.spotify.SpotifyAlbum
import com.meld.spotify.SpotifyArtist
import com.meld.spotify.SpotifyConnection
import com.meld.spotify.SpotifyConnectionState
import com.meld.spotify.SpotifyLibraryItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class SpotifyLibraryEntryKind {
    LIKED,
    FOLDER,
    PLAYLIST,
    ALBUM,
    ARTIST,
}

/** A row of the Spotify library. [id] is the folder URI for a folder and empty for the liked songs, else the Spotify id. */
data class SpotifyLibraryEntry(
    val kind: SpotifyLibraryEntryKind,
    val id: String,
    val name: String,
    val detail: String? = null,
    val count: Int? = null,
    val imageUrl: String? = null,
) {
    val key: String get() = "$kind:$id"

    fun matches(term: String): Boolean = name.contains(term, ignoreCase = true) || detail?.contains(term, ignoreCase = true) == true
}

/** [list] is everything loaded so far in Spotify's order; [entries] is what the search and sort leave of it. */
data class SpotifyFolderUiState(
    val session: SpotifyConnectionState,
    val byName: Boolean = false,
    val descending: Boolean = false,
    val query: String = "",
    val list: SpotifyPagerState<SpotifyLibraryEntry> = SpotifyPagerState(),
    val entries: List<SpotifyLibraryEntry> = emptyList(),
)

/** One playlist folder of the Spotify library. */
@OptIn(ExperimentalCoroutinesApi::class)
class SpotifyFolderViewModel(
    scope: CoroutineScope,
    private val store: SpotifyLibraryStore,
    connection: SpotifyConnection,
    private val folderUri: String,
) {
    private val query = MutableStateFlow("")
    private val order = MutableStateFlow(false to false)
    private val connected = connection.state.map { it is SpotifyConnectionState.Connected }.distinctUntilChanged()
    private val pager get() = store.playlists(folderUri)

    // Re-read with the store's generation so a new sign-in swaps in the new account's list.
    private val list = store.generation.flatMapLatest { pager.state.map { it.entries(SpotifyLibraryItem::toEntry) } }

    val state: StateFlow<SpotifyFolderUiState> =
        combine(connection.state, order, query, list) { session, (byName, descending), query, list ->
            SpotifyFolderUiState(session, byName, descending, query, list, list.items.shown(query, byName, descending))
        }.stateIn(scope, SharingStarted.Eagerly, SpotifyFolderUiState(connection.state.value))

    init {
        scope.launch {
            // The list is short, so it is read to the end: search and sort then cover the whole folder.
            combine(store.generation, connected) { _, connected -> connected }
                .collect { if (it) pager.loadAllInBackground() }
        }
    }

    fun setSort(
        byName: Boolean,
        descending: Boolean,
    ) {
        order.value = byName to descending
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun retry() = pager.loadAllInBackground()

    fun refresh() = pager.refresh(all = true)
}

// Spotify can repeat an item across pages when the library changes while it is being read.
internal fun <T> SpotifyPagerState<T>.entries(transform: (T) -> SpotifyLibraryEntry) =
    map { items -> items.map(transform).distinctBy(SpotifyLibraryEntry::key) }

private fun List<SpotifyLibraryEntry>.shown(
    query: String,
    byName: Boolean,
    descending: Boolean,
): List<SpotifyLibraryEntry> {
    val term = query.trim()
    val found = if (term.isEmpty()) this else filter { it.matches(term) }
    val sorted = if (byName) found.sortedBy { it.name.lowercase() } else found
    return if (descending) sorted.asReversed() else sorted
}

private const val ROW_IMAGE_SIZE = 150

internal fun SpotifyLibraryItem.toEntry(): SpotifyLibraryEntry =
    when (this) {
        is SpotifyLibraryItem.Folder -> {
            SpotifyLibraryEntry(SpotifyLibraryEntryKind.FOLDER, folder.uri, folder.name, count = folder.childCount)
        }

        is SpotifyLibraryItem.Playlist -> {
            SpotifyLibraryEntry(
                kind = SpotifyLibraryEntryKind.PLAYLIST,
                id = playlist.id,
                name = playlist.name,
                detail = playlist.ownerName,
                count = playlist.trackCount,
                imageUrl = playlist.images.url(ROW_IMAGE_SIZE),
            )
        }
    }

internal fun SpotifyAlbum.toEntry() =
    SpotifyLibraryEntry(
        kind = SpotifyLibraryEntryKind.ALBUM,
        id = id,
        name = name,
        detail = artists.joinToString { it.name }.ifEmpty { null },
        imageUrl = images.url(ROW_IMAGE_SIZE),
    )

internal fun SpotifyArtist.toEntry() = SpotifyLibraryEntry(SpotifyLibraryEntryKind.ARTIST, id, name, imageUrl = images.url(ROW_IMAGE_SIZE))

/** Shared with the artist page, whose albums are rows of the same kind. */
internal fun SpotifyAlbum.toLibraryEntry(): SpotifyLibraryEntry = toEntry().copy(detail = releaseDate?.take(4))
