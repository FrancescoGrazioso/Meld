@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.meld.desktop.spotify

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meld.spotify.SpotifyConnection
import com.meld.spotify.library.SpotifyLibraryStore
import com.meld.spotify.library.SpotifyLibraryViewModel
import com.meld.spotify.library.SpotifyTracksViewModel
import com.meld.spotify.playback.SpotifyPlayback
import com.meld.spotify.ui.SpotifyLibraryContent
import com.meld.spotify.ui.SpotifyTracksContent
import com.metrolist.desktop.state.app.UiState
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.import_provider_spotify
import com.metrolist.shared.generated.resources.refresh
import com.metrolist.shared.ui.components.CollectionHeaderNavigation
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.navigation.LocalNavigator
import com.metrolist.shared.ui.navigation.Screen
import com.metrolist.shared.ui.navigation.SpotifyLibraryScreen
import com.metrolist.shared.ui.navigation.SpotifySettingsScreen
import com.metrolist.shared.ui.navigation.SpotifyTracksScreen
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * Draws [destination] when it is one of Meld's Spotify routes and says whether it was. The lists
 * live in the Koin-held [SpotifyLibraryStore], so the screen state built here can be as short-lived
 * as the composition: coming back finds the lists loaded, and the host's saved state restores the scroll.
 */
@Composable
fun spotifyDestination(destination: Screen): Boolean {
    val navigator = LocalNavigator.current
    when (destination) {
        is SpotifyLibraryScreen -> {
            val store = koinInject<SpotifyLibraryStore>()
            val connection = koinInject<SpotifyConnection>()
            val scope = rememberCoroutineScope()
            val viewModel = remember(destination) { SpotifyLibraryViewModel(scope, store, connection, destination.folderUri) }
            SpotifyScreenHost(destination.title ?: stringResource(Res.string.import_provider_spotify), viewModel::refresh) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    SpotifyLibraryContent(
                        viewModel = viewModel,
                        isFolder = destination.folderUri != null,
                        contentPadding = PaddingValues(bottom = 16.dp),
                        modifier = Modifier.widthIn(max = 1400.dp),
                    )
                }
            }
        }

        is SpotifyTracksScreen -> {
            val store = koinInject<SpotifyLibraryStore>()
            val connection = koinInject<SpotifyConnection>()
            val playback = koinInject<SpotifyPlayback>()
            val scope = rememberCoroutineScope()
            val viewModel =
                remember(destination) {
                    SpotifyTracksViewModel(scope, store, connection, playback, destination.type, destination.id)
                }
            // The list has its own header with the title.
            SpotifyScreenHost(title = null, viewModel::refresh) {
                SpotifyTracksContent(
                    viewModel = viewModel,
                    type = destination.type,
                    id = destination.id,
                    titleHint = destination.title,
                    contentPadding = PaddingValues(bottom = 16.dp),
                )
            }
        }

        // Desktop settings are an overlay, not a route: open it over the screen that asked.
        SpotifySettingsScreen -> {
            LaunchedEffect(Unit) {
                navigator.goBack()
                UiState.showIntegrationsSettings = true
            }
        }

        else -> {
            return false
        }
    }
    return true
}

/** Back and refresh above the shared content: a mouse cannot pull to refresh. */
@Composable
private fun SpotifyScreenHost(
    title: String?,
    onRefresh: () -> Unit,
    content: @Composable () -> Unit,
) {
    val navigator = LocalNavigator.current
    Box(Modifier.fillMaxSize()) {
        CollectionHeaderNavigation(
            onBackClick = { navigator.goBack() },
            title =
                if (title != null) {
                    {
                        Text(
                            text = title,
                            modifier = Modifier.padding(horizontal = 64.dp),
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    null
                },
            content = content,
        )
        IconButton(
            onClick = onRefresh,
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 8.dp, top = 8.dp),
            shapes = IconButtonDefaults.shapes(),
        ) {
            Icon(MetrolistIcons.Outlined.Refresh, contentDescription = stringResource(Res.string.refresh))
        }
    }
}
