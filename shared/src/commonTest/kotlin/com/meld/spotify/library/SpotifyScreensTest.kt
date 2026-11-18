package com.meld.spotify.library

import com.meld.spotify.SpotifyTrackItem
import com.meld.spotify.ui.rowKeys
import com.metrolist.shared.ui.navigation.Screen
import com.metrolist.shared.ui.navigation.SpotifyFolderScreen
import com.metrolist.shared.ui.navigation.SpotifySettingsScreen
import com.metrolist.shared.ui.navigation.SpotifyTracksScreen
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class SpotifyScreensTest {
    // The back stack is saved through Screen's sealed serializer, which must know the routes declared outside Screen.kt.
    @Test
    fun routesSurviveTheBackStackSerializer() {
        val routes =
            listOf(
                SpotifyFolderScreen("spotify:folder:f1", "Folder"),
                SpotifyTracksScreen(SpotifyCollectionType.PLAYLIST, "p1", "Mix"),
                SpotifySettingsScreen,
            )
        // Routes with a `type` property, upstream's included, clash with JSON's default discriminator.
        val json = Json { classDiscriminator = "#class" }
        routes.forEach { route ->
            assertEquals(route, json.decodeFromString(Screen.serializer(), json.encodeToString(Screen.serializer(), route)))
        }
    }

    // A key used twice crashes a lazy list, and Spotify can repeat an entry across pages.
    @Test
    fun trackRowsAreKeyedByUidAndNoKeyIsUsedTwice() {
        fun entry(uid: String?) = SpotifyTrackItem(uid, null, null, null)
        assertEquals(listOf<Any>("a", 1, 2, "b"), listOf(entry("a"), entry(null), entry("a"), entry("b")).rowKeys())
    }
}
