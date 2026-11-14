package com.meld.desktop.spotify

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.meld.spotify.SpotifyConnection
import com.metrolist.desktop.spotifyimport.SpotifyLoginDialog
import org.koin.compose.koinInject

/**
 * Returns the action that opens upstream's Spotify login dialog. The captured cookie goes to the
 * session owner, which reports the outcome through its state, so every attempt starts on a fresh dialog.
 */
@Composable
fun rememberSpotifySignIn(): () -> Unit {
    val connection = koinInject<SpotifyConnection>()
    var visible by remember { mutableStateOf(false) }
    if (visible) {
        SpotifyLoginDialog(
            isAuthenticating = false,
            authenticationFailed = false,
            onDismissRequest = { visible = false },
            onSessionCookie = {
                visible = false
                connection.login(it)
            },
        )
    }
    return { visible = true }
}
