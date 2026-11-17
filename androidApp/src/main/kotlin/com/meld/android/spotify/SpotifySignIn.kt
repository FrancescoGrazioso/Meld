package com.meld.android.spotify

import androidx.compose.runtime.Composable
import com.meld.spotify.ui.rememberSpotifySignIn
import com.metrolist.android.spotifyimport.SpotifyLoginScreen

/** Android's part of the Spotify sign-in: upstream's login screen as the window that captures the cookie. */
@Composable
fun rememberSpotifySignIn(): (() -> Unit)? =
    rememberSpotifySignIn { onCookie, onDismiss ->
        SpotifyLoginScreen(
            isAuthenticating = false,
            authenticationError = null,
            onClearAuthenticationError = {},
            onSessionCookie = onCookie,
            onNavigateBack = onDismiss,
        )
    }
