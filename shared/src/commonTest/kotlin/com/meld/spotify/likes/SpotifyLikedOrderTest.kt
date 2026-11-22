package com.meld.spotify.likes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class SpotifyLikedOrderTest {
    @Test
    fun songsFromSpotifySwapPlacesByDateAndTheOthersStayWhereTheyAre() {
        // Upstream's list, newest first: y* were liked on YouTube Music, s* came from Spotify in a scrambled order.
        val recency = listOf("s1", "y1", "s3", "s2", "y2")
        val dates = mapOf("s1" to 100L, "s2" to 200L, "s3" to 300L)
        assertEquals(listOf("s3", "y1", "s2", "s1", "y2"), reorderBySpotifyDate(recency, dates))
    }

    @Test
    fun nothingKnownFromSpotifyLeavesUpstreamsListUntouched() {
        val recency = listOf("y1", "y2", "y3")
        assertSame(recency, reorderBySpotifyDate(recency, emptyMap()))
        assertSame(recency, reorderBySpotifyDate(recency, mapOf("y2" to 1L)))
        // A dated song that is not liked any more changes nothing either.
        assertSame(recency, reorderBySpotifyDate(recency, mapOf("gone" to 9L, "other" to 8L)))
    }

    @Test
    fun likesOfTheSameInstantKeepUpstreamsOrder() {
        val recency = listOf("a", "b", "c")
        assertEquals(listOf("c", "a", "b"), reorderBySpotifyDate(recency, mapOf("a" to 1L, "b" to 1L, "c" to 2L)))
    }
}
