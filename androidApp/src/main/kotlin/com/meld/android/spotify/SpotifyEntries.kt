@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.meld.android.spotify

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.meld.spotify.SpotifyConnection
import com.meld.spotify.library.SpotifyCollectionType
import com.meld.spotify.library.SpotifyFolderViewModel
import com.meld.spotify.library.SpotifyLibraryStore
import com.meld.spotify.library.SpotifyTracksViewModel
import com.meld.spotify.playback.SpotifyPlayback
import com.meld.spotify.ui.SpotifyFolderContent
import com.meld.spotify.ui.SpotifyTracksContent
import com.metrolist.android.LocalMiniPlayerBottomInset
import com.metrolist.android.ui.detailViewModelMetadata
import com.metrolist.android.ui.navTabMetadata
import com.metrolist.android.ui.screens.settings.IntegrationsScreen
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.back
import com.metrolist.shared.generated.resources.import_provider_spotify
import com.metrolist.shared.generated.resources.meld_spotify_liked_songs
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.navigation.Navigator
import com.metrolist.shared.ui.navigation.Screen
import com.metrolist.shared.ui.navigation.SpotifyFolderScreen
import com.metrolist.shared.ui.navigation.SpotifySettingsScreen
import com.metrolist.shared.ui.navigation.SpotifyTracksScreen
import com.metrolist.shared.ui.settings.SettingsPageId
import com.metrolist.shared.ui.tv.tvFocusHighlight
import org.jetbrains.compose.resources.stringResource
import org.koin.androidx.compose.koinViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module

class SpotifyFolderHostViewModel(
    store: SpotifyLibraryStore,
    connection: SpotifyConnection,
    folderUri: String,
) : ViewModel() {
    val shared = SpotifyFolderViewModel(viewModelScope, store, connection, folderUri)
}

class SpotifyTracksHostViewModel(
    store: SpotifyLibraryStore,
    connection: SpotifyConnection,
    playback: SpotifyPlayback?,
    type: SpotifyCollectionType,
    id: String,
) : ViewModel() {
    val shared = SpotifyTracksViewModel(viewModelScope, store, connection, playback, type, id)
}

val spotifyScreensModule: Module =
    module {
        viewModel { (folderUri: String) -> SpotifyFolderHostViewModel(get(), get(), folderUri) }
        // Playback is looked up, not required: without it the lists still open and only the play actions are missing.
        viewModel { (type: SpotifyCollectionType, id: String) -> SpotifyTracksHostViewModel(get(), get(), getOrNull(), type, id) }
    }

/** Meld's Spotify routes, added to the app's entry provider. */
fun EntryProviderScope<NavKey>.spotifyEntries(navigator: Navigator) {
    entry<SpotifyFolderScreen>(metadata = navTabMetadata + detailViewModelMetadata) { screen ->
        val folderUri = screen.folderUri
        SpotifyScreenHost(screen.title ?: stringResource(Res.string.import_provider_spotify), navigator) { padding ->
            SpotifyFolderContent(
                viewModel =
                    koinViewModel<SpotifyFolderHostViewModel>(
                        key = "spotify-folder:$folderUri",
                        parameters = { parametersOf(folderUri) },
                    ).shared,
                contentPadding = padding,
            )
        }
    }
    entry<SpotifyTracksScreen>(metadata = navTabMetadata + detailViewModelMetadata) { screen ->
        val title =
            screen.title ?: stringResource(
                if (screen.type == SpotifyCollectionType.LIKED) Res.string.meld_spotify_liked_songs else Res.string.import_provider_spotify,
            )
        SpotifyScreenHost(title, navigator) { padding ->
            SpotifyTracksContent(
                viewModel =
                    koinViewModel<SpotifyTracksHostViewModel>(
                        key = "spotify-tracks:${screen.type}:${screen.id}",
                        parameters = { parametersOf(screen.type, screen.id) },
                    ).shared,
                type = screen.type,
                id = screen.id,
                titleHint = screen.title,
                contentPadding = padding,
            )
        }
    }
    entry<SpotifySettingsScreen>(metadata = navTabMetadata) {
        IntegrationsScreen(
            onNavigateBack = { navigator.goBack() },
            onNavigateToListenTogether = { navigator.navigate(Screen.ListenTogetherSettings) },
            initialPage = SettingsPageId.SPOTIFY,
        )
    }
}

@Composable
private fun SpotifyScreenHost(
    title: String,
    navigator: Navigator,
    content: @Composable (PaddingValues) -> Unit,
) {
    val miniPlayerInset = LocalMiniPlayerBottomInset.current
    Scaffold(
        modifier = Modifier.imePadding().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)),
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(
                        onClick = { navigator.goBack() },
                        modifier = Modifier.tvFocusHighlight(CircleShape),
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        Icon(MetrolistIcons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.back))
                    }
                },
            )
        },
    ) { padding ->
        content(PaddingValues(top = padding.calculateTopPadding(), bottom = miniPlayerInset))
    }
}
