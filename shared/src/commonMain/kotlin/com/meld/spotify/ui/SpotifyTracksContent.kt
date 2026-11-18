@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.meld.spotify.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meld.spotify.SpotifyError
import com.meld.spotify.SpotifyTrack
import com.meld.spotify.SpotifyTrackItem
import com.meld.spotify.library.SpotifyCollectionType
import com.meld.spotify.library.SpotifyPlaylistPicker
import com.meld.spotify.library.SpotifyTracksViewModel
import com.meld.spotify.library.playableTrack
import com.meld.spotify.library.toLibraryEntry
import com.meld.spotify.likes.SpotifyLikeSync
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.albums
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.description
import com.metrolist.shared.generated.resources.dismiss
import com.metrolist.shared.generated.resources.edit_playlist
import com.metrolist.shared.generated.resources.import_provider_spotify
import com.metrolist.shared.generated.resources.meld_spotify_edit_failed
import com.metrolist.shared.generated.resources.meld_spotify_empty_tracks
import com.metrolist.shared.generated.resources.meld_spotify_liked_songs
import com.metrolist.shared.generated.resources.meld_spotify_popular
import com.metrolist.shared.generated.resources.meld_spotify_preparing_shuffle
import com.metrolist.shared.generated.resources.meld_spotify_reorder_loading
import com.metrolist.shared.generated.resources.n_song
import com.metrolist.shared.generated.resources.play
import com.metrolist.shared.generated.resources.playlist_name
import com.metrolist.shared.generated.resources.playlist_reorder
import com.metrolist.shared.generated.resources.retry
import com.metrolist.shared.generated.resources.save
import com.metrolist.shared.generated.resources.shuffle
import com.metrolist.shared.ui.components.AlertDialog
import com.metrolist.shared.ui.components.Artwork
import com.metrolist.shared.ui.components.CollectionHeader
import com.metrolist.shared.ui.components.CollectionHeaderHorizontalPadding
import com.metrolist.shared.ui.components.HeaderPlaybackButtons
import com.metrolist.shared.ui.components.RefreshIndicator
import com.metrolist.shared.ui.components.ReorderMenu
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.navigation.Screen
import com.metrolist.shared.ui.navigation.SpotifySettingsScreen
import com.metrolist.shared.ui.utils.DragScrollableColumn
import com.metrolist.shared.ui.utils.artistArtworkShape
import kotlinx.coroutines.flow.first
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.mp.KoinPlatform
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** How many rows before the end of the list the next page is asked for. */
private const val LOAD_AHEAD = 15

/**
 * A Spotify playlist, the liked songs, an album or an artist. [titleHint] is the name the
 * previous screen already knew, shown until the list has loaded.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SpotifyTracksContent(
    viewModel: SpotifyTracksViewModel,
    type: SpotifyCollectionType,
    id: String,
    titleHint: String?,
    contentPadding: PaddingValues,
    onOpen: (Screen) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val likeSync = remember { KoinPlatform.getKoinOrNull()?.getOrNull<SpotifyLikeSync>() }
    val likedIds = likeSync?.likedIds?.collectAsState()
    SpotifySessionGate(state.session, contentPadding, { onOpen(SpotifySettingsScreen) }) {
        val info = state.info
        val tracks = state.tracks
        val likedTitle = stringResource(Res.string.meld_spotify_liked_songs)
        val title = info?.title ?: titleHint ?: likedTitle.takeIf { type == SpotifyCollectionType.LIKED }.orEmpty()
        val listState = rememberLazyListState()
        val picker = remember { KoinPlatform.getKoinOrNull()?.getOrNull<SpotifyPlaylistPicker>() }
        var editingDetails by remember { mutableStateOf(false) }
        var reorderMode by rememberSaveable { mutableStateOf(false) }
        // Positions mean nothing to Spotify until the whole playlist is loaded, so that comes first.
        val reordering = reorderMode && state.canEdit && tracks.complete
        LaunchedEffect(reorderMode, state.canEdit, tracks.complete, tracks.error) {
            if (reorderMode && state.canEdit && !tracks.complete && tracks.error == null) viewModel.loadAll()
        }
        // The order while a row is being dragged. Spotify is told once, when the row is dropped; the list it then shows takes over.
        var dragged by remember { mutableStateOf<List<SpotifyTrackItem>?>(null) }
        var draggedUid by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(tracks.items) { dragged = null }
        val items = dragged ?: tracks.items
        val keys = remember(items) { items.rowKeys() }

        fun moveBefore(
            item: SpotifyTrackItem,
            before: SpotifyTrackItem?,
        ) {
            val uid = item.uid
            // An entry without a uid cannot be named to Spotify, neither as what moves nor as where to.
            if (uid == null || (before != null && before.uid == null) || !viewModel.move(uid, before?.uid)) dragged = null
        }
        val reorderState =
            rememberReorderableLazyListState(listState) { from, to ->
                val fromIndex = keys.indexOf(from.key)
                val toIndex = keys.indexOf(to.key)
                if (fromIndex >= 0 && toIndex >= 0) dragged = items.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
            }
        LaunchedEffect(reorderState.isAnyItemDragging) {
            val uid = draggedUid
            if (reorderState.isAnyItemDragging || uid == null) return@LaunchedEffect
            draggedUid = null
            val order = dragged ?: return@LaunchedEffect
            val position = order.indexOfFirst { it.uid == uid }
            if (position < 0 || order == tracks.items) dragged = null else moveBefore(order[position], order.getOrNull(position + 1))
        }
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
                            if (state.canEdit) {
                                Row {
                                    IconButton(onClick = { editingDetails = true }, shapes = IconButtonDefaults.shapes()) {
                                        Icon(MetrolistIcons.Outlined.Edit, contentDescription = stringResource(Res.string.edit_playlist))
                                    }
                                    IconToggleButton(checked = reorderMode, onCheckedChange = { reorderMode = it }) {
                                        Icon(
                                            MetrolistIcons.Outlined.DragIndicator,
                                            contentDescription = stringResource(Res.string.playlist_reorder),
                                        )
                                    }
                                }
                                if (reorderMode && !tracks.complete && tracks.error == null) {
                                    Text(
                                        text = stringResource(Res.string.meld_spotify_reorder_loading),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    if (type == SpotifyCollectionType.ARTIST && tracks.items.isNotEmpty()) {
                        item(key = "popular") { SectionTitle(stringResource(Res.string.meld_spotify_popular), itemModifier) }
                    }
                    itemsIndexed(items, key = { index, _ -> keys[index] }, contentType = { _, _ -> "track" }) { index, item ->
                        val row: @Composable (leading: (@Composable () -> Unit)?) -> Unit = { leading ->
                            SpotifyTrackRow(
                                item = item,
                                index = index,
                                count = items.size,
                                numbered = type == SpotifyCollectionType.ALBUM,
                                onClick = if (canPlay) ({ viewModel.play(index, title) }) else null,
                                onPlayNext = if (canPlay) viewModel::playNext else null,
                                onAddToQueue = if (canPlay) viewModel::addToQueue else null,
                                liked = item.track?.id in likedIds?.value.orEmpty(),
                                modifier = itemModifier.padding(horizontal = CollectionHeaderHorizontalPadding),
                                onAddToPlaylist = picker?.let { { track: SpotifyTrack -> it.open(track) } },
                                onRemove = if (state.canEdit && item.uid != null) ({ viewModel.remove(item) }) else null,
                                leading = leading,
                            )
                        }
                        val uid = item.uid
                        // A row keyed by position has no uid of its own to move by.
                        if (reordering && uid != null && keys[index] is String) {
                            ReorderableItem(reorderState, key = keys[index]) {
                                val handle = Modifier.draggableHandle(onDragStarted = { _ -> draggedUid = uid })
                                val up = index > 0
                                val down = index < items.lastIndex
                                row {
                                    ReorderMenu(
                                        onMoveUp = if (up) ({ moveBefore(item, items[index - 1]) }) else null,
                                        onMoveDown = if (down) ({ moveBefore(item, items.getOrNull(index + 2)) }) else null,
                                        dragHandleModifier = handle,
                                        onMoveToTop = if (up) ({ moveBefore(item, items.first()) }) else null,
                                        onMoveToBottom = if (down) ({ moveBefore(item, null) }) else null,
                                    )
                                }
                            }
                        } else {
                            row(null)
                        }
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
                                onClick = { onOpen(entry.screen()) },
                                modifier = itemModifier,
                            )
                        }
                    }
                    item(key = "end") { Spacer(Modifier.height(32.dp)) }
                }
                state.editFailure?.let { error ->
                    Snackbar(
                        modifier =
                            Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = contentPadding.calculateBottomPadding())
                                .padding(16.dp)
                                .widthIn(max = 600.dp),
                        // Asking again for what Spotify does not allow would only fail again.
                        action =
                            if (error is SpotifyError.Forbidden) {
                                null
                            } else {
                                { TextButton(onClick = viewModel::retryEdit) { Text(stringResource(Res.string.retry)) } }
                            },
                        dismissAction = {
                            IconButton(onClick = viewModel::dismissEditFailure, shapes = IconButtonDefaults.shapes()) {
                                Icon(MetrolistIcons.Outlined.Close, contentDescription = stringResource(Res.string.dismiss))
                            }
                        },
                    ) {
                        Text(stringResource(Res.string.meld_spotify_edit_failed, stringResource(error.text())))
                    }
                }
            }
        }
        if (editingDetails && info != null) {
            var name by remember { mutableStateOf(info.title) }
            var description by remember { mutableStateOf(info.description.orEmpty()) }
            AlertDialog(
                onDismissRequest = { editingDetails = false },
                title = { Text(stringResource(Res.string.edit_playlist)) },
                text = {
                    Column {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text(stringResource(Res.string.playlist_name)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = description,
                            onValueChange = { description = it },
                            label = { Text(stringResource(Res.string.description)) },
                            maxLines = 4,
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = name.isNotBlank(),
                        onClick = {
                            viewModel.editDetails(name, description)
                            editingDetails = false
                        },
                    ) { Text(stringResource(Res.string.save)) }
                },
                dismissButton = { TextButton(onClick = { editingDetails = false }) { Text(stringResource(Res.string.cancel)) } },
            )
        }
    }
}

/**
 * The list keys: an entry's uid, which follows it when the playlist is reordered. An entry without one, or
 * one Spotify repeated across pages, is keyed by position, so that no key is used twice.
 */
internal fun List<SpotifyTrackItem>.rowKeys(): List<Any> {
    val seen = HashSet<String>()
    return mapIndexed { index, item -> item.uid?.takeIf(seen::add) ?: index }
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
