@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.meld.spotify.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meld.spotify.library.SpotifyCollectionType
import com.meld.spotify.library.SpotifyTracksViewModel
import com.meld.spotify.library.playableTrack
import com.meld.spotify.library.toLibraryEntry
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.albums
import com.metrolist.shared.generated.resources.import_provider_spotify
import com.metrolist.shared.generated.resources.meld_spotify_empty_tracks
import com.metrolist.shared.generated.resources.meld_spotify_liked_songs
import com.metrolist.shared.generated.resources.meld_spotify_popular
import com.metrolist.shared.generated.resources.meld_spotify_preparing_shuffle
import com.metrolist.shared.generated.resources.n_song
import com.metrolist.shared.generated.resources.play
import com.metrolist.shared.generated.resources.shuffle
import com.metrolist.shared.ui.components.Artwork
import com.metrolist.shared.ui.components.CollectionHeader
import com.metrolist.shared.ui.components.CollectionHeaderHorizontalPadding
import com.metrolist.shared.ui.components.HeaderPlaybackButtons
import com.metrolist.shared.ui.components.RefreshIndicator
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.navigation.LocalNavigator
import com.metrolist.shared.ui.utils.DragScrollableColumn
import com.metrolist.shared.ui.utils.artistArtworkShape
import kotlinx.coroutines.flow.first
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** How many rows before the end of the list the next page is asked for. */
private const val LOAD_AHEAD = 15

/**
 * A Spotify playlist, the liked songs, an album or an artist. [titleHint] is the name the
 * previous screen already knew, shown until the list has loaded.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifyTracksContent(
    viewModel: SpotifyTracksViewModel,
    type: SpotifyCollectionType,
    id: String,
    titleHint: String?,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val navigator = LocalNavigator.current
    SpotifySessionGate(state.session, contentPadding) {
        val info = state.info
        val tracks = state.tracks
        val likedTitle = stringResource(Res.string.meld_spotify_liked_songs)
        val title = info?.title ?: titleHint ?: likedTitle.takeIf { type == SpotifyCollectionType.LIKED }.orEmpty()
        val listState = rememberLazyListState()
        // Restarted after every page so that a list still near its end asks for the next one; a failed page waits for Retry.
        LaunchedEffect(listState, tracks.items.size, tracks.complete, tracks.error) {
            if (tracks.items.isEmpty() || tracks.complete || tracks.error != null) return@LaunchedEffect
            snapshotFlow {
                val layout = listState.layoutInfo
                (layout.visibleItemsInfo.lastOrNull()?.index ?: 0) >= layout.totalItemsCount - LOAD_AHEAD
            }.first { it }
            viewModel.loadMore()
        }
        val refreshState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = tracks.refreshing,
            onRefresh = viewModel::refresh,
            state = refreshState,
            indicator = {
                RefreshIndicator(
                    state = refreshState,
                    isRefreshing = tracks.refreshing,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = contentPadding.calculateTopPadding()),
                )
            },
            modifier = modifier.fillMaxSize(),
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val isMedium = maxWidth >= 600.dp
                val itemModifier = Modifier.widthIn(max = 1400.dp).fillMaxWidth()
                val canPlay = viewModel.canPlay
                DragScrollableColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = contentPadding,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    item(key = "header") {
                        CollectionHeader(
                            isWide = false,
                            isMedium = isMedium,
                            modifier = itemModifier,
                            artwork = { artworkModifier, cornerRadius ->
                                Artwork(
                                    thumbnail = info?.imageUrl,
                                    icon = type.icon,
                                    modifier = artworkModifier,
                                    iconSize = 72.dp,
                                    cornerRadius = cornerRadius,
                                    shape =
                                        if (type == SpotifyCollectionType.ARTIST) {
                                            artistArtworkShape(id)
                                        } else {
                                            RoundedCornerShape(cornerRadius)
                                        },
                                )
                            },
                            actions =
                                if (canPlay && tracks.items.any { it.playableTrack != null }) {
                                    {
                                        HeaderPlaybackButtons(
                                            onPlayAll = { viewModel.play(title = title) },
                                            onShuffle = { viewModel.shuffle(title) },
                                            playLabel = stringResource(Res.string.play),
                                            shuffleLabel = stringResource(Res.string.shuffle),
                                            buttonHeight = 40.dp,
                                            modifier = Modifier.fillMaxWidth(),
                                            enabled = !state.preparingShuffle,
                                        )
                                    }
                                } else {
                                    null
                                },
                        ) {
                            Text(title, style = MaterialTheme.typography.headlineMediumEmphasized)
                            val count = (info?.trackCount ?: tracks.total).takeIf { type != SpotifyCollectionType.ARTIST }
                            Text(
                                text =
                                    listOfNotNull(
                                        stringResource(Res.string.import_provider_spotify),
                                        info?.owner,
                                        count?.let { pluralStringResource(Res.plurals.n_song, it, it) },
                                    ).joinToString(" • "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (state.preparingShuffle) {
                                Text(
                                    text = stringResource(Res.string.meld_spotify_preparing_shuffle),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (type == SpotifyCollectionType.ARTIST && tracks.items.isNotEmpty()) {
                        item(key = "popular") { SectionTitle(stringResource(Res.string.meld_spotify_popular), itemModifier) }
                    }
                    // Keyed by position: the same song can be in a playlist twice, and entries without a track have no id.
                    itemsIndexed(tracks.items, key = { index, _ -> index }, contentType = { _, _ -> "track" }) { index, item ->
                        SpotifyTrackRow(
                            item = item,
                            index = index,
                            count = tracks.items.size,
                            numbered = type == SpotifyCollectionType.ALBUM,
                            onClick = if (canPlay) ({ viewModel.play(index, title) }) else null,
                            onPlayNext = if (canPlay) viewModel::playNext else null,
                            onAddToQueue = if (canPlay) viewModel::addToQueue else null,
                            modifier = itemModifier.padding(horizontal = CollectionHeaderHorizontalPadding),
                        )
                    }
                    item(key = "status") {
                        SpotifyListStatus(
                            state = tracks,
                            shown = tracks.items.size,
                            empty = stringResource(Res.string.meld_spotify_empty_tracks),
                            onRetry = viewModel::loadMore,
                            modifier = itemModifier,
                        )
                    }
                    val albums = info?.albums.orEmpty()
                    if (albums.isNotEmpty()) {
                        item(key = "albums") { SectionTitle(stringResource(Res.string.albums), itemModifier) }
                        items(albums, key = { "album:${it.id}" }, contentType = { "album" }) { album ->
                            val entry = album.toLibraryEntry()
                            SpotifyLibraryRow(
                                title = entry.name,
                                subtitle = entry.subtitle(),
                                imageUrl = entry.imageUrl,
                                icon = MetrolistIcons.Outlined.Album,
                                onClick = { navigator.navigate(entry.screen()) },
                                modifier = itemModifier,
                            )
                        }
                    }
                    item(key = "end") { Spacer(Modifier.height(32.dp)) }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(
    text: String,
    modifier: Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLargeEmphasized,
        modifier = modifier.padding(horizontal = CollectionHeaderHorizontalPadding, vertical = 12.dp),
    )
}

private val SpotifyCollectionType.icon
    get() =
        when (this) {
            SpotifyCollectionType.LIKED -> MetrolistIcons.Default.Favorite
            SpotifyCollectionType.PLAYLIST -> MetrolistIcons.AutoMirrored.Outlined.QueueMusic
            SpotifyCollectionType.ALBUM -> MetrolistIcons.Outlined.Album
            SpotifyCollectionType.ARTIST -> MetrolistIcons.Outlined.Person
        }
