package com.meld.spotify.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import com.meld.spotify.SpotifyConnection
import org.koin.mp.KoinPlatform

/**
 * A Spotify sign-in around the platform's login window. The window only has to deliver the `sp_dc`
 * cookie or say it was closed; what the cookie leads to (connecting, the profile, why it failed) is
 * the [connection]'s state, the same on every platform. Every attempt opens a new window, so a retry
 * never meets the page that just failed.
 */
class SpotifySignIn(
    private val connection: SpotifyConnection,
    private val window: MutableState<Boolean> = mutableStateOf(false),
) {
    /** The login window is to be shown. */
    val capturing: Boolean get() = window.value

    fun start() {
        window.value = true
    }

    fun cancel() {
        window.value = false
    }

    /** Takes the cookie of the window that is open; one that arrives late or twice, or is empty, is dropped. */
    fun cookie(spDc: String) {
        if (!capturing) return
        window.value = false
        if (spDc.isNotBlank()) connection.login(spDc)
    }
}

/**
 * Returns the action that starts a Spotify sign-in, or null where Meld's module is not registered.
 * [capture] is the platform's whole part: show the login window, then call `onCookie` with `sp_dc`
 * or `onDismiss`.
 */
@Composable
fun rememberSpotifySignIn(capture: @Composable (onCookie: (String) -> Unit, onDismiss: () -> Unit) -> Unit): (() -> Unit)? {
    val connection = remember { KoinPlatform.getKoin().getOrNull<SpotifyConnection>() } ?: return null
    val window = rememberSaveable { mutableStateOf(false) }
    val signIn = remember(connection, window) { SpotifySignIn(connection, window) }
    if (signIn.capturing) capture(signIn::cookie, signIn::cancel)
    return signIn::start
}
