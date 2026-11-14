package com.meld.spotify.library

import com.meld.spotify.SpotifyAlbum
import com.meld.spotify.SpotifyArtist
import com.meld.spotify.SpotifyConnection
import com.meld.spotify.SpotifyConnectionState
import com.meld.spotify.SpotifyError
import com.meld.spotify.SpotifyLibraryItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.ui.screens.LibraryFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The Spotify library as the app's own library screen shows it, each list with what has loaded so far. */
data class SpotifyInLibraryState(
    val session: SpotifyConnectionState,
    val playlists: SpotifyPagerState<SpotifyLibraryEntry> = SpotifyPagerState(),
    val albums: SpotifyPagerState<SpotifyLibraryEntry> = SpotifyPagerState(),
    val artists: SpotifyPagerState<SpotifyLibraryEntry> = SpotifyPagerState(),
) {
    val connected: Boolean get() = session is SpotifyConnectionState.Connected

    /** Whether the library offers the Spotify filter; an expired session keeps it as the way back to the sign-in. */
    val available: Boolean get() = connected || session is SpotifyConnectionState.Expired

    private fun lists(filter: LibraryFilter?) =
        when (filter) {
            null -> listOf(playlists, albums, artists)
            LibraryFilter.PLAYLISTS -> listOf(playlists)
            LibraryFilter.ALBUMS -> listOf(albums)
            LibraryFilter.ARTISTS -> listOf(artists)
            LibraryFilter.TRACKS, LibraryFilter.PODCASTS -> emptyList()
        }

    /**
     * The Spotify rows under [filter], in Spotify's order: the liked songs, then playlists and folders,
     * albums and artists. The liked songs sit where the app's own liked songs do (everything and
     * playlists); under tracks they are shown only when [spotifyOnly], as there is nothing else to show.
     */
    fun entries(
        filter: LibraryFilter?,
        spotifyOnly: Boolean,
        query: String,
        likedTitle: String,
    ): List<SpotifyLibraryEntry> {
        if (!connected) return emptyList()
        val showsLiked = filter == null || filter == LibraryFilter.PLAYLISTS || (spotifyOnly && filter == LibraryFilter.TRACKS)
        val liked = listOfNotNull(SpotifyLibraryEntry(SpotifyLibraryEntryKind.LIKED, "", likedTitle).takeIf { showsLiked })
        val all = liked + lists(filter).flatMap { it.items }
        val term = query.trim()
        return if (term.isEmpty()) all else all.filter { it.matches(term) }
    }

    fun loading(filter: LibraryFilter?): Boolean = connected && lists(filter).any { it.loading || (!it.complete && it.error == null) }

    fun error(filter: LibraryFilter?): SpotifyError? = lists(filter).firstNotNullOfOrNull { it.error }.takeIf { connected }
}

/**
 * Feeds the app's library screen: once an account is connected every list is read to the end in
 * the background, so the screen never waits for Spotify and its search and sort cover everything.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpotifyInLibrary(
    scope: CoroutineScope,
    private val store: SpotifyLibraryStore,
    connection: SpotifyConnection,
) {
    // Re-read with the store's generation so a new sign-in swaps in the new account's lists.
    val state: StateFlow<SpotifyInLibraryState> =
        store.generation
            .flatMapLatest {
                combine(
                    connection.state,
                    store.playlists(null).state,
                    store.albums().state,
                    store.artists().state,
                ) { session, playlists, albums, artists ->
                    SpotifyInLibraryState(
                        session,
                        playlists.entries(SpotifyLibraryItem::toEntry),
                        albums.entries(SpotifyAlbum::toEntry),
                        artists.entries(SpotifyArtist::toEntry),
                    )
                }
            }.stateIn(scope, SharingStarted.Eagerly, SpotifyInLibraryState(connection.state.value))

    init {
        val connected = connection.state.map { it is SpotifyConnectionState.Connected }.distinctUntilChanged()
        scope.launch {
            combine(store.generation, connected) { _, isConnected -> isConnected }.collect { if (it) retry() }
        }
    }

    /** Continues every list from where it stopped; also the retry after a failed page. */
    fun retry() = pagers().forEach { it.loadAllInBackground() }

    fun refresh() {
        if (state.value.connected) pagers().forEach { it.refresh(all = true) }
    }

    private fun pagers() = listOf(store.playlists(null), store.albums(), store.artists())
}

/** The cells of the library grid: the app's own items with the Spotify rows placed among them. */
class LibraryCells internal constructor(
    private val cells: List<Any>,
    /** Something other than rows stands for Spotify (progress, a failure, an expired session), so the library is not empty. */
    val hasStatus: Boolean = false,
) {
    val size: Int get() = cells.size
    val isEmpty: Boolean get() = cells.isEmpty() && !hasStatus

    /** The index into the app's own items, or -1 where the cell is a Spotify row. */
    fun index(cell: Int): Int = cells[cell] as? Int ?: -1

    fun entry(cell: Int): SpotifyLibraryEntry? = cells[cell] as? SpotifyLibraryEntry

    /** The grid key of a Spotify row, null for the app's own items. */
    fun key(cell: Int): String? = entry(cell)?.let { "spotify:${it.key}" }
}

/**
 * Places [entries] among [items], which the library has already sorted. Sorted by name, both are
 * merged on the lowercased title, the key the library itself uses. No other sort key exists on a
 * Spotify row (Spotify does not say when an item was added, played or updated), so there the
 * Spotify rows keep Spotify's order and come first, whatever the direction. The liked songs always lead.
 */
fun libraryCells(
    items: List<YTItem>,
    entries: List<SpotifyLibraryEntry>,
    byName: Boolean,
    descending: Boolean,
    hasStatus: Boolean = false,
): LibraryCells {
    val cells = ArrayList<Any>(entries.size + items.size)
    if (!byName) {
        cells.addAll(entries)
        cells.addAll(items.indices)
        return LibraryCells(cells, hasStatus)
    }
    val (liked, rest) = entries.partition { it.kind == SpotifyLibraryEntryKind.LIKED }
    val sorted = if (descending) rest.sortedByDescending { it.name.lowercase() } else rest.sortedBy { it.name.lowercase() }
    cells.addAll(liked)
    var next = 0
    items.forEachIndexed { index, item ->
        val title = item.title.lowercase()
        while (next < sorted.size) {
            val order = sorted[next].name.lowercase().compareTo(title)
            if (if (descending) order <= 0 else order >= 0) break
            cells += sorted[next++]
        }
        cells += index
    }
    while (next < sorted.size) cells += sorted[next++]
    return LibraryCells(cells, hasStatus)
}
