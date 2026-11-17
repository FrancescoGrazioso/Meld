@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.meld.spotify.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meld.spotify.SpotifyConnection
import com.meld.spotify.SpotifyConnectionState
import com.meld.spotify.likes.SpotifyLikeSync
import com.meld.spotify.likes.SpotifyLikeSyncStatus
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.account
import com.metrolist.shared.generated.resources.import_provider_spotify
import com.metrolist.shared.generated.resources.meld_spotify_connect
import com.metrolist.shared.generated.resources.meld_spotify_connect_description
import com.metrolist.shared.generated.resources.meld_spotify_connected
import com.metrolist.shared.generated.resources.meld_spotify_connected_as
import com.metrolist.shared.generated.resources.meld_spotify_connecting
import com.metrolist.shared.generated.resources.meld_spotify_disconnect
import com.metrolist.shared.generated.resources.meld_spotify_disconnect_confirm
import com.metrolist.shared.generated.resources.meld_spotify_disconnect_description
import com.metrolist.shared.generated.resources.meld_spotify_expired
import com.metrolist.shared.generated.resources.meld_spotify_expired_named
import com.metrolist.shared.generated.resources.meld_spotify_likes
import com.metrolist.shared.generated.resources.meld_spotify_likes_importing
import com.metrolist.shared.generated.resources.meld_spotify_likes_last_sync
import com.metrolist.shared.generated.resources.meld_spotify_likes_never_synced
import com.metrolist.shared.generated.resources.meld_spotify_likes_not_found
import com.metrolist.shared.generated.resources.meld_spotify_likes_pending
import com.metrolist.shared.generated.resources.meld_spotify_likes_pull
import com.metrolist.shared.generated.resources.meld_spotify_likes_pull_description
import com.metrolist.shared.generated.resources.meld_spotify_likes_push
import com.metrolist.shared.generated.resources.meld_spotify_likes_push_description
import com.metrolist.shared.generated.resources.meld_spotify_likes_sync_now
import com.metrolist.shared.generated.resources.meld_spotify_overview
import com.metrolist.shared.generated.resources.meld_spotify_status_expired
import com.metrolist.shared.generated.resources.meld_spotify_status_not_connected
import com.metrolist.shared.generated.resources.retry
import com.metrolist.shared.generated.resources.spotify_sign_in_again
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.settings.SettingsPageId
import com.metrolist.shared.ui.settings.SettingsTone
import com.metrolist.shared.ui.settings.SettingsUiRegistryBuilder
import com.metrolist.shared.ui.settings.settingsPageSubtitle
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import org.koin.mp.KoinPlatform
import kotlin.time.Instant

/**
 * Registers the Spotify page and returns its id, or null where Meld's module is not registered or
 * the host has no login window to open ([onSignIn] is null).
 */
@Composable
@NonRestartableComposable
internal fun SettingsUiRegistryBuilder.spotifyPage(onSignIn: (() -> Unit)?): SettingsPageId? {
    val connection = remember { KoinPlatform.getKoin().getOrNull<SpotifyConnection>() }
    if (connection == null || onSignIn == null) return null
    // State changes must rebuild the registry that owns these rows.
    val state by connection.state.collectAsState()
    val likeSync = remember { KoinPlatform.getKoin().getOrNull<SpotifyLikeSync>() }
    val likeStatus = likeSync?.status?.collectAsState()?.value
    val settings = Settings.shared
    LaunchedEffect(connection) { connection.refresh() }

    val status =
        when (val current = state) {
            is SpotifyConnectionState.Connected -> current.name ?: stringResource(Res.string.meld_spotify_connected)
            is SpotifyConnectionState.Expired -> stringResource(Res.string.meld_spotify_status_expired)
            SpotifyConnectionState.Connecting -> stringResource(Res.string.meld_spotify_connecting)
            else -> stringResource(Res.string.meld_spotify_status_not_connected)
        }
    page(
        id = SettingsPageId.SPOTIFY,
        title = stringResource(Res.string.import_provider_spotify),
        subtitle = settingsPageSubtitle(stringResource(Res.string.meld_spotify_overview), status),
        icon = MetrolistIcons.Outlined.LibraryMusic,
        tone = SettingsTone.GREEN,
    ) {
        group("spotifyAccount", stringResource(Res.string.account)) {
            when (val current = state) {
                SpotifyConnectionState.Disconnected -> {
                    action(
                        id = "spotifyConnect",
                        title = stringResource(Res.string.meld_spotify_connect),
                        description = stringResource(Res.string.meld_spotify_connect_description),
                        onClick = onSignIn,
                    )
                }

                SpotifyConnectionState.Connecting -> {
                    action(
                        id = "spotifyConnecting",
                        title = stringResource(Res.string.meld_spotify_connecting),
                        enabled = false,
                    ) {}
                }

                is SpotifyConnectionState.Connected -> {
                    action(
                        id = "spotifyDisconnect",
                        title =
                            current.name?.let { stringResource(Res.string.meld_spotify_connected_as, it) }
                                ?: stringResource(Res.string.meld_spotify_connected),
                        description = stringResource(Res.string.meld_spotify_disconnect_description),
                        valueLabel = stringResource(Res.string.meld_spotify_disconnect),
                        confirmationMessage = stringResource(Res.string.meld_spotify_disconnect_confirm),
                        onClick = connection::logout,
                    )
                }

                is SpotifyConnectionState.Expired -> {
                    val message =
                        current.name?.let { stringResource(Res.string.meld_spotify_expired_named, it) }
                            ?: stringResource(Res.string.meld_spotify_expired)
                    custom("spotifyExpired", message) {
                        SpotifySignInNotice(message, stringResource(Res.string.spotify_sign_in_again), onSignIn)
                    }
                }

                is SpotifyConnectionState.Failed -> {
                    val message = stringResource(current.error.text(signingIn = true))
                    custom("spotifyFailed", message) {
                        SpotifySignInNotice(message, stringResource(Res.string.retry), onSignIn)
                    }
                }
            }
        }
        if (likeSync != null && likeStatus != null && state is SpotifyConnectionState.Connected) {
            group("spotifyLikes", stringResource(Res.string.meld_spotify_likes)) {
                switch(
                    id = "spotifyLikesPush",
                    title = stringResource(Res.string.meld_spotify_likes_push),
                    description = stringResource(Res.string.meld_spotify_likes_push_description),
                    checked = settings.meldSpotifyLikesPush,
                ) {
                    settings.meldSpotifyLikesPush = it
                    if (it) likeSync.syncNow()
                }
                switch(
                    id = "spotifyLikesPull",
                    title = stringResource(Res.string.meld_spotify_likes_pull),
                    description = stringResource(Res.string.meld_spotify_likes_pull_description),
                    checked = settings.meldSpotifyLikesPull,
                ) {
                    settings.meldSpotifyLikesPull = it
                    if (it) likeSync.syncNow()
                }
                action(
                    id = "spotifyLikesSyncNow",
                    title = stringResource(Res.string.meld_spotify_likes_sync_now),
                    description = likeStatus.text(),
                    enabled = !likeStatus.syncing && (settings.meldSpotifyLikesPush || settings.meldSpotifyLikesPull),
                    onClick = likeSync::syncNow,
                )
            }
        }
    }
    return SettingsPageId.SPOTIFY
}

/** The status line: the import while it runs, else the last sync, then what waits and what Spotify does not have. */
@Composable
private fun SpotifyLikeSyncStatus.text(): String {
    val progress =
        if (importTotal > 0) {
            stringResource(Res.string.meld_spotify_likes_importing, importDone, importTotal)
        } else if (syncedAt > 0) {
            val time = Instant.fromEpochMilliseconds(syncedAt).toLocalDateTime(TimeZone.currentSystemDefault())
            stringResource(Res.string.meld_spotify_likes_last_sync, "${time.date} ${time.hour.twoDigits()}:${time.minute.twoDigits()}")
        } else {
            stringResource(Res.string.meld_spotify_likes_never_synced)
        }
    return listOfNotNull(
        progress,
        stringResource(Res.string.meld_spotify_likes_pending, pending).takeIf { pending > 0 },
        stringResource(Res.string.meld_spotify_likes_not_found, notFound).takeIf { notFound > 0 },
    ).joinToString(" • ")
}

private fun Int.twoDigits() = toString().padStart(2, '0')

@Composable
private fun SpotifySignInNotice(
    message: String,
    buttonLabel: String,
    onSignIn: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(MetrolistIcons.Outlined.Warning, null, tint = MaterialTheme.colorScheme.error)
            Text(message, color = MaterialTheme.colorScheme.error)
        }
        Button(onClick = onSignIn, shapes = ButtonDefaults.shapes()) { Text(buttonLabel) }
    }
}
