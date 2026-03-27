/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.playlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.ListThumbnailSize
import com.metrolist.music.playback.queues.SoundCloudPlaylistQueue
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.ItemThumbnail
import com.metrolist.music.ui.component.ListItem
import com.metrolist.music.viewmodels.SoundCloudPlaylistViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundCloudPlaylistScreen(
    navController: NavController,
    viewModel: SoundCloudPlaylistViewModel = hiltViewModel()
) {
    val playlist by viewModel.playlist.collectAsState()
    val tracks by viewModel.tracks.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    val playerConnection = LocalPlayerConnection.current ?: return
    val lazyListState = rememberLazyListState()

    val playTracks = { startIndex: Int ->
        if (tracks.isNotEmpty() && playlist != null) {
            playerConnection.playQueue(
                SoundCloudPlaylistQueue(
                    playlistId = playlist!!.id,
                    initialTracks = tracks,
                    startIndex = startIndex,
                    mapper = viewModel.mapper
                )
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(LocalPlayerAwareWindowInsets.current.asPaddingValues())
    ) {
        TopAppBar(
            title = {
                Text(
                    text = playlist?.title ?: "SoundCloud Playlist",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            navigationIcon = {
                IconButton(
                    icon = R.drawable.chevron_back,
                    onClick = { navController.popBackStack() }
                )
            },
            actions = {
                IconButton(
                    icon = R.drawable.play,
                    onClick = { playTracks(0) },
                    enabled = !isLoading && tracks.isNotEmpty()
                )
            }
        )

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (error != null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = "Error: $error", color = MaterialTheme.colorScheme.error)
            }
        } else {
            LazyColumn(
                state = lazyListState,
                modifier = Modifier.fillMaxSize()
            ) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        ItemThumbnail(
                            url = playlist?.artwork_url,
                            modifier = Modifier.size(200.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = playlist?.title ?: "",
                            style = MaterialTheme.typography.titleLarge
                        )
                        playlist?.user?.username?.let { username ->
                            Text(
                                text = username,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                itemsIndexed(tracks) { index, track ->
                    ListItem(
                        title = track.title,
                        subtitle = track.user?.username ?: "",
                        thumbnailContent = {
                            ItemThumbnail(
                                url = track.artwork_url,
                                modifier = Modifier.size(ListThumbnailSize)
                            )
                        },
                        modifier = Modifier.clickable { playTracks(index) }
                    )
                }
            }
        }
    }
}
