/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.menu

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.metrolist.music.R
import com.metrolist.music.playback.SpotifyYouTubeMapper
import com.metrolist.spotify.Spotify
import com.metrolist.spotify.models.SpotifyPlaylist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dialog that loads and displays the user's Spotify playlists for selection.
 */
@Composable
fun SpotifyPlaylistPickerDialog(
    onSelect: (SpotifyPlaylist) -> Unit,
    onDismiss: () -> Unit,
) {
    var playlists by remember { mutableStateOf<List<SpotifyPlaylist>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            Spotify.myPlaylists(limit = 50)
                .onSuccess { paging ->
                    playlists = paging.items
                    isLoading = false
                }
                .onFailure { e ->
                    error = e.message
                    isLoading = false
                }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.spotify_add_to_playlist)) },
        text = {
            when {
                isLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                error != null -> {
                    Text(
                        text = error ?: "",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                playlists.isEmpty() -> {
                    Text(stringResource(R.string.spotify_no_playlists))
                }
                else -> {
                    LazyColumn {
                        items(playlists, key = { it.id }) { playlist ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(playlist) }
                                    .padding(vertical = 8.dp),
                            ) {
                                val imageUrl = playlist.images.firstOrNull()?.url
                                AsyncImage(
                                    model = imageUrl,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(4.dp)),
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = playlist.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    playlist.owner?.displayName?.let { owner ->
                                        Text(
                                            text = owner,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

/**
 * Manages the full "Add to Spotify playlist" flow for a YouTube track:
 * 1. Resolves the YouTube track to a Spotify URI (cache-first, then search)
 * 2. Shows a loading indicator during resolution
 * 3. Shows the playlist picker on success
 * 4. Adds the track to the selected playlist
 *
 * Call this composable conditionally when [showDialog] is true.
 *
 * @param spotifyUri When non-null, skips reverse lookup and uses this URI directly
 *   (happy path for tracks already known to be from Spotify).
 */
@Composable
fun AddToSpotifyPlaylistFlow(
    showDialog: Boolean,
    youtubeId: String,
    title: String,
    artist: String,
    durationSec: Int = -1,
    spotifyUri: String? = null,
    mapper: SpotifyYouTubeMapper,
    onDismiss: () -> Unit,
) {
    if (!showDialog) return

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var resolvedUri by rememberSaveable { mutableStateOf(spotifyUri) }
    var isResolving by rememberSaveable { mutableStateOf(spotifyUri == null) }
    var resolveFailed by rememberSaveable { mutableStateOf(false) }
    var showPicker by rememberSaveable { mutableStateOf(spotifyUri != null) }

    LaunchedEffect(showDialog) {
        if (spotifyUri != null) {
            resolvedUri = spotifyUri
            showPicker = true
            isResolving = false
            return@LaunchedEffect
        }
        isResolving = true
        resolveFailed = false
        val uri = withContext(Dispatchers.IO) {
            mapper.resolveToSpotifyUri(youtubeId, title, artist, durationSec)
        }
        if (uri != null) {
            resolvedUri = uri
            showPicker = true
            isResolving = false
        } else {
            isResolving = false
            resolveFailed = true
        }
    }

    when {
        isResolving -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.spotify_add_to_playlist)) },
                text = {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.spotify_searching),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(android.R.string.cancel))
                    }
                },
            )
        }

        resolveFailed -> {
            LaunchedEffect(Unit) {
                Toast.makeText(
                    context,
                    context.getString(R.string.spotify_track_not_found_on_spotify),
                    Toast.LENGTH_SHORT,
                ).show()
                onDismiss()
            }
        }

        showPicker && resolvedUri != null -> {
            val uri = resolvedUri!!
            SpotifyPlaylistPickerDialog(
                onSelect = { selectedPlaylist ->
                    onDismiss()
                    coroutineScope.launch(Dispatchers.IO) {
                        Spotify.addTracksToPlaylist(selectedPlaylist.id, listOf(uri))
                            .onSuccess {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(
                                        context,
                                        context.getString(
                                            R.string.spotify_track_added_to_playlist,
                                            selectedPlaylist.name,
                                        ),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                            .onFailure {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.spotify_add_to_playlist_failed),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                    }
                },
                onDismiss = onDismiss,
            )
        }
    }
}
