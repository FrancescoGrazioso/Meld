package com.meld.migration

import com.meld.spotify.FIRST
import com.meld.spotify.SpotifyBackend
import com.meld.spotify.SpotifyConnectionState.Disconnected
import com.meld.spotify.SpotifyConnectionState.Expired
import com.meld.spotify.TESTER
import com.metrolist.shared.constants.MeldSpotifyLikesPullKey
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.settings.secureSettingsKeys
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegacyMeldSettingsTest {
    /** What the pre-KMP app left in its DataStore, with made-up values. */
    private fun oldSettings(syncLikes: Boolean = true) =
        MapSettings(
            "enableSpotify" to true,
            "useSpotifyHome" to false,
            "useSpotifySearch" to true,
            "spotifyHomeOnly" to true,
            "spotifySyncLikes" to syncLikes,
            "hideYtmLikedSongs" to true,
            "spotifySpDc" to FIRST,
            "spotifySpKey" to "old-key",
            "spotifyAccessToken" to "old-token",
            "spotifyTokenExpiry" to 123L,
            "spotifyUsername" to "Someone",
            "spotifyUserId" to "someone",
            "musicSource" to "SPOTIFY",
            "spotifyLikedSortType" to "TITLE",
            "spotify_profile_tracks_json" to "[]",
            "spotify_gql_hashes_json" to "{}",
            "qobuzMatchOverrides" to "{\"s1\":\"q1\"}",
            "enableQobuz" to true,
        )

    @Test
    fun switchesAreCarriedOverAndTheRestOfTheOldSpotifySettingsIsRemoved() {
        val stored = oldSettings()

        val settings = Settings(stored)

        assertFalse(settings.meldSpotifyOnHome)
        assertTrue(settings.meldSearchSpotify)
        assertTrue(settings.meldHomeSpotifyOnly)
        assertTrue(settings.meldSpotifyLikesPush)
        assertEquals(
            setOf("meldSpotifyOnHome", "meldHomeSpotifyOnly", "meldSearchSpotify", "meldSpotifyLikesPush", "spotifySpDc", "qobuzMatchOverrides", "enableQobuz"),
            stored.keys.filterTo(mutableSetOf()) { "potify" in it || "obuz" in it || it == "musicSource" || it == "hideYtmLikedSongs" },
        )
        // The only credential left is the cookie, which every platform keeps in its encrypted store and out of backups.
        assertContains(secureSettingsKeys, LEGACY_MELD_SPOTIFY_COOKIE_KEY)
        assertEquals(FIRST, settings.meldLegacySpotifyCookie)
        assertTrue(settings.exportBackupSettings().entries.none { it.key == LEGACY_MELD_SPOTIFY_COOKIE_KEY || it.value == FIRST })
    }

    @Test
    fun oldLikeSyncNeverTurnsOnBringingSpotifyLikesIn() {
        for (syncLikes in listOf(true, false)) {
            val stored = oldSettings(syncLikes)

            val settings = Settings(stored)

            assertFalse(settings.meldSpotifyLikesPull)
            assertFalse(stored.hasKey(MeldSpotifyLikesPullKey))
            assertEquals(syncLikes, settings.meldSpotifyLikesPush)
        }
    }

    @Test
    fun aChoiceMadeInTheNewAppIsNotOverwritten() {
        val stored = oldSettings()
        stored.putBoolean("meldSpotifyOnHome", true)

        assertTrue(Settings(stored).meldSpotifyOnHome)
        assertTrue(Settings(stored).meldSpotifyOnHome)
    }

    @Test
    fun anOldBackupBringsTheSwitchesButNeverTheCookie() {
        val restored =
            Settings(MapSettings()).portableSettingsFromLegacy(
                mapOf("useSpotifyHome" to false, "spotifySyncLikes" to true, "spotifySpDc" to FIRST, "spotifyAccessToken" to "old-token"),
            )

        val entries = restored.entries.associate { it.key to it.value }
        assertEquals(mapOf("meldSpotifyLikesPush" to "true", "meldSpotifyOnHome" to "false"), entries)
    }

    @Test
    fun theOldCookieSignsInThroughTheOwnerAndIsThenForgotten() =
        runBlocking<Unit> {
            val backend = SpotifyBackend()
            val settings = Settings(oldSettings())
            val owner = backend.owner(this)

            adoptLegacySpotifySession(owner, settings)

            assertEquals(TESTER, owner.state.value)
            assertContains(backend.session, FIRST)
            assertEquals("", settings.meldLegacySpotifyCookie)
        }

    @Test
    fun aCookieSpotifyRefusesLeavesTheSessionExpired() =
        runBlocking<Unit> {
            val backend = SpotifyBackend().apply { accepted.clear() }
            val settings = Settings(oldSettings())
            val owner = backend.owner(this)

            adoptLegacySpotifySession(owner, settings)

            assertEquals(Expired(null), owner.state.value)
            assertEquals("", backend.session)
            assertEquals("", settings.meldLegacySpotifyCookie)
            assertEquals(Expired(null), backend.owner(this).state.value)
        }

    @Test
    fun theCookieIsKeptWhileSpotifyCannotBeReached() =
        runBlocking<Unit> {
            val backend = SpotifyBackend().apply { offline = true }
            val settings = Settings(oldSettings())
            val owner = backend.owner(this)

            adoptLegacySpotifySession(owner, settings)

            assertEquals(Disconnected, owner.state.value)
            assertEquals(FIRST, settings.meldLegacySpotifyCookie)

            backend.offline = false
            adoptLegacySpotifySession(owner, settings)

            assertEquals(TESTER, owner.state.value)
            assertEquals("", settings.meldLegacySpotifyCookie)
        }

    @Test
    fun aSessionFromTheNewAppIsNotReplacedByTheOldCookie() =
        runBlocking<Unit> {
            val backend = SpotifyBackend()
            val settings = Settings(MapSettings("spotifySpDc" to "stale-cookie"))
            val owner = backend.owner(this)
            owner.connect(FIRST)
            val session = backend.session

            adoptLegacySpotifySession(owner, settings)

            assertEquals(TESTER, owner.state.value)
            assertEquals(session, backend.session)
            assertEquals("", settings.meldLegacySpotifyCookie)
        }
}
