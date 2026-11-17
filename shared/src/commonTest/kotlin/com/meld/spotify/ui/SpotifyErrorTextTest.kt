package com.meld.spotify.ui

import com.meld.spotify.SpotifyError
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.meld_spotify_error_changed
import com.metrolist.shared.generated.resources.meld_spotify_error_forbidden
import com.metrolist.shared.generated.resources.meld_spotify_error_offline
import com.metrolist.shared.generated.resources.meld_spotify_error_rate_limited
import com.metrolist.shared.generated.resources.meld_spotify_error_rejected
import com.metrolist.shared.generated.resources.meld_spotify_error_unavailable
import com.metrolist.shared.generated.resources.meld_spotify_expired
import kotlin.test.Test
import kotlin.test.assertEquals

class SpotifyErrorTextTest {
    private val cause = RuntimeException("cause")

    @Test
    fun everyErrorHasOneTextWhereverItIsShown() {
        val texts =
            mapOf(
                SpotifyError.Offline(cause) to Res.string.meld_spotify_error_offline,
                SpotifyError.RateLimited(3) to Res.string.meld_spotify_error_rate_limited,
                SpotifyError.Unavailable(503) to Res.string.meld_spotify_error_unavailable,
                SpotifyError.Forbidden("playlist") to Res.string.meld_spotify_error_forbidden,
                SpotifyError.Changed("shape") to Res.string.meld_spotify_error_changed,
            )
        texts.forEach { (error, text) ->
            assertEquals(text, error.text(), error.toString())
            assertEquals(text, error.text(signingIn = true), error.toString())
        }
    }

    @Test
    fun aRefusedSessionIsExpiredInTheLibraryAndNotAcceptedAtSignIn() {
        listOf(SpotifyError.SessionExpired(), SpotifyError.NotLoggedIn()).forEach { error ->
            assertEquals(Res.string.meld_spotify_expired, error.text(), error.toString())
            assertEquals(Res.string.meld_spotify_error_rejected, error.text(signingIn = true), error.toString())
        }
    }
}
