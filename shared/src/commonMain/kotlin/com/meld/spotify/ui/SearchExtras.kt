package com.meld.spotify.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.meld.spotify.SpotifyConnection
import com.meld.spotify.SpotifyConnectionState
import com.meld.spotify.SpotifyTrack
import com.meld.spotify.SpotifyTrackItem
import com.meld.spotify.library.SpotifyInSearch
import com.meld.spotify.library.SpotifyLibraryEntry
import com.meld.spotify.library.SpotifyPlaylistPicker
import com.meld.spotify.library.SpotifySearchKind
import com.meld.spotify.library.SpotifySearchState
import com.meld.spotify.likes.SpotifyLikeSync
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.albums
import com.metrolist.shared.generated.resources.artists
import com.metrolist.shared.generated.resources.filter_all
import com.metrolist.shared.generated.resources.playlists
import com.metrolist.shared.generated.resources.retry
import com.metrolist.shared.generated.resources.search_no_results
import com.metrolist.shared.generated.resources.songs
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.components.ChipShelf
import com.metrolist.shared.ui.components.ContainedLoadingIndicator
import com.metrolist.shared.ui.components.SectionTitle
import com.metrolist.shared.ui.navigation.LocalNavigator
import com.metrolist.shared.ui.navigation.Screen
import com.metrolist.shared.ui.utils.DragScrollableColumn
import com.metrolist.shared.ui.utils.desktopArrowScroll
import org.jetbrains.compose.resources.stringResource
import org.koin.core.Koin
import org.koin.mp.KoinPlatform

/**
 * Meld's part of the app's search results: the Spotify chip in front of the app's own filter chips,
 * and, while it is selected, the Spotify results in place of the app's. [None] leaves the screen as it is.
 */
@Stable
class SearchExtras internal constructor(
    val chip: (@Composable () -> Unit)? = null,
    private val results: (@Composable (LazyListState, Modifier, Dp, Boolean) -> Unit)? = null,
) {
    /** The Spotify chip is selected: [Results] is the whole result list. */
    val active: Boolean get() = results != null

    @Composable
    fun Results(
        listState: LazyListState,
        modifier: Modifier,
        bottomContentPadding: Dp,
        isWide: Boolean,
    ) {
        results?.invoke(listState, modifier, bottomContentPadding, isWide)
    }

    companion object {
        val None = SearchExtras()
    }
}

/**
 * The Spotify side of the search results for [query], the text the user submitted (empty while
 * they are still typing), or [SearchExtras.None] unless an account is connected. Spotify is asked
 * only while its chip is selected; the choice is kept, as the library's Spotify chip is. The text
 * goes nowhere else from here: the app's own search and history see what they always did.
 *
 * [onOpen] opens a Spotify route; a host that provides upstream's [LocalNavigator] leaves it out.
 */
@Composable
fun spotifySearchExtras(
    query: String,
    onOpen: ((Screen) -> Unit)? = null,
): SearchExtras {
    val koin = remember { KoinPlatform.getKoinOrNull() }
    val search = remember { koin?.getOrNull<SpotifyInSearch>() } ?: return SearchExtras.None
    val session = remember { koin?.getOrNull<SpotifyConnection>() }?.state?.collectAsState()
    if (session?.value !is SpotifyConnectionState.Connected) return SearchExtras.None

    val active = Settings.shared.meldSearchSpotify
    val open by rememberUpdatedState(onOpen ?: LocalNavigator.current::navigate)
    LaunchedEffect(search, active, query) { if (active) search.search(query) }
    return remember(search, koin, active) {
        val chip = @Composable { SpotifyChip(active) { Settings.shared.meldSearchSpotify = !active } }
        SearchExtras(
            chip = chip,
            results =
                if (active) {
                    { listState, modifier, bottom, _ -> SpotifySearchResults(search, koin, listState, modifier, bottom, chip) { open(it) } }
                } else {
                    null
                },
        )
    }
}

private val SpotifySearchKind.label
    get() =
        when (this) {
            SpotifySearchKind.TRACKS -> Res.string.songs
            SpotifySearchKind.ARTISTS -> Res.string.artists
            SpotifySearchKind.ALBUMS -> Res.string.albums
            SpotifySearchKind.PLAYLISTS -> Res.string.playlists
        }

// Under "All" each kind shows its first results; its chip or its title shows all of them.
private const val PREVIEW_SIZE = 4

@Composable
private fun SpotifySearchResults(
    search: SpotifyInSearch,
    koin: Koin?,
    listState: LazyListState,
    modifier: Modifier,
    bottomContentPadding: Dp,
    chip: @Composable () -> Unit,
    onOpen: (Screen) -> Unit,
) {
    val state by search.state.collectAsState()
    var kind by rememberSaveable { mutableStateOf<SpotifySearchKind?>(null) }
    val picker = remember { koin?.getOrNull<SpotifyPlaylistPicker>() }
    val likedIds = remember { koin?.getOrNull<SpotifyLikeSync>() }?.likedIds?.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val leave = {
        keyboard?.hide()
        focus.clearFocus()
    }

    LaunchedEffect(kind, state) {
        val shown = kind ?: return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.totalItemsCount > 0 && !listState.canScrollForward }.collect { atEnd ->
            if (atEnd) search.loadMore(shown)
        }
    }

    DragScrollableColumn(
        state = listState,
        modifier = modifier.fillMaxWidth().desktopArrowScroll(listState),
        contentPadding = PaddingValues(bottom = 24.dp + bottomContentPadding),
    ) {
        item(key = "spotify_chips") {
            SearchFrame(Modifier.padding(top = 12.dp)) {
                ChipShelf(
                    options = SpotifySearchKind.entries,
                    selectedOption = kind,
                    onSelectionChange = { kind = it },
                    label = { stringResource(it.label) },
                    allLabel = stringResource(Res.string.filter_all),
                    leadingContent = chip,
                )
            }
        }
        val error = state.error
        val kinds = kind?.let(::listOf) ?: SpotifySearchKind.entries
        when {
            state.searching -> {
                item(key = "spotify_searching") {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { ContainedLoadingIndicator() }
                }
            }

            error != null && state.isEmpty -> {
                item(key = "spotify_failed") {
                    SpotifyNotice(stringResource(error.text()), stringResource(Res.string.retry), { search.retry(kind) }, isError = true)
                }
            }

            kinds.all { state.count(it) == 0 } -> {
                item(key = "spotify_no_results") { SpotifyNotice(stringResource(Res.string.search_no_results), null, {}) }
            }

            else -> {
                kinds.forEach { shown ->
                    val limit = if (kind == null) PREVIEW_SIZE else Int.MAX_VALUE
                    if (kind == null && state.count(shown) > 0) {
                        item(key = "spotify_title_$shown") {
                            SearchFrame {
                                SectionTitle(
                                    title = stringResource(shown.label),
                                    modifier = Modifier.padding(horizontal = 16.dp).padding(top = 12.dp),
                                    onClick = { kind = shown },
                                )
                            }
                        }
                    }
                    if (shown == SpotifySearchKind.TRACKS) {
                        val tracks = state.tracks.take(limit)
                        itemsIndexed(tracks, key = { _, track -> "spotify_track_${track.id}" }, contentType = { _, _ -> "track" }) { index, track ->
                            SearchFrame {
                                SpotifyTrackRow(
                                    item = SpotifyTrackItem(null, track.uri, null, track),
                                    index = index,
                                    count = tracks.size,
                                    numbered = false,
                                    onClick =
                                        if (search.canPlay) {
                                            {
                                                leave()
                                                search.play(tracks, index)
                                            }
                                        } else {
                                            null
                                        },
                                    onPlayNext = if (search.canPlay) search::playNext else null,
                                    onAddToQueue = if (search.canPlay) search::addToQueue else null,
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    liked = track.id in likedIds?.value.orEmpty(),
                                    onAddToPlaylist = picker?.let { { chosen: SpotifyTrack -> it.open(chosen) } },
                                )
                            }
                        }
                    } else {
                        entryRows(state.entries(shown).take(limit)) {
                            leave()
                            onOpen(it.screen())
                        }
                    }
                }
                if (state.loadingMore) {
                    item(key = "spotify_loading_more") {
                        Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) { ContainedLoadingIndicator() }
                    }
                }
                if (error != null) {
                    item(key = "spotify_more_failed") { SearchFrame(Modifier.padding(horizontal = 16.dp)) { SpotifyFailureRow(error) { search.retry(kind) } } }
                }
            }
        }
    }
}

private fun SpotifySearchState.count(kind: SpotifySearchKind): Int = if (kind == SpotifySearchKind.TRACKS) tracks.size else entries(kind).size

private fun LazyListScope.entryRows(
    entries: List<SpotifyLibraryEntry>,
    onClick: (SpotifyLibraryEntry) -> Unit,
) {
    items(entries, key = { "spotify_${it.key}" }, contentType = { "entry" }) { entry ->
        SearchFrame {
            SpotifyLibraryRow(
                title = entry.name,
                subtitle = entry.subtitle(),
                imageUrl = entry.imageUrl,
                icon = entry.kind.icon,
                onClick = { onClick(entry) },
            )
        }
    }
}

/** Centres a result row the way the app's own results are. */
@Composable
private fun SearchFrame(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = 1400.dp).fillMaxWidth()) { content() }
    }
}
