@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.meld.spotify.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meld.spotify.SpotifyConnection
import com.meld.spotify.SpotifyConnectionState
import com.meld.spotify.library.SpotifyPickerState
import com.meld.spotify.library.SpotifyPlaylistPicker
import com.meld.spotify.library.url
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.close
import com.metrolist.shared.generated.resources.meld_spotify_add_to_playlist
import com.metrolist.shared.generated.resources.meld_spotify_added
import com.metrolist.shared.generated.resources.meld_spotify_songs_count
import com.metrolist.shared.generated.resources.meld_spotify_already_in_playlist
import com.metrolist.shared.generated.resources.meld_spotify_new_playlist
import com.metrolist.shared.generated.resources.meld_spotify_new_playlist_description
import com.metrolist.shared.generated.resources.meld_spotify_new_playlist_description_list
import com.metrolist.shared.generated.resources.meld_spotify_not_found
import com.metrolist.shared.generated.resources.meld_spotify_searching
import com.metrolist.shared.generated.resources.n_song
import com.metrolist.shared.generated.resources.no_editable_playlists
import com.metrolist.shared.generated.resources.retry
import com.metrolist.shared.generated.resources.search
import com.metrolist.shared.generated.resources.search_no_results
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.ui.components.ContainedLoadingIndicator
import com.metrolist.shared.ui.components.Dialog
import com.metrolist.shared.ui.components.PlayerMenuAction
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.utils.DragScrollableColumn
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.mp.KoinPlatform

/**
 * "Add to Spotify playlist" for the app's own song menus and players, on every platform: empty unless a
 * Spotify account is connected and [song] can be on Spotify. The dialog it opens is drawn by [SpotifyPlaylistPickerHost].
 */
@Composable
fun spotifyPlaylistMenuActions(
    song: SongItem?,
    onDismiss: () -> Unit,
): List<PlayerMenuAction> {
    val koin = remember { KoinPlatform.getKoinOrNull() }
    val picker = remember { koin?.getOrNull<SpotifyPlaylistPicker>() }
    val session = remember { koin?.getOrNull<SpotifyConnection>() }?.state?.collectAsState()
    if (picker == null || song == null || song.isLocal || session?.value !is SpotifyConnectionState.Connected) return emptyList()
    return listOf(
        PlayerMenuAction(MetrolistIcons.AutoMirrored.Outlined.PlaylistAdd, stringResource(Res.string.meld_spotify_add_to_playlist)) {
            picker.open(song)
            onDismiss()
        },
    )
}

/**
 * Draws Meld's dialogs that any screen can open, while one is open: "Add to Spotify playlist" and "Choose the
 * YouTube version". Each host calls it once, above its screens.
 */
@Composable
fun SpotifyPlaylistPickerHost() {
    SpotifyNoticeHost()
    SpotifyVersionDialog()
    val picker = remember { KoinPlatform.getKoinOrNull()?.getOrNull<SpotifyPlaylistPicker>() } ?: return
    val state = picker.state.collectAsState().value ?: return
    Dialog(onDismissRequest = picker::close) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.widthIn(max = 560.dp).heightIn(max = 600.dp).padding(vertical = 16.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(Res.string.meld_spotify_add_to_playlist), style = MaterialTheme.typography.titleLarge)
                        Text(
                            text =
                                when {
                                    state.tracks.size > 1 ->
                                        state.title + " • " +
                                            pluralStringResource(Res.plurals.meld_spotify_songs_count, state.tracks.size, state.tracks.size)
                                    else -> state.track?.let { track -> "${track.name} • ${track.artists.joinToString { it.name }}" } ?: state.title
                                },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = picker::close, shapes = IconButtonDefaults.shapes()) {
                        Icon(MetrolistIcons.Outlined.Close, contentDescription = stringResource(Res.string.close))
                    }
                }
                PickerBody(state, picker)
            }
        }
    }
}

@Composable
private fun ColumnScope.PickerBody(
    state: SpotifyPickerState,
    picker: SpotifyPlaylistPicker,
) {
    val error = state.error
    when {
        state.notFound -> {
            SpotifyNotice(stringResource(Res.string.meld_spotify_not_found), null, {})
        }

        state.loading -> {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                ContainedLoadingIndicator()
                if (state.track == null) Text(stringResource(Res.string.meld_spotify_searching), Modifier.padding(top = 12.dp))
            }
        }

        // Nothing to choose from: the lookup or the list of playlists failed.
        error != null && state.playlists.isEmpty() -> {
            SpotifyNotice(stringResource(error.text()), stringResource(Res.string.retry), picker::retry, isError = true)
        }

        state.playlists.isEmpty() -> {
            NewPlaylistRow(state, picker)
            if (state.adding != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            SpotifyNotice(stringResource(Res.string.no_editable_playlists), null, {})
        }

        else -> {
            OutlinedTextField(
                value = state.query,
                onValueChange = picker::setQuery,
                placeholder = { Text(stringResource(Res.string.search)) },
                leadingIcon = { Icon(MetrolistIcons.Outlined.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Box(Modifier.fillMaxWidth().heightIn(min = 4.dp)) {
                if (state.adding != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            // The add that failed; choosing the playlist again is the retry.
            if (error != null) {
                Text(
                    text = stringResource(error.text()),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
            }
            val shown = state.shown
            if (shown.isEmpty()) SpotifyNotice(stringResource(Res.string.search_no_results), null, {})
            DragScrollableColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                if (state.query.isBlank()) item(key = "new") { NewPlaylistRow(state, picker) }
                items(shown, key = { it.id }) { playlist ->
                    SpotifyLibraryRow(
                        title = playlist.name,
                        subtitle =
                            when {
                                playlist.id in state.added -> stringResource(Res.string.meld_spotify_added)
                                playlist.id in state.containing -> stringResource(Res.string.meld_spotify_already_in_playlist)
                                else -> playlist.trackCount?.let { pluralStringResource(Res.plurals.n_song, it, it) }.orEmpty()
                            },
                        imageUrl = playlist.images.url(PICKER_IMAGE_SIZE),
                        icon = MetrolistIcons.AutoMirrored.Outlined.QueueMusic,
                        onClick = { picker.add(playlist) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NewPlaylistRow(
    state: SpotifyPickerState,
    picker: SpotifyPlaylistPicker,
) {
    SpotifyLibraryRow(
        title = stringResource(Res.string.meld_spotify_new_playlist),
        subtitle =
            if (state.tracks.size > 1) {
                stringResource(Res.string.meld_spotify_new_playlist_description_list, state.title)
            } else {
                stringResource(Res.string.meld_spotify_new_playlist_description, state.track?.name.orEmpty())
            },
        imageUrl = null,
        icon = MetrolistIcons.AutoMirrored.Outlined.PlaylistAdd,
        onClick = picker::addToNew,
    )
}

private const val PICKER_IMAGE_SIZE = 150
