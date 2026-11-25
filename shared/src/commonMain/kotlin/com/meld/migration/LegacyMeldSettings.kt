package com.meld.migration

import com.meld.spotify.SpotifySessionOwner
import com.metrolist.shared.constants.MeldSpotifyLikesPushKey
import com.metrolist.shared.constants.MeldSpotifyOnHomeKey
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.settings.removeExisting
import com.russhwolf.settings.Settings as KmpSettings

/**
 * The `sp_dc` cookie of the pre-KMP app, under the key that app used. Being a secure key, each platform
 * moves it out of the plain settings into its encrypted store; it stays there only until
 * [com.meld.spotify.SpotifySessionOwner.adopt] has signed in with it.
 */
const val LEGACY_MELD_SPOTIFY_COOKIE_KEY = "spotifySpDc"

/**
 * Old switch to the setting that took over its meaning. "Sync likes" both sent likes to Spotify and
 * replaced the liked songs with Spotify's: only the sending half is carried over, because bringing Spotify
 * likes in also likes them on YouTube Music and must be turned on by the user.
 */
private val legacyToggles =
    mapOf(
        "useSpotifyHome" to MeldSpotifyOnHomeKey,
        "spotifyHomeOnly" to "meldHomeSpotifyOnly",
        "useSpotifySearch" to "meldSearchSpotify",
        "spotifySyncLikes" to MeldSpotifyLikesPushKey,
    )

private val droppedKeys =
    arrayOf(
        // Being signed in is the switch now.
        "enableSpotify",
        "hideYtmLikedSongs",
        // Never read by the old app.
        "musicSource",
        // The Spotify screens have no sort options yet.
        "spotifyPlaylistSortType",
        "spotifyPlaylistSortDescending",
        "spotifyLikedSortType",
        "spotifyLikedSortDescending",
        // Clear-text credentials and identity: the session is rebuilt from the cookie alone.
        "spotifySpKey",
        "spotifyAccessToken",
        "spotifyTokenExpiry",
        "spotifyUsername",
        "spotifyUserId",
        // Caches Spotify fills again.
        "spotify_profile_tracks_json",
        "spotify_profile_artists_json",
        "spotify_profile_cache_ts",
        "spotify_profile_had_rest",
        "spotify_followed_artists_json",
        "spotify_followed_artists_ts",
        "spotify_related_artist_names_json",
        "spotify_related_artist_names_ts",
        "spotify_cached_playlists_json",
        "spotify_gql_hashes_json",
        "spotify_gql_hashes_last_fetch",
    )

/**
 * Carries the pre-KMP app's Spotify settings over in place and removes what is left of them. Runs on every
 * start and does nothing once they are gone. The Qobuz settings, the user's `qobuzMatchOverrides` among
 * them, are left where they are for the Qobuz port.
 */
internal fun migrateLegacyMeldSettings(settings: KmpSettings) {
    legacyToggles.forEach { (old, new) ->
        val value = runCatching { settings.getBooleanOrNull(old) }.getOrNull() ?: return@forEach
        if (!settings.hasKey(new)) settings.putBoolean(new, value)
    }
    settings.removeExisting(*legacyToggles.keys.toTypedArray(), *droppedKeys)
}

/** The same for the settings of an old backup, where the cookie must not reach the staged restore either. */
internal fun mapLegacyMeldSettings(legacy: MutableMap<String, Any>) {
    legacyToggles.forEach { (old, new) -> legacy.remove(old)?.let { legacy.getOrPut(new) { it } } }
    legacy.remove(LEGACY_MELD_SPOTIFY_COOKIE_KEY)
}

/** Signs the user of the pre-KMP app back in; its cookie is forgotten as soon as Spotify has answered for it. */
suspend fun adoptLegacySpotifySession(
    owner: SpotifySessionOwner,
    settings: Settings,
) {
    val cookie = settings.meldLegacySpotifyCookie
    if (cookie.isNotBlank() && owner.adopt(cookie)) settings.meldLegacySpotifyCookie = ""
}
