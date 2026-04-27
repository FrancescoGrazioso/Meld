/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.CONTENT_TYPE_PLAYLIST
import com.metrolist.music.db.entities.Playlist
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.PlaylistListItem
import com.metrolist.music.ui.component.SpotifyFolderListItem
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.viewmodels.SpotifyViewModel
import com.metrolist.spotify.SpotifyMapper
import com.metrolist.spotify.models.SpotifyLibraryItem
import java.net.URLEncoder

/**
 * Renders one level of the user's Spotify library tree. Reached by tapping a folder
 * in [LibraryPlaylistsScreen] or another [SpotifyFolderScreen] (folders can contain
 * sub-folders). Tapping a playlist navigates to the existing `spotify_playlist`
 * route; tapping a sub-folder pushes another instance of this screen.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SpotifyFolderScreen(
    navController: NavController,
    folderUri: String,
    folderName: String?,
    spotifyViewModel: SpotifyViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<SpotifyLibraryItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(folderUri) {
        items = null
        error = null
        spotifyViewModel.loadFolderContents(folderUri)
            .onSuccess { items = it }
            .onFailure { error = it.message ?: it::class.simpleName }
    }

    val displayName = folderName?.takeIf { it.isNotBlank() }
        ?: folderUri.substringAfterLast(":")

    val lazyListState = rememberLazyListState()

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            items == null && error == null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            error != null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = error ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            items?.isEmpty() == true -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.spotify_folder_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            else -> {
                LazyColumn(
                    state = lazyListState,
                    contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
                ) {
                    items(items!!.size, key = { idx -> items!![idx].uri }, contentType = { CONTENT_TYPE_PLAYLIST }) { index ->
                        when (val item = items!![index]) {
                            is SpotifyLibraryItem.Folder -> {
                                val encoded = URLEncoder.encode(item.folder.uri, Charsets.UTF_8.name())
                                val encodedName = URLEncoder.encode(item.folder.name, Charsets.UTF_8.name())
                                SpotifyFolderListItem(
                                    folder = item.folder,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            navController.navigate("spotify_folder/$encoded?name=$encodedName")
                                        },
                                )
                            }
                            is SpotifyLibraryItem.Playlist -> {
                                val pl = item.playlist
                                val thumbnailUrl = SpotifyMapper.getPlaylistThumbnail(pl)
                                PlaylistListItem(
                                    playlist = Playlist(
                                        playlist = PlaylistEntity(
                                            id = "spotify_${pl.id}",
                                            name = pl.name,
                                            thumbnailUrl = thumbnailUrl,
                                        ),
                                        songCount = 0,
                                        songThumbnails = listOfNotNull(thumbnailUrl),
                                    ),
                                    autoPlaylist = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            navController.navigate("spotify_playlist/${pl.id}")
                                        },
                                )
                            }
                        }
                    }
                }
            }
        }

        TopAppBar(
            title = {
                Text(
                    text = displayName,
                    maxLines = 1,
                )
            },
            navigationIcon = {
                IconButton(
                    onClick = navController::navigateUp,
                    onLongClick = navController::backToMain,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.arrow_back),
                        contentDescription = null,
                    )
                }
            },
        )
    }
}
