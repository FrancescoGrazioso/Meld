/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.menu

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.metrolist.music.LocalDatabase
import com.metrolist.music.R
import com.metrolist.music.db.entities.SpeedDialItem
import com.metrolist.music.ui.component.Material3MenuGroup
import com.metrolist.music.ui.component.Material3MenuItemData
import com.metrolist.spotify.models.SpotifyPlaylist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Centralised context menu for Spotify playlists.
 * Used from Home (Spotify sections), Speed Dial, and Library.
 * Handles pin/unpin state internally via [LocalDatabase].
 *
 * @param playlist full Spotify playlist object
 * @param onNavigate callback to open the playlist detail screen
 * @param onDismiss callback to close the menu
 */
@Composable
fun SpotifyPlaylistMenu(
    playlist: SpotifyPlaylist,
    onNavigate: () -> Unit,
    onDismiss: () -> Unit,
) {
    SpotifyPlaylistMenuContent(
        speedDialId = "spotify:${playlist.id}",
        displayName = playlist.name,
        buildSpeedDialItem = { SpeedDialItem.fromSpotifyPlaylist(playlist) },
        onNavigate = onNavigate,
        onDismiss = onDismiss,
    )
}

/**
 * Overload for contexts where only an ID and title are available
 * (e.g. Speed Dial items stored as [PlaylistItem] with a "spotify:" prefix).
 *
 * @param spotifyId the full "spotify:xxx" id
 * @param title display name of the playlist
 * @param thumbnail optional thumbnail URL for the speed dial item
 */
@Composable
fun SpotifyPlaylistMenu(
    spotifyId: String,
    title: String,
    thumbnail: String? = null,
    onNavigate: () -> Unit,
    onDismiss: () -> Unit,
) {
    SpotifyPlaylistMenuContent(
        speedDialId = spotifyId,
        displayName = title,
        buildSpeedDialItem = {
            SpeedDialItem(
                id = spotifyId,
                title = title,
                thumbnailUrl = thumbnail,
                type = "PLAYLIST",
            )
        },
        onNavigate = onNavigate,
        onDismiss = onDismiss,
    )
}

@Composable
private fun SpotifyPlaylistMenuContent(
    speedDialId: String,
    displayName: String,
    buildSpeedDialItem: () -> SpeedDialItem,
    onNavigate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val database = LocalDatabase.current
    val coroutineScope = rememberCoroutineScope()
    val isPinned by database.speedDialDao.isPinned(speedDialId).collectAsState(initial = false)

    Spacer(Modifier.height(8.dp))

    Material3MenuGroup(
        items = listOf(
            Material3MenuItemData(
                title = {
                    Text(
                        text = stringResource(
                            if (isPinned) R.string.unpin_from_speed_dial
                            else R.string.pin_to_speed_dial
                        )
                    )
                },
                icon = {
                    Icon(
                        painter = painterResource(
                            if (isPinned) R.drawable.remove else R.drawable.ic_push_pin
                        ),
                        contentDescription = null,
                    )
                },
                onClick = {
                    coroutineScope.launch(Dispatchers.IO) {
                        val item = buildSpeedDialItem()
                        if (database.speedDialDao.isPinned(item.id).first()) {
                            database.speedDialDao.delete(item.id)
                        } else {
                            database.speedDialDao.insert(item)
                        }
                    }
                    onDismiss()
                },
            ),
            Material3MenuItemData(
                title = { Text(text = displayName) },
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.queue_music),
                        contentDescription = null,
                    )
                },
                onClick = {
                    onNavigate()
                    onDismiss()
                },
            ),
        )
    )

    Spacer(Modifier.height(8.dp))
}
