package com.meld.spotify.library

import com.meld.spotify.SpotifyAlbum
import com.meld.spotify.SpotifyArtist
import com.meld.spotify.SpotifyTrack
import com.meld.spotify.SpotifyTrackItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class SpotifyTrackArrangementTest {
    private fun item(
        id: String,
        name: String,
        artist: String,
        album: String,
        seconds: Int,
        added: String?,
    ) = SpotifyTrackItem(
        uid = id,
        uri = "spotify:track:$id",
        addedAt = added,
        track =
            SpotifyTrack(
                id = id,
                uri = "spotify:track:$id",
                name = name,
                artists = listOf(SpotifyArtist(id = artist, uri = "spotify:artist:$artist", name = artist)),
                album = SpotifyAlbum(id = album, uri = "spotify:album:$album", name = album),
                durationMs = seconds * 1000L,
                explicit = false,
            ),
    )

    private val list =
        listOf(
            item("a", "Numb", "Linkin Park", "Meteora", 187, "2026-01-03T10:00:00Z"),
            item("b", "Faint", "Linkin Park", "Meteora", 162, "2026-03-01T10:00:00Z"),
            item("c", "Feel Good Inc.", "Gorillaz", "Demon Days", 222, null),
            SpotifyTrackItem(uid = "d", uri = "spotify:episode:x", addedAt = "2026-02-01T10:00:00Z", track = null),
        )

    private fun ids(items: List<SpotifyTrackItem>) = items.map { it.uid }

    @Test
    fun thePlainViewIsTheListItself() {
        assertSame(list, list.arranged("", SpotifyTrackSort.SPOTIFY))
        assertSame(list, list.arranged("   ", SpotifyTrackSort.SPOTIFY))
    }

    @Test
    fun searchLooksAtTitleArtistAndAlbumAndWantsEveryWord() {
        assertEquals(listOf("a", "b"), ids(list.arranged("linkin", SpotifyTrackSort.SPOTIFY)))
        assertEquals(listOf("c"), ids(list.arranged("demon good", SpotifyTrackSort.SPOTIFY)))
        assertEquals(listOf("b"), ids(list.arranged("METEORA faint", SpotifyTrackSort.SPOTIFY)))
        assertEquals(emptyList(), ids(list.arranged("linkin demon", SpotifyTrackSort.SPOTIFY)))
    }

    @Test
    fun eachOrderSortsTheTracksAndLeavesOutWhatHasNone() {
        assertEquals(listOf("b", "c", "a"), ids(list.arranged("", SpotifyTrackSort.TITLE)))
        assertEquals(listOf("c", "a", "b"), ids(list.arranged("", SpotifyTrackSort.ARTIST)))
        assertEquals(listOf("c", "a", "b"), ids(list.arranged("", SpotifyTrackSort.ALBUM)))
        assertEquals(listOf("b", "a", "c"), ids(list.arranged("", SpotifyTrackSort.DURATION)))
        // Newest first; the one Spotify gives no date for comes last.
        assertEquals(listOf("b", "a", "c"), ids(list.arranged("", SpotifyTrackSort.DATE_ADDED)))
    }

    @Test
    fun theOrderOfEachListIsRememberedAndSpotifysOwnIsNotKept() {
        var stored = ""
        assertEquals(SpotifyTrackSort.SPOTIFY, spotifySortOf(stored, "PLAYLIST:a"))
        stored = withSpotifySort(stored, "PLAYLIST:a", SpotifyTrackSort.TITLE)
        stored = withSpotifySort(stored, "ALBUM:b", SpotifyTrackSort.DURATION)
        stored = withSpotifySort(stored, "PLAYLIST:a", SpotifyTrackSort.ARTIST)
        assertEquals(SpotifyTrackSort.ARTIST, spotifySortOf(stored, "PLAYLIST:a"))
        assertEquals(SpotifyTrackSort.DURATION, spotifySortOf(stored, "ALBUM:b"))
        stored = withSpotifySort(stored, "PLAYLIST:a", SpotifyTrackSort.SPOTIFY)
        assertEquals("ALBUM:b=DURATION", stored)
        // Only the newest lists are kept.
        assertEquals("c=TITLE", withSpotifySort(stored, "c", SpotifyTrackSort.TITLE, limit = 1))
    }
}
