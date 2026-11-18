package com.meld.spotify.library

import com.meld.spotify.parseHome
import com.meld.spotify.spotifyFixture
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SpotifyHomeFixtureTest {
    // The feed captured from the web player: shortcuts with an item of unknown kind, mixes, an episodes-only section, albums, artists.
    @Test
    fun theCapturedFeedBecomesRowsOfWhatTheAppCanOpen() {
        val data =
            Json
                .parseToJsonElement(spotifyFixture("home.json"))
                .jsonObject
                .getValue("data")
                .jsonObject
        val shelves = assertNotNull(parseHome(data, emptyList())).shelves()

        // The shortcuts have no title of their own and take the greeting; the episodes section is gone.
        assertEquals(listOf<String?>("Good afternoon", "Made For Test User", "Recommended for today", "Your favorite artists"), shelves.map { it.title })
        assertEquals(shelves.size, shelves.map { it.key }.toSet().size)

        val kinds = shelves.map { shelf -> shelf.entries.groupingBy { it.kind }.eachCount() }
        assertEquals(
            mapOf(SpotifyLibraryEntryKind.PLAYLIST to 4, SpotifyLibraryEntryKind.ARTIST to 2, SpotifyLibraryEntryKind.ALBUM to 3),
            kinds[0],
        )
        assertEquals(mapOf(SpotifyLibraryEntryKind.PLAYLIST to 2), kinds[1])
        assertEquals(mapOf(SpotifyLibraryEntryKind.ALBUM to 2), kinds[2])
        assertEquals(mapOf(SpotifyLibraryEntryKind.ARTIST to 2), kinds[3])
        shelves.forEach { shelf -> assertEquals(shelf.entries.size, shelf.entries.map { it.key }.toSet().size) }
    }
}
