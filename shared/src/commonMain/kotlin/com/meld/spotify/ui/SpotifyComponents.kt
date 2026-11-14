@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.meld.spotify.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meld.spotify.SpotifyConnectionState
import com.meld.spotify.SpotifyError
import com.meld.spotify.SpotifyTrack
import com.meld.spotify.SpotifyTrackItem
import com.meld.spotify.library.SpotifyPagerState
import com.meld.spotify.library.playableTrack
import com.meld.spotify.library.url
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.add_to_queue
import com.metrolist.shared.generated.resources.meld_spotify_error_changed
import com.metrolist.shared.generated.resources.meld_spotify_error_forbidden
import com.metrolist.shared.generated.resources.meld_spotify_error_offline
import com.metrolist.shared.generated.resources.meld_spotify_error_rate_limited
import com.metrolist.shared.generated.resources.meld_spotify_error_unavailable
import com.metrolist.shared.generated.resources.meld_spotify_expired
import com.metrolist.shared.generated.resources.meld_spotify_expired_named
import com.metrolist.shared.generated.resources.meld_spotify_item_unavailable
import com.metrolist.shared.generated.resources.meld_spotify_item_unavailable_description
import com.metrolist.shared.generated.resources.meld_spotify_loaded_of_total
import com.metrolist.shared.generated.resources.meld_spotify_not_connected
import com.metrolist.shared.generated.resources.meld_spotify_open_settings
import com.metrolist.shared.generated.resources.meld_spotify_track_unavailable
import com.metrolist.shared.generated.resources.more_options
import com.metrolist.shared.generated.resources.play_next
import com.metrolist.shared.generated.resources.retry
import com.metrolist.shared.generated.resources.search_no_results
import com.metrolist.shared.generated.resources.spotify_sign_in_again
import com.metrolist.shared.ui.components.Artwork
import com.metrolist.shared.ui.components.CollectionSongsLoadingPlaceholder
import com.metrolist.shared.ui.components.ContainedLoadingIndicator
import com.metrolist.shared.ui.components.GroupedListItem
import com.metrolist.shared.ui.components.ListItemTitle
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.navigation.LocalNavigator
import com.metrolist.shared.ui.navigation.SpotifySettingsScreen
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

internal val SpotifyError.text: StringResource
    get() =
        when (this) {
            is SpotifyError.Offline -> Res.string.meld_spotify_error_offline

            is SpotifyError.RateLimited -> Res.string.meld_spotify_error_rate_limited

            is SpotifyError.Unavailable -> Res.string.meld_spotify_error_unavailable

            is SpotifyError.Forbidden -> Res.string.meld_spotify_error_forbidden

            is SpotifyError.Changed -> Res.string.meld_spotify_error_changed

            is SpotifyError.SessionExpired,
            is SpotifyError.NotLoggedIn,
            -> Res.string.meld_spotify_expired
        }

/** Shows [content] to a connected account; anyone else is told why not and led to the Spotify settings. */
@Composable
internal fun SpotifySessionGate(
    session: SpotifyConnectionState,
    contentPadding: PaddingValues,
    content: @Composable () -> Unit,
) {
    val navigator = LocalNavigator.current
    val openSettings = { navigator.navigate(SpotifySettingsScreen) }
    when (session) {
        is SpotifyConnectionState.Connected -> {
            content()
        }

        is SpotifyConnectionState.Expired -> {
            Box(Modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) {
                SpotifyNotice(
                    message =
                        session.name?.let { stringResource(Res.string.meld_spotify_expired_named, it) }
                            ?: stringResource(Res.string.meld_spotify_expired),
                    action = stringResource(Res.string.spotify_sign_in_again),
                    onAction = openSettings,
                    isError = true,
                )
            }
        }

        SpotifyConnectionState.Connecting -> {
            Box(Modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) { ContainedLoadingIndicator() }
        }

        else -> {
            Box(Modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) {
                SpotifyNotice(
                    message = stringResource(Res.string.meld_spotify_not_connected),
                    action = stringResource(Res.string.meld_spotify_open_settings),
                    onAction = openSettings,
                )
            }
        }
    }
}

@Composable
internal fun SpotifyNotice(
    message: String,
    action: String?,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) Button(onClick = onAction, shapes = ButtonDefaults.shapes()) { Text(action) }
    }
}

/**
 * The end of a paged list: progress, the page that failed with a retry (what loaded stays above
 * it), or what an empty list means. [shown] is the number of rows left after a search.
 */
@Composable
internal fun SpotifyListStatus(
    state: SpotifyPagerState<*>,
    shown: Int,
    empty: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val error = state.error
    val total = state.total
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        when {
            error != null -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (state.items.isNotEmpty() && !state.complete && total != null) {
                        Text(
                            text = stringResource(Res.string.meld_spotify_loaded_of_total, state.items.size, total),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                    SpotifyNotice(stringResource(error.text), stringResource(Res.string.retry), onRetry, isError = true)
                }
            }

            state.items.isEmpty() && !state.complete -> {
                CollectionSongsLoadingPlaceholder()
            }

            state.loading -> {
                ContainedLoadingIndicator(Modifier.padding(16.dp))
            }

            shown == 0 -> {
                SpotifyNotice(if (state.items.isEmpty()) empty else stringResource(Res.string.search_no_results), null, {})
            }
        }
    }
}

/**
 * A Spotify track as a row of upstream's grouped lists. Entries that cannot be played stay
 * visible but disabled. The menu is Meld's own: [onPlayNext] and [onAddToQueue] are null where
 * nothing can play Spotify tracks, and then there is no menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SpotifyTrackRow(
    item: SpotifyTrackItem,
    index: Int,
    count: Int,
    numbered: Boolean,
    onClick: (() -> Unit)?,
    onPlayNext: ((SpotifyTrack) -> Unit)?,
    onAddToQueue: ((SpotifyTrack) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val track = item.track
    val playable = item.playableTrack
    val hasMenu = playable != null && onPlayNext != null && onAddToQueue != null
    var menuOpen by remember { mutableStateOf(false) }
    GroupedListItem(index = index, count = count, modifier = modifier) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .alpha(if (playable != null) 1f else 0.38f)
                    .clip(RoundedCornerShape(8.dp))
                    .combinedClickable(
                        enabled = playable != null && onClick != null,
                        onClick = { onClick?.invoke() },
                        onLongClick = if (hasMenu) ({ menuOpen = true }) else null,
                    ).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (numbered) {
                Box(Modifier.width(36.dp), contentAlignment = Alignment.Center) {
                    Text("${index + 1}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(8.dp))
            } else {
                Artwork(
                    thumbnail = track?.album?.images?.url(TRACK_IMAGE_SIZE),
                    icon = MetrolistIcons.Default.MusicNote,
                    modifier = Modifier.size(48.dp),
                    iconSize = 24.dp,
                    cornerRadius = 4.dp,
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                ListItemTitle(
                    text = track?.name ?: stringResource(Res.string.meld_spotify_item_unavailable),
                    isExplicit = track?.explicit == true,
                )
                Text(
                    text =
                        when {
                            track == null -> {
                                stringResource(Res.string.meld_spotify_item_unavailable_description)
                            }

                            playable == null -> {
                                stringResource(Res.string.meld_spotify_track_unavailable)
                            }

                            else -> {
                                listOf(track.artists.joinToString { it.name }, track.durationMs.asDuration())
                                    .filter(String::isNotEmpty)
                                    .joinToString(" • ")
                            }
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (playable != null && hasMenu) {
                Box {
                    IconButton(onClick = { menuOpen = true }, shapes = IconButtonDefaults.shapes()) {
                        Icon(MetrolistIcons.Default.MoreVert, contentDescription = stringResource(Res.string.more_options))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.play_next)) },
                            onClick = {
                                menuOpen = false
                                onPlayNext(playable)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.add_to_queue)) },
                            onClick = {
                                menuOpen = false
                                onAddToQueue(playable)
                            },
                        )
                    }
                }
            }
        }
    }
}

private const val TRACK_IMAGE_SIZE = 64

private fun Long.asDuration(): String {
    if (this <= 0) return ""
    val seconds = this / 1000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}
