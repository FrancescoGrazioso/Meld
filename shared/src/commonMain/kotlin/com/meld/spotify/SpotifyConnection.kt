package com.meld.spotify

import kotlinx.coroutines.flow.StateFlow

sealed interface SpotifyConnectionState {
    data object Disconnected : SpotifyConnectionState

    data object Connecting : SpotifyConnectionState

    /** The integration is on: there is no separate switch to enable. */
    data class Connected(
        val name: String?,
        val imageUrl: String?,
    ) : SpotifyConnectionState

    /** Spotify no longer accepts the session; survives a restart until the next successful sign-in. */
    data class Expired(
        val name: String?,
    ) : SpotifyConnectionState

    /** A sign-in that did not go through; nothing that was stored has changed. */
    data class Failed(
        val error: SpotifyError,
    ) : SpotifyConnectionState
}

/** What shared UI may know about the Spotify session. */
interface SpotifyConnection {
    val state: StateFlow<SpotifyConnectionState>

    /** Signs in with the `sp_dc` cookie captured by the platform's login window. */
    fun login(spDc: String)

    fun logout()

    /** Checks the stored session against Spotify and reloads the profile. */
    fun refresh()
}
