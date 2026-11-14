package com.meld.spotify.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.meld.spotify.SpotifyConnection
import com.meld.spotify.SpotifyConnectionState
import com.meld.spotify.library.SpotifyCollectionType
import com.meld.spotify.library.SpotifyLibraryEntry
import com.meld.spotify.library.SpotifyLibraryEntryKind
import com.meld.spotify.library.SpotifyLibraryTab
import com.meld.spotify.library.SpotifyLibraryViewModel
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.album
import com.metrolist.shared.generated.resources.albums
import com.metrolist.shared.generated.resources.artist
import com.metrolist.shared.generated.resources.artists
import com.metrolist.shared.generated.resources.import_provider_spotify
import com.metrolist.shared.generated.resources.meld_spotify_empty_albums
import com.metrolist.shared.generated.resources.meld_spotify_empty_artists
import com.metrolist.shared.generated.resources.meld_spotify_empty_folder
import com.metrolist.shared.generated.resources.meld_spotify_empty_playlists
import com.metrolist.shared.generated.resources.meld_spotify_folder
import com.metrolist.shared.generated.resources.meld_spotify_library_description
import com.metrolist.shared.generated.resources.meld_spotify_library_expired
import com.metrolist.shared.generated.resources.meld_spotify_liked_songs
import com.metrolist.shared.generated.resources.meld_spotify_liked_songs_description
import com.metrolist.shared.generated.resources.meld_spotify_n_items
import com.metrolist.shared.generated.resources.meld_spotify_order_spotify
import com.metrolist.shared.generated.resources.n_song
import com.metrolist.shared.generated.resources.playlist
import com.metrolist.shared.generated.resources.playlists
import com.metrolist.shared.generated.resources.search_library
import com.metrolist.shared.generated.resources.sort_by_name
import com.metrolist.shared.model.PlaylistSongSort
import com.metrolist.shared.model.SortDirection
import com.metrolist.shared.ui.components.ChipShelf
import com.metrolist.shared.ui.components.InlineSearchRow
import com.metrolist.shared.ui.components.ListCard
import com.metrolist.shared.ui.components.PlaylistOrderControls
import com.metrolist.shared.ui.components.RefreshIndicator
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.navigation.LocalNavigator
import com.metrolist.shared.ui.navigation.NavigationBackHandler
import com.metrolist.shared.ui.navigation.Screen
import com.metrolist.shared.ui.navigation.SpotifyLibraryScreen
import com.metrolist.shared.ui.navigation.SpotifyTracksScreen
import com.metrolist.shared.ui.screens.playlistOrderControlsText
import com.metrolist.shared.ui.utils.DragScrollableColumn
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.mp.KoinPlatform

/**
 * The row that leads from the app's library to the Spotify one, or null while no account is
 * connected. An expired session keeps the row, so the way back in stays where the content was.
 */
@Composable
fun spotifyLibraryEntry(): (@Composable () -> Unit)? {
    val connection = remember { KoinPlatform.getKoin().getOrNull<SpotifyConnection>() } ?: return null
    val state by connection.state.collectAsState()
    val expired = state is SpotifyConnectionState.Expired
    if (state !is SpotifyConnectionState.Connected && !expired) return null
    val navigator = LocalNavigator.current
    return {
        Surface(
            onClick = { navigator.navigate(SpotifyLibraryScreen()) },
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(Modifier.padding(12.dp)) {
                ListCard(
                    title = stringResource(Res.string.import_provider_spotify),
                    subtitle =
                        stringResource(
                            if (expired) Res.string.meld_spotify_library_expired else Res.string.meld_spotify_library_description,
                        ),
                    thumbnail = null,
                    icon = if (expired) MetrolistIcons.Outlined.Warning else MetrolistIcons.Outlined.LibraryMusic,
                )
            }
        }
    }
}

/** The Spotify library ([isFolder] false) or one of its playlist folders. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifyLibraryContent(
    viewModel: SpotifyLibraryViewModel,
    isFolder: Boolean,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val navigator = LocalNavigator.current
    SpotifySessionGate(state.session, contentPadding) {
        var searchOpen by rememberSaveable { mutableStateOf(false) }
        val toggleSearch = {
            if (searchOpen) viewModel.setQuery("")
            searchOpen = !searchOpen
        }
        NavigationBackHandler(enabled = searchOpen, onBack = toggleSearch)
        val refreshState = rememberPullToRefreshState()
        val likedTitle = stringResource(Res.string.meld_spotify_liked_songs)
        val showLiked =
            !isFolder && state.view.tab == SpotifyLibraryTab.PLAYLISTS && likedTitle.contains(state.query.trim(), ignoreCase = true)
        PullToRefreshBox(
            isRefreshing = state.list.refreshing,
            onRefresh = viewModel::refresh,
            state = refreshState,
            indicator = {
                RefreshIndicator(
                    state = refreshState,
                    isRefreshing = state.list.refreshing,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = contentPadding.calculateTopPadding()),
                )
            },
            modifier = modifier.fillMaxSize(),
        ) {
            DragScrollableColumn(modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
                if (!isFolder) {
                    item(key = "tabs") {
                        ChipShelf(
                            options = SpotifyLibraryTab.entries,
                            selectedOption = state.view.tab,
                            onSelectionChange = { it?.let(viewModel::setTab) },
                            label = { stringResource(it.label) },
                            selectionRequired = true,
                        )
                    }
                }
                item(key = "controls") {
                    InlineSearchRow(
                        isSearchOpen = searchOpen,
                        query = state.query,
                        onQueryChange = viewModel::setQuery,
                        onClose = toggleSearch,
                        onSearchToggle = toggleSearch,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        PlaylistOrderControls(
                            text =
                                playlistOrderControlsText().let { base ->
                                    base.copy(
                                        sortLabels =
                                            mapOf(
                                                PlaylistSongSort.ORIGINAL to stringResource(Res.string.meld_spotify_order_spotify),
                                                PlaylistSongSort.TITLE to stringResource(Res.string.sort_by_name),
                                            ),
                                    )
                                },
                            sort = if (state.view.byName) PlaylistSongSort.TITLE else PlaylistSongSort.ORIGINAL,
                            sortDirection = if (state.view.descending) SortDirection.DESCENDING else SortDirection.ASCENDING,
                            reorderMode = false,
                            canReorder = false,
                            onSortChange = { viewModel.setSort(it == PlaylistSongSort.TITLE, state.view.descending) },
                            onSortDirectionChange = { viewModel.setSort(state.view.byName, it == SortDirection.DESCENDING) },
                            onReorderModeChange = {},
                        )
                    }
                }
                if (showLiked) {
                    item(key = "liked") {
                        SpotifyLibraryRow(
                            title = likedTitle,
                            subtitle = stringResource(Res.string.meld_spotify_liked_songs_description),
                            imageUrl = null,
                            icon = MetrolistIcons.Default.Favorite,
                            onClick = { navigator.navigate(SpotifyTracksScreen(SpotifyCollectionType.LIKED)) },
                        )
                    }
                }
                items(state.entries, key = SpotifyLibraryEntry::key, contentType = { "entry" }) { entry ->
                    SpotifyLibraryRow(
                        title = entry.name,
                        subtitle = entry.subtitle(),
                        imageUrl = entry.imageUrl,
                        icon = entry.kind.icon,
                        artistId = entry.id.takeIf { entry.kind == SpotifyLibraryEntryKind.ARTIST },
                        onClick = { navigator.navigate(entry.screen()) },
                        modifier = Modifier.animateItem(),
                    )
                }
                item(key = "status") {
                    SpotifyListStatus(
                        state = state.list,
                        // The liked songs row is content too: with it the list is neither empty nor without results.
                        shown = state.entries.size + if (showLiked) 1 else 0,
                        empty = stringResource(if (isFolder) Res.string.meld_spotify_empty_folder else state.view.tab.empty),
                        onRetry = viewModel::retry,
                    )
                }
            }
        }
    }
}

@Composable
internal fun SpotifyLibraryRow(
    title: String,
    subtitle: String,
    imageUrl: String?,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    artistId: String? = null,
) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        ListCard(title = title, subtitle = subtitle, thumbnail = imageUrl, icon = icon, artistId = artistId)
    }
}

private val SpotifyLibraryTab.label
    get() =
        when (this) {
            SpotifyLibraryTab.PLAYLISTS -> Res.string.playlists
            SpotifyLibraryTab.ALBUMS -> Res.string.albums
            SpotifyLibraryTab.ARTISTS -> Res.string.artists
        }

private val SpotifyLibraryTab.empty
    get() =
        when (this) {
            SpotifyLibraryTab.PLAYLISTS -> Res.string.meld_spotify_empty_playlists
            SpotifyLibraryTab.ALBUMS -> Res.string.meld_spotify_empty_albums
            SpotifyLibraryTab.ARTISTS -> Res.string.meld_spotify_empty_artists
        }

private val SpotifyLibraryEntryKind.icon
    get() =
        when (this) {
            SpotifyLibraryEntryKind.FOLDER -> MetrolistIcons.Outlined.LibraryMusic
            SpotifyLibraryEntryKind.PLAYLIST -> MetrolistIcons.AutoMirrored.Outlined.QueueMusic
            SpotifyLibraryEntryKind.ALBUM -> MetrolistIcons.Outlined.Album
            SpotifyLibraryEntryKind.ARTIST -> MetrolistIcons.Outlined.Person
        }

@Composable
internal fun SpotifyLibraryEntry.subtitle(): String {
    val kind =
        stringResource(
            when (kind) {
                SpotifyLibraryEntryKind.FOLDER -> Res.string.meld_spotify_folder
                SpotifyLibraryEntryKind.PLAYLIST -> Res.string.playlist
                SpotifyLibraryEntryKind.ALBUM -> Res.string.album
                SpotifyLibraryEntryKind.ARTIST -> Res.string.artist
            },
        )
    val amount =
        count?.let {
            pluralStringResource(
                if (this.kind == SpotifyLibraryEntryKind.FOLDER) Res.plurals.meld_spotify_n_items else Res.plurals.n_song,
                it,
                it,
            )
        }
    return listOfNotNull(kind, detail, amount).joinToString(" • ")
}

internal fun SpotifyLibraryEntry.screen(): Screen =
    when (kind) {
        SpotifyLibraryEntryKind.FOLDER -> SpotifyLibraryScreen(id, name)
        SpotifyLibraryEntryKind.PLAYLIST -> SpotifyTracksScreen(SpotifyCollectionType.PLAYLIST, id, name)
        SpotifyLibraryEntryKind.ALBUM -> SpotifyTracksScreen(SpotifyCollectionType.ALBUM, id, name)
        SpotifyLibraryEntryKind.ARTIST -> SpotifyTracksScreen(SpotifyCollectionType.ARTIST, id, name)
    }
