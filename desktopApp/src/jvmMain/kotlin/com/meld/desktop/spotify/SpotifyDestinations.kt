@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.meld.desktop.spotify

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meld.spotify.ui.SpotifyScreen
import com.metrolist.desktop.state.app.UiState
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.refresh
import com.metrolist.shared.ui.components.CollectionHeaderNavigation
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.navigation.LocalNavigator
import com.metrolist.shared.ui.navigation.Screen
import com.metrolist.shared.ui.navigation.SpotifyFolderScreen
import com.metrolist.shared.ui.navigation.SpotifySettingsScreen
import com.metrolist.shared.ui.navigation.SpotifyTracksScreen
import org.jetbrains.compose.resources.stringResource

/** Draws [destination] when it is one of Meld's Spotify routes and says whether it was. */
@Composable
fun spotifyDestination(destination: Screen): Boolean {
    val navigator = LocalNavigator.current
    when (destination) {
        is SpotifyFolderScreen, is SpotifyTracksScreen -> {
            SpotifyScreen(destination, navigator::navigate, { navigator.goBack() }) { title, onRefresh, content ->
                // A track list has its own header with the title.
                SpotifyScreenHost(title.takeIf { destination is SpotifyFolderScreen }, onRefresh) {
                    content(PaddingValues(bottom = 16.dp))
                }
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

/** The desktop's frame around the shared screen: back, and refresh as a button because a mouse cannot pull to refresh. */
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
