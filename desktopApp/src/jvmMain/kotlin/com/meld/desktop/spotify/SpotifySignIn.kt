package com.meld.desktop.spotify

import androidx.compose.runtime.Composable
import com.meld.spotify.ui.rememberSpotifySignIn
import com.metrolist.desktop.spotifyimport.SpotifyLoginDialog

/** The desktop's part of the Spotify sign-in: upstream's login dialog as the window that captures the cookie. */
@Composable
fun rememberSpotifySignIn(): (() -> Unit)? =
    rememberSpotifySignIn { onCookie, onDismiss ->
        SpotifyLoginDialog(
            isAuthenticating = false,
            authenticationFailed = false,
            onDismissRequest = onDismiss,
            onSessionCookie = onCookie,
        )
    }
