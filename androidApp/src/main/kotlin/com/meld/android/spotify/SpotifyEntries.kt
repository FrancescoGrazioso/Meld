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
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.meld.spotify.ui.SpotifyScreen
import com.metrolist.android.LocalMiniPlayerBottomInset
import com.metrolist.android.ui.navTabMetadata
import com.metrolist.android.ui.screens.settings.IntegrationsScreen
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.back
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.navigation.Navigator
import com.metrolist.shared.ui.navigation.Screen
import com.metrolist.shared.ui.navigation.SpotifyFolderScreen
import com.metrolist.shared.ui.navigation.SpotifySettingsScreen
import com.metrolist.shared.ui.navigation.SpotifyTracksScreen
import com.metrolist.shared.ui.settings.SettingsPageId
import com.metrolist.shared.ui.tv.tvFocusHighlight
import org.jetbrains.compose.resources.stringResource

/** Meld's Spotify routes, added to the app's entry provider. */
fun EntryProviderScope<NavKey>.spotifyEntries(navigator: Navigator) {
    entry<SpotifyFolderScreen>(metadata = navTabMetadata) { SpotifyScreenHost(it, navigator) }
    entry<SpotifyTracksScreen>(metadata = navTabMetadata) { SpotifyScreenHost(it, navigator) }
    entry<SpotifySettingsScreen>(metadata = navTabMetadata) {
        IntegrationsScreen(
            onNavigateBack = { navigator.goBack() },
            onNavigateToListenTogether = { navigator.navigate(Screen.ListenTogetherSettings) },
            initialPage = SettingsPageId.SPOTIFY,
        )
    }
}

/** Android's frame around the shared screen: the top bar with back, the cutout and the mini player. Refresh is the pull gesture. */
@Composable
private fun SpotifyScreenHost(
    screen: Screen,
    navigator: Navigator,
) {
    val miniPlayerInset = LocalMiniPlayerBottomInset.current
    SpotifyScreen(screen, navigator::navigate, { navigator.goBack() }) { title, _, content ->
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
}
