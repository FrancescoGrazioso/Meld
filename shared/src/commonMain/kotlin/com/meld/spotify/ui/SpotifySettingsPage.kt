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
import com.meld.spotify.SpotifyFailure
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
import com.metrolist.shared.generated.resources.meld_spotify_error_changed
import com.metrolist.shared.generated.resources.meld_spotify_error_offline
import com.metrolist.shared.generated.resources.meld_spotify_error_rate_limited
import com.metrolist.shared.generated.resources.meld_spotify_error_rejected
import com.metrolist.shared.generated.resources.meld_spotify_error_unavailable
import com.metrolist.shared.generated.resources.meld_spotify_expired
import com.metrolist.shared.generated.resources.meld_spotify_expired_named
import com.metrolist.shared.generated.resources.meld_spotify_overview
import com.metrolist.shared.generated.resources.meld_spotify_status_expired
import com.metrolist.shared.generated.resources.meld_spotify_status_not_connected
import com.metrolist.shared.generated.resources.retry
import com.metrolist.shared.generated.resources.spotify_sign_in_again
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.settings.SettingsPageId
import com.metrolist.shared.ui.settings.SettingsTone
import com.metrolist.shared.ui.settings.SettingsUiRegistryBuilder
import com.metrolist.shared.ui.settings.settingsPageSubtitle
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.mp.KoinPlatform

/**
 * Registers the Spotify page and returns its id, or null where the platform has no Spotify
 * session (iOS) or no login window to open ([onSignIn] is null).
 */
@Composable
@NonRestartableComposable
internal fun SettingsUiRegistryBuilder.spotifyPage(onSignIn: (() -> Unit)?): SettingsPageId? {
    val connection = remember { KoinPlatform.getKoin().getOrNull<SpotifyConnection>() }
    if (connection == null || onSignIn == null) return null
    // State changes must rebuild the registry that owns these rows.
    val state by connection.state.collectAsState()
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
                    val message = stringResource(current.failure.message)
                    custom("spotifyFailed", message) {
                        SpotifySignInNotice(message, stringResource(Res.string.retry), onSignIn)
                    }
                }
            }
        }
    }
    return SettingsPageId.SPOTIFY
}

private val SpotifyFailure.message: StringResource
    get() =
        when (this) {
            SpotifyFailure.OFFLINE -> Res.string.meld_spotify_error_offline
            SpotifyFailure.RATE_LIMITED -> Res.string.meld_spotify_error_rate_limited
            SpotifyFailure.UNAVAILABLE -> Res.string.meld_spotify_error_unavailable
            SpotifyFailure.REJECTED -> Res.string.meld_spotify_error_rejected
            SpotifyFailure.CHANGED -> Res.string.meld_spotify_error_changed
        }

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
