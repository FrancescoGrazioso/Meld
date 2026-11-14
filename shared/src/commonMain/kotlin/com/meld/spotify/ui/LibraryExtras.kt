@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.meld.spotify.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meld.spotify.SpotifyConnectionState
import com.meld.spotify.SpotifyError
import com.meld.spotify.library.LibraryCells
import com.meld.spotify.library.SpotifyInLibrary
import com.meld.spotify.library.SpotifyLibraryEntry
import com.meld.spotify.library.SpotifyLibraryEntryKind
import com.meld.spotify.library.libraryCells
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.import_provider_spotify
import com.metrolist.shared.generated.resources.meld_spotify_library_expired
import com.metrolist.shared.generated.resources.meld_spotify_liked_songs
import com.metrolist.shared.generated.resources.retry
import com.metrolist.shared.model.SortDirection
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.components.ExpressiveFilterChip
import com.metrolist.shared.ui.components.GridCard
import com.metrolist.shared.ui.components.LibraryItemContainer
import com.metrolist.shared.ui.components.ListCard
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.navigation.LocalNavigator
import com.metrolist.shared.ui.navigation.SpotifySettingsScreen
import com.metrolist.shared.ui.screens.LibraryFilter
import com.metrolist.shared.ui.screens.LibrarySortType
import org.jetbrains.compose.resources.stringResource
import org.koin.mp.KoinPlatform

/**
 * Meld's part of the app's library screen: the Spotify filter chip, the Spotify rows among the
 * app's own, and what stands for them while they load or cannot. [None] leaves the screen as it is.
 */
@Stable
class LibraryExtras internal constructor(
    /** Only Spotify rows are wanted: the library shows none of its own items. */
    val spotifyOnly: Boolean = false,
    val chip: (@Composable () -> Unit)? = null,
    val status: (@Composable () -> Unit)? = null,
    private val entries: (query: String) -> List<SpotifyLibraryEntry> = { emptyList() },
) {
    /** [items] as the library sorted them, with the Spotify rows that match [query] placed among them. */
    @Composable
    fun cells(
        items: List<YTItem>,
        sort: LibrarySortType,
        direction: SortDirection,
        query: String,
    ): LibraryCells =
        remember(this, items, sort, direction, query) {
            libraryCells(items, entries(query), sort == LibrarySortType.NAME, direction == SortDirection.DESCENDING, status != null)
        }

    @Composable
    fun Row(
        cells: LibraryCells,
        cell: Int,
        isGridView: Boolean,
    ) {
        cells.entry(cell)?.let { SpotifyLibraryCell(it, isGridView) }
    }

    companion object {
        val None = LibraryExtras()
    }
}

/**
 * The Spotify side of the library for the kind [filter] selects, or [LibraryExtras.None] while no
 * account is connected. The Spotify chip is a filter of its own next to the kind chips, so with
 * both selected the library shows only the Spotify items of that kind. An expired session keeps
 * the chip, so the way back in stays where the content was.
 */
@Composable
fun spotifyLibraryExtras(
    filter: LibraryFilter?,
    onFilterChange: (LibraryFilter?) -> Unit,
): LibraryExtras {
    val library = remember { KoinPlatform.getKoin().getOrNull<SpotifyInLibrary>() } ?: return LibraryExtras.None
    val state by library.state.collectAsState()
    var stored by remember { mutableStateOf(Settings.shared.meldLibrarySpotifyOnly) }
    val setSpotifyOnly = { value: Boolean ->
        stored = value
        Settings.shared.meldLibrarySpotifyOnly = value
    }
    // Podcasts replace the whole library with a screen Spotify has no part in, so choosing them leaves the Spotify filter.
    LaunchedEffect(filter) { if (filter == LibraryFilter.PODCASTS) setSpotifyOnly(false) }
    if (!state.available) return LibraryExtras.None

    val spotifyOnly = stored && filter != LibraryFilter.PODCASTS
    val currentOnFilterChange by rememberUpdatedState(onFilterChange)
    val navigator = LocalNavigator.current
    val likedTitle = stringResource(Res.string.meld_spotify_liked_songs)
    return remember(library, state, filter, spotifyOnly, likedTitle, navigator) {
        val session = state.session
        val error = state.error(filter)
        val openSettings = { navigator.navigate(SpotifySettingsScreen) }
        LibraryExtras(
            spotifyOnly = spotifyOnly,
            chip = {
                SpotifyChip(spotifyOnly) {
                    if (filter == LibraryFilter.PODCASTS) currentOnFilterChange(null)
                    setSpotifyOnly(!spotifyOnly)
                }
            },
            status =
                when {
                    session is SpotifyConnectionState.Expired && spotifyOnly -> {
                        { SpotifySessionGate(session, PaddingValues()) {} }
                    }

                    session is SpotifyConnectionState.Expired && filter != LibraryFilter.TRACKS -> {
                        { SpotifyExpiredRow(openSettings) }
                    }

                    error != null -> {
                        { SpotifyFailureRow(error, library::retry) }
                    }

                    state.loading(filter) -> {
                        { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    }

                    else -> {
                        null
                    }
                },
            entries = { query -> state.entries(filter, spotifyOnly, query, likedTitle) },
        )
    }
}

@Composable
private fun SpotifyChip(
    selected: Boolean,
    onClick: () -> Unit,
) {
    ExpressiveFilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(stringResource(Res.string.import_provider_spotify), style = MaterialTheme.typography.labelLarge, maxLines = 1) },
    )
}

@Composable
private fun SpotifyLibraryCell(
    entry: SpotifyLibraryEntry,
    isGridView: Boolean,
) {
    val navigator = LocalNavigator.current
    val artistId = entry.id.takeIf { entry.kind == SpotifyLibraryEntryKind.ARTIST }
    LibraryItemContainer(
        isGridView = isGridView,
        onMenuClick = null,
        modifier = Modifier.clickable { navigator.navigate(entry.screen()) },
    ) {
        if (isGridView) {
            Box {
                GridCard(
                    title = entry.name,
                    subtitle = entry.subtitle(),
                    thumbnail = entry.imageUrl,
                    icon = entry.kind.icon,
                    artistId = artistId,
                )
                SpotifyBadge(Modifier.align(Alignment.TopEnd).padding(8.dp))
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    ListCard(
                        title = entry.name,
                        subtitle = entry.subtitle(),
                        thumbnail = entry.imageUrl,
                        icon = entry.kind.icon,
                        artistId = artistId,
                    )
                }
                SpotifyBadge(Modifier.padding(horizontal = 8.dp))
            }
        }
    }
}

/** Marks a row as coming from Spotify, the way the library badges its own special collections but quieter. */
@Composable
private fun SpotifyBadge(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            stringResource(Res.string.import_provider_spotify),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun SpotifyExpiredRow(onClick: () -> Unit) {
    LibraryItemContainer(isGridView = false, onMenuClick = null, modifier = Modifier.clickable(onClick = onClick)) {
        ListCard(
            title = stringResource(Res.string.import_provider_spotify),
            subtitle = stringResource(Res.string.meld_spotify_library_expired),
            thumbnail = null,
            icon = MetrolistIcons.Outlined.Warning,
        )
    }
}

@Composable
private fun SpotifyFailureRow(
    error: SpotifyError,
    onRetry: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(error.text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.retry)) }
    }
}
