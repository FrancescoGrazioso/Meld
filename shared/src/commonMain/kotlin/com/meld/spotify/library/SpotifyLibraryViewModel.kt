package com.meld.spotify.library

import com.meld.spotify.SpotifyAlbum
import com.meld.spotify.SpotifyArtist
import com.meld.spotify.SpotifyConnection
import com.meld.spotify.SpotifyConnectionState
import com.meld.spotify.SpotifyLibraryItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SpotifyLibraryEntryKind {
    FOLDER,
    PLAYLIST,
    ALBUM,
    ARTIST,
}

/** A row of the Spotify library. [id] is the folder URI for a folder, else the Spotify id. */
data class SpotifyLibraryEntry(
    val kind: SpotifyLibraryEntryKind,
    val id: String,
    val name: String,
    val detail: String? = null,
    val count: Int? = null,
    val imageUrl: String? = null,
) {
    val key: String get() = "$kind:$id"
}

/** [list] is everything loaded so far in Spotify's order; [entries] is what the search and sort leave of it. */
data class SpotifyLibraryUiState(
    val session: SpotifyConnectionState,
    val view: SpotifyLibraryView = SpotifyLibraryView(),
    val query: String = "",
    val list: SpotifyPagerState<SpotifyLibraryEntry> = SpotifyPagerState(),
    val entries: List<SpotifyLibraryEntry> = emptyList(),
)

/** The Spotify library, or one of its playlist folders when [folderUri] is set. */
@OptIn(ExperimentalCoroutinesApi::class)
class SpotifyLibraryViewModel(
    scope: CoroutineScope,
    private val store: SpotifyLibraryStore,
    connection: SpotifyConnection,
    private val folderUri: String? = null,
) {
    private val query = MutableStateFlow("")
    private val view = if (folderUri == null) store.view else MutableStateFlow(SpotifyLibraryView())
    private val connected = connection.state.map { it is SpotifyConnectionState.Connected }.distinctUntilChanged()

    // Re-emitted with the store's generation so a new sign-in swaps in the new account's lists.
    private val tab = combine(store.generation, view.map { it.tab }.distinctUntilChanged()) { _, tab -> tab }

    val state: StateFlow<SpotifyLibraryUiState> =
        combine(connection.state, view, query, tab.flatMapLatest(::list)) { session, view, query, list ->
            SpotifyLibraryUiState(session, view, query, list, list.items.shown(query, view))
        }.stateIn(scope, SharingStarted.Eagerly, SpotifyLibraryUiState(connection.state.value))

    init {
        scope.launch {
            // The lists are short, so they are read to the end: search and sort then cover the whole library.
            combine(tab, connected) { tab, connected -> tab.takeIf { connected } }
                .collect { it?.let(::pager)?.loadAllInBackground() }
        }
    }

    fun setTab(tab: SpotifyLibraryTab) = view.update { it.copy(tab = tab) }

    fun setSort(
        byName: Boolean,
        descending: Boolean,
    ) = view.update { it.copy(byName = byName, descending = descending) }

    fun setQuery(value: String) {
        query.value = value
    }

    fun retry() = pager(view.value.tab).loadAllInBackground()

    fun refresh() = pager(view.value.tab).refresh(all = true)

    private fun pager(tab: SpotifyLibraryTab): SpotifyPager<*> =
        when (tab) {
            SpotifyLibraryTab.PLAYLISTS -> store.playlists(folderUri)
            SpotifyLibraryTab.ALBUMS -> store.albums()
            SpotifyLibraryTab.ARTISTS -> store.artists()
        }

    private fun list(tab: SpotifyLibraryTab): Flow<SpotifyPagerState<SpotifyLibraryEntry>> =
        when (tab) {
            SpotifyLibraryTab.PLAYLISTS -> store.playlists(folderUri).state.map { it.entries(SpotifyLibraryItem::toEntry) }
            SpotifyLibraryTab.ALBUMS -> store.albums().state.map { it.entries(SpotifyAlbum::toEntry) }
            SpotifyLibraryTab.ARTISTS -> store.artists().state.map { it.entries(SpotifyArtist::toEntry) }
        }
}

// Spotify can repeat an item across pages when the library changes while it is being read.
private fun <T> SpotifyPagerState<T>.entries(transform: (T) -> SpotifyLibraryEntry) =
    map { items -> items.map(transform).distinctBy(SpotifyLibraryEntry::key) }

private fun List<SpotifyLibraryEntry>.shown(
    query: String,
    view: SpotifyLibraryView,
): List<SpotifyLibraryEntry> {
    val term = query.trim()
    val found =
        if (term.isEmpty()) {
            this
        } else {
            filter { it.name.contains(term, ignoreCase = true) || it.detail?.contains(term, ignoreCase = true) == true }
        }
    val sorted = if (view.byName) found.sortedBy { it.name.lowercase() } else found
    return if (view.descending) sorted.asReversed() else sorted
}

private const val ROW_IMAGE_SIZE = 150

private fun SpotifyLibraryItem.toEntry(): SpotifyLibraryEntry =
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

private fun SpotifyAlbum.toEntry() =
    SpotifyLibraryEntry(
        kind = SpotifyLibraryEntryKind.ALBUM,
        id = id,
        name = name,
        detail = artists.joinToString { it.name }.ifEmpty { null },
        imageUrl = images.url(ROW_IMAGE_SIZE),
    )

private fun SpotifyArtist.toEntry() = SpotifyLibraryEntry(SpotifyLibraryEntryKind.ARTIST, id, name, imageUrl = images.url(ROW_IMAGE_SIZE))

/** Shared with the artist page, whose albums are rows of the same kind. */
internal fun SpotifyAlbum.toLibraryEntry(): SpotifyLibraryEntry = toEntry().copy(detail = releaseDate?.take(4))
