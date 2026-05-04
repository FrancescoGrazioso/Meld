/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.menu

import android.widget.Toast
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.ListItemHeight
import com.metrolist.music.constants.ListThumbnailSize
import com.metrolist.music.constants.ThumbnailCornerRadius
import com.metrolist.music.playback.SpotifyYouTubeMapper
import com.metrolist.music.ui.component.ListDialog
import com.metrolist.music.ui.component.Material3MenuGroup
import com.metrolist.music.ui.component.Material3MenuItemData
import com.metrolist.music.ui.component.YouTubeMatchDialog
import com.metrolist.music.utils.joinByBullet
import com.metrolist.music.utils.makeTimeString
import com.metrolist.spotify.SpotifyMapper
import com.metrolist.spotify.models.SpotifyTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Context menu for Spotify tracks that haven't been resolved to a Room [Song] yet.
 * Provides the most common actions: play next, add to queue, change YouTube match,
 * and (when inside a playlist context) remove from playlist.
 *
 * @param onRemoveFromPlaylist When non-null, shows a "Remove from playlist" action.
 *   The callback is invoked when the user confirms removal.
 */
@Composable
fun SpotifyTrackMenu(
    track: SpotifyTrack,
    mapper: SpotifyYouTubeMapper,
    onDismiss: () -> Unit,
    navController: NavController? = null,
    onRemoveFromPlaylist: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val coroutineScope = rememberCoroutineScope()

    var showYouTubeMatchDialog by rememberSaveable { mutableStateOf(false) }
    var showAddToPlaylistDialog by rememberSaveable { mutableStateOf(false) }
    var showSelectArtistDialog by rememberSaveable { mutableStateOf(false) }

    fun resolveAndNavigateToArtist(artistName: String) {
        coroutineScope.launch {
            val ytArtistId = withContext(Dispatchers.IO) {
                runCatching {
                    YouTube.search(artistName, YouTube.SearchFilter.FILTER_ARTIST)
                        .getOrNull()
                        ?.items
                        ?.firstOrNull { it is ArtistItem }
                        ?.id
                }.getOrNull()
            }
            if (ytArtistId != null) {
                navController?.navigate("artist/$ytArtistId")
                onDismiss()
            } else {
                Toast.makeText(
                    context,
                    context.getString(R.string.spotify_no_tracks),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    if (showSelectArtistDialog) {
        ListDialog(
            onDismiss = { showSelectArtistDialog = false },
        ) {
            items(track.artists) { artist ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .height(ListItemHeight)
                        .clickable {
                            showSelectArtistDialog = false
                            resolveAndNavigateToArtist(artist.name)
                        }
                        .padding(horizontal = 12.dp),
                ) {
                    Box(
                        contentAlignment = Alignment.CenterStart,
                        modifier = Modifier
                            .fillParentMaxWidth()
                            .height(ListItemHeight)
                            .padding(horizontal = 24.dp),
                    ) {
                        Text(
                            text = artist.name,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }

    val currentMatch by produceState<com.metrolist.music.db.entities.SpotifyMatchEntity?>(
        initialValue = null,
        track.id,
    ) {
        withContext(Dispatchers.IO) {
            value = database.getSpotifyMatch(track.id)
        }
    }

    if (showYouTubeMatchDialog) {
        YouTubeMatchDialog(
            currentYouTubeId = currentMatch?.youtubeId,
            onConfirm = { result ->
                coroutineScope.launch(Dispatchers.IO) {
                    mapper.overrideMatch(
                        spotifyId = track.id,
                        youtubeId = result.videoId,
                        title = result.title,
                        artist = result.artist,
                    )
                }
            },
            onDismiss = { showYouTubeMatchDialog = false },
        )
    }

    val thumbnailUrl = SpotifyMapper.getTrackThumbnail(track)

    ListItem(
        headlineContent = {
            Text(
                text = track.name,
                modifier = Modifier.basicMarquee(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                text = joinByBullet(
                    track.artists.joinToString { it.name },
                    makeTimeString(track.durationMs.toLong()),
                ),
            )
        },
        leadingContent = {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(ListThumbnailSize)
                    .clip(RoundedCornerShape(ThumbnailCornerRadius)),
            ) {
                AsyncImage(
                    model = thumbnailUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(ThumbnailCornerRadius)),
                )
            }
        },
    )

    Spacer(modifier = Modifier.height(12.dp))

    Material3MenuGroup(
        items = listOf(
            Material3MenuItemData(
                title = { Text(text = stringResource(R.string.play_next)) },
                description = { Text(text = stringResource(R.string.play_next_desc)) },
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.playlist_play),
                        contentDescription = null,
                    )
                },
                onClick = {
                    onDismiss()
                    coroutineScope.launch {
                        val mediaItem = withContext(Dispatchers.IO) {
                            mapper.resolveToMediaItem(track)
                        }
                        if (mediaItem != null) {
                            playerConnection.playNext(mediaItem)
                            Toast.makeText(
                                context,
                                context.getString(R.string.added_to_play_next),
                                Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            Toast.makeText(
                                context,
                                context.getString(R.string.spotify_no_tracks),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                },
            ),
            Material3MenuItemData(
                title = { Text(text = stringResource(R.string.add_to_queue)) },
                description = { Text(text = stringResource(R.string.add_to_queue_desc)) },
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.queue_music),
                        contentDescription = null,
                    )
                },
                onClick = {
                    onDismiss()
                    coroutineScope.launch {
                        val mediaItem = withContext(Dispatchers.IO) {
                            mapper.resolveToMediaItem(track)
                        }
                        if (mediaItem != null) {
                            playerConnection.addToQueue(mediaItem)
                            Toast.makeText(
                                context,
                                context.getString(R.string.added_to_queue),
                                Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            Toast.makeText(
                                context,
                                context.getString(R.string.spotify_no_tracks),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                },
            ),
        ),
    )

    Material3MenuGroup(
        items = listOf(
            Material3MenuItemData(
                title = { Text(text = stringResource(R.string.spotify_add_to_playlist)) },
                description = { Text(text = stringResource(R.string.spotify_add_to_playlist_desc)) },
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.playlist_add),
                        contentDescription = null,
                    )
                },
                onClick = {
                    showAddToPlaylistDialog = true
                },
            ),
            Material3MenuItemData(
                title = { Text(text = stringResource(R.string.change_youtube_version)) },
                description = { Text(text = stringResource(R.string.change_youtube_version_desc)) },
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.link),
                        contentDescription = null,
                    )
                },
                onClick = {
                    showYouTubeMatchDialog = true
                },
            ),
        ),
    )

    if (navController != null && track.artists.isNotEmpty()) {
        Material3MenuGroup(
            items = listOf(
                Material3MenuItemData(
                    title = { Text(text = stringResource(R.string.view_artist)) },
                    description = { Text(text = track.artists.joinToString { it.name }) },
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.artist),
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        if (track.artists.size == 1) {
                            resolveAndNavigateToArtist(track.artists[0].name)
                        } else {
                            showSelectArtistDialog = true
                        }
                    },
                ),
            ),
        )
    }

    AddToSpotifyPlaylistFlow(
        showDialog = showAddToPlaylistDialog,
        youtubeId = track.id,
        title = track.name,
        artist = track.artists.firstOrNull()?.name ?: "",
        durationSec = track.durationMs / 1000,
        spotifyUri = track.uri ?: "spotify:track:${track.id}",
        mapper = mapper,
        onDismiss = { showAddToPlaylistDialog = false },
    )

    if (onRemoveFromPlaylist != null) {
        Material3MenuGroup(
            items = listOf(
                Material3MenuItemData(
                    title = { Text(text = stringResource(R.string.spotify_remove_from_playlist)) },
                    description = { Text(text = stringResource(R.string.spotify_remove_from_playlist_desc)) },
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.remove),
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        onDismiss()
                        onRemoveFromPlaylist()
                    },
                ),
            ),
        )
    }
}
