package com.meld.spotify.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meld.spotify.library.SpotifyHomeShelf
import com.meld.spotify.library.SpotifyInHome
import com.meld.spotify.library.SpotifyLibraryEntry
import com.meld.spotify.library.staysOnSpotifyOnlyHome
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.import_provider_spotify
import com.metrolist.shared.generated.resources.new_releases
import com.metrolist.shared.ui.components.DesktopHorizontalScrollControls
import com.metrolist.shared.ui.components.SectionTitle
import com.metrolist.shared.ui.navigation.LocalNavigator
import com.metrolist.shared.ui.navigation.Screen
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.navigation.SpotifySettingsScreen
import com.metrolist.shared.ui.screens.HomeShelf
import org.jetbrains.compose.resources.stringResource
import org.koin.mp.KoinPlatform

/**
 * Meld's part of the app's home screen: the Spotify feed as rows of cards, or the one row that
 * stands for it when the session expired or it did not load. [None] leaves the screen as it is.
 */
@Stable
class HomeExtras internal constructor(
    private val shelves: List<SpotifyHomeShelf> = emptyList(),
    private val status: (@Composable () -> Unit)? = null,
    private val onOpen: (Screen) -> Unit = {},
    /** Spotify stands in for the app's own recommendations, see [SpotifyInHomeState.replacesHome]. */
    val spotifyOnly: Boolean = false,
) {
    /** The app's own rows that the home screen shows: all of them, or those that stay on a Spotify-only home. */
    fun shelves(all: List<HomeShelf>): List<HomeShelf> = if (spotifyOnly) all.filter { staysOnSpotifyOnlyHome(it.key) } else all

    /** The home screen's "load more", or null on a Spotify-only home, where more would only be rows that are not shown. */
    fun loadMore(onLoadMore: (() -> Unit)?): (() -> Unit)? = onLoadMore.takeUnless { spotifyOnly }

    /**
     * What a host puts above the rows: YouTube Music's chips, and its loading and error rows. None of it
     * belongs on a Spotify-only home, whose own state is the Spotify rows.
     */
    fun header(header: (LazyListScope.() -> Unit)?): (LazyListScope.() -> Unit)? = header.takeUnless { spotifyOnly }

    /** The Spotify rows as items of the home list. They come first: Spotify is what Meld is opened for. */
    fun items(
        scope: LazyListScope,
        isWide: Boolean,
    ) {
        status?.let { status ->
            scope.item(key = "spotify_home_status", contentType = "spotify_home_status") { HomeFrame(isWide, inset = true) { status() } }
        }
        scope.items(shelves, key = { "spotify_home_${it.key}" }, contentType = { "spotify_home_shelf" }) { shelf ->
            HomeFrame(isWide, inset = false) { SpotifyShelf(shelf, isWide, onOpen) }
        }
    }

    companion object {
        val None = HomeExtras()

        /** A Spotify-only home whose feed has not arrived yet. */
        internal val Waiting = HomeExtras(spotifyOnly = true)
    }
}

/**
 * The Spotify side of the home screen, or [HomeExtras.None] while there is nothing to show: no
 * account, the feed switched off in the Spotify settings, or the feed still loading. It holds the
 * app's own recommendations back only on the Spotify-only home the user can ask for in the settings.
 *
 * [onOpen] opens a Spotify route; a host that provides upstream's [LocalNavigator] leaves it out.
 */
@Composable
fun spotifyHomeExtras(onOpen: ((Screen) -> Unit)? = null): HomeExtras {
    val home = remember { KoinPlatform.getKoinOrNull()?.getOrNull<SpotifyInHome>() } ?: return HomeExtras.None
    val state by home.state.collectAsState()
    val error = state.error
    val spotifyOnly = state.replacesHome(Settings.shared.meldHomeSpotifyOnly)
    if (!state.expired && error == null && state.shelves.isEmpty()) return if (spotifyOnly) HomeExtras.Waiting else HomeExtras.None

    val open by rememberUpdatedState(onOpen ?: LocalNavigator.current::navigate)
    return remember(home, state, spotifyOnly) {
        HomeExtras(
            spotifyOnly = spotifyOnly,
            shelves = state.shelves,
            status =
                when {
                    state.expired -> {
                        { SpotifyExpiredRow { open(SpotifySettingsScreen) } }
                    }

                    error != null -> {
                        { SpotifyFailureRow(error, home::refresh) }
                    }

                    else -> {
                        null
                    }
                },
            onOpen = { open(it) },
        )
    }
}

/** Centres a home row the way the app's own shelves are, with their side margin when [inset]. */
@Composable
private fun HomeFrame(
    isWide: Boolean,
    inset: Boolean,
    content: @Composable () -> Unit,
) {
    val margin = if (isWide) 24.dp else 16.dp
    Box(Modifier.fillMaxWidth().padding(bottom = margin), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = 1400.dp).fillMaxWidth().padding(horizontal = if (inset) margin else 0.dp)) { content() }
    }
}

@Composable
private fun SpotifyShelf(
    shelf: SpotifyHomeShelf,
    isWide: Boolean,
    onOpen: (Screen) -> Unit,
) {
    val margin = if (isWide) 24.dp else 16.dp
    val fallback = if (shelf.key == SpotifyHomeShelf.NEW_RELEASES) Res.string.new_releases else Res.string.import_provider_spotify
    Column(Modifier.fillMaxWidth()) {
        SectionTitle(
            title = shelf.title ?: stringResource(fallback),
            modifier = Modifier.padding(horizontal = margin),
            isWide = isWide,
        )
        Spacer(Modifier.height(10.dp))
        val rowState = rememberLazyListState()
        DesktopHorizontalScrollControls(rowState) {
            LazyRow(state = rowState, contentPadding = PaddingValues(horizontal = margin)) {
                items(shelf.entries, key = SpotifyLibraryEntry::key, contentType = { "entry" }) { entry ->
                    Box(Modifier.width(if (isWide) 180.dp else 160.dp)) {
                        SpotifyLibraryCell(entry, isGridView = true) { onOpen(entry.screen()) }
                    }
                }
            }
        }
    }
}
