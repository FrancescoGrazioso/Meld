package com.meld.android.spotify

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.meld.spotify.SpotifyConnection
import com.metrolist.android.spotifyimport.SpotifyLoginScreen
import org.koin.compose.koinInject

/**
 * Returns the action that opens upstream's Spotify login window. The captured cookie goes to the
 * session owner, which reports the outcome through its state, so every attempt starts on a fresh window.
 */
@Composable
fun rememberSpotifySignIn(): () -> Unit {
    val connection = koinInject<SpotifyConnection>()
    var visible by rememberSaveable { mutableStateOf(false) }
    if (visible) {
        SpotifyLoginScreen(
            isAuthenticating = false,
            authenticationError = null,
            onClearAuthenticationError = {},
            onSessionCookie = {
                visible = false
                connection.login(it)
            },
            onNavigateBack = { visible = false },
        )
    }
    return { visible = true }
}
