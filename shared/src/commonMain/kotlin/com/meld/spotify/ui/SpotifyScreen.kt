package com.meld.spotify.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meld.spotify.library.SpotifyCollectionType
import com.meld.spotify.library.SpotifyFolderViewModel
import com.meld.spotify.library.SpotifyTracksViewModel
import com.meld.spotify.playback.SpotifyPlayback
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.import_provider_spotify
import com.metrolist.shared.generated.resources.meld_spotify_liked_songs
import com.metrolist.shared.ui.navigation.Screen
import com.metrolist.shared.ui.navigation.SpotifyFolderScreen
import com.metrolist.shared.ui.navigation.SpotifyTracksScreen
import org.jetbrains.compose.resources.stringResource
import org.koin.mp.KoinPlatform

/**
 * Draws [screen] when it is a [SpotifyFolderScreen] or a [SpotifyTracksScreen]; this is the only way
 * a host shows them. [onOpen] opens another route, [com.metrolist.shared.ui.navigation.SpotifySettingsScreen]
 * included. [frame] is the host's own chrome (back button, insets): it is given the screen's title
 * and its refresh action, and calls `content` with the padding the list has to keep.
 *
 * The screen's state lives exactly as long as it is composed. The lists themselves are in the
 * Koin-held [com.meld.spotify.library.SpotifyLibraryStore], so coming back finds them loaded.
 */
@Composable
fun SpotifyScreen(
    screen: Screen,
    onOpen: (Screen) -> Unit,
    frame: @Composable (title: String, onRefresh: () -> Unit, content: @Composable (PaddingValues) -> Unit) -> Unit,
) {
    // Keyed, so a host that shows every route from one place still gets a scope per screen, cancelled with it.
    key(screen) {
        val koin = remember { KoinPlatform.getKoin() }
        val scope = rememberCoroutineScope()
        when (screen) {
            is SpotifyFolderScreen -> {
                var sort by rememberSaveable { mutableStateOf(false to false) }
                var query by rememberSaveable { mutableStateOf("") }
                val viewModel = remember { SpotifyFolderViewModel(scope, koin.get(), koin.get(), screen.folderUri, sort, query) }
                LaunchedEffect(viewModel) {
                    viewModel.state.collect {
                        sort = it.byName to it.descending
                        query = it.query
                    }
                }
                frame(screen.title ?: stringResource(Res.string.import_provider_spotify), viewModel::refresh) { padding ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        SpotifyFolderContent(viewModel, padding, onOpen, Modifier.widthIn(max = 1400.dp))
                    }
                }
            }

            is SpotifyTracksScreen -> {
                // Playback is looked up, not required: without it the list still opens and only the play actions are missing.
                val viewModel =
                    remember {
                        SpotifyTracksViewModel(scope, koin.get(), koin.get(), koin.getOrNull<SpotifyPlayback>(), screen.type, screen.id)
                    }
                val liked = screen.type == SpotifyCollectionType.LIKED
                val fallback = if (liked) Res.string.meld_spotify_liked_songs else Res.string.import_provider_spotify
                frame(screen.title ?: stringResource(fallback), viewModel::refresh) { padding ->
                    SpotifyTracksContent(viewModel, screen.type, screen.id, screen.title, padding, onOpen)
                }
            }

            else -> {
                Unit
            }
        }
    }
}
