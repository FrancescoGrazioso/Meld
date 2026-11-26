package com.meld.spotify.likes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

private const val DAY = 24L * 60 * 60 * 1000

/** Names say what a song is: y* liked on YouTube only, c* copied from Spotify, a* an anchor, o* the user's own on both. */
private fun data(vararg songs: Pair<String, Long>) =
    SpotifyLikeOrderData(
        dates = songs.toMap(),
        origins =
            songs.associate { (id, _) ->
                id to
                    when (id.first()) {
                        'a' -> LikeOrigin.ANCHOR
                        'o' -> LikeOrigin.OWN
                        'u' -> LikeOrigin.UNKNOWN
                        else -> LikeOrigin.COPIED
                    }
            },
    )

class SpotifyLikedOrderTest {
    @Test
    fun nothingFromSpotifyLeavesTheListAsItIs() {
        val recency = listOf("y1", "y2")
        assertSame(recency, orderLikes(recency, SpotifyLikeOrderData()))
        // Dates of songs that are not liked any more, and likes that are all the user's own.
        assertSame(recency, orderLikes(recency, data("c9" to 9L)))
        val own = listOf("a2", "y1", "o1", "a1")
        assertSame(own, orderLikes(own, data("a2" to 200L, "o1" to 5L, "a1" to 100L)))
    }

    @Test
    fun aLibraryCopiedInBulkGoesUnderTheLikesTheUserMadeOnYouTube() {
        // The report: likes on Spotify for years, then likes on YouTube Music, then Meld copies the Spotify
        // ones over, which YouTube lists on top, in whatever order they were sent.
        val recency = listOf("c2", "c3", "c1", "y3", "y2", "y1")
        val ordered = orderLikes(recency, data("c1" to 100L, "c2" to 200L, "c3" to 300L))
        assertEquals(listOf("y3", "y2", "y1", "c3", "c2", "c1"), ordered)
    }

    @Test
    fun copiesArePlacedAgainstTheLikesThatHaveADate() {
        // Since the bulk copy the user liked a3 and a2 in Meld, with a YouTube-only like between them and one after.
        val recency = listOf("y9", "a3", "y8", "a2", "c5", "c1", "c4", "y1")
        val dates = data("a3" to 900L, "a2" to 800L, "c5" to 850L, "c1" to 100L, "c4" to 400L)
        // c5 is newer than a2: right above it, under the undated y8 that YouTube lists above a2.
        assertEquals(listOf("y9", "a3", "y8", "c5", "a2", "y1", "c4", "c1"), orderLikes(recency, dates))
    }

    @Test
    fun aLikeOfTheUsersOwnKeepsItsPlaceWhateverSpotifySaysOfIt() {
        // o1 was liked on Spotify long ago and on YouTube last week: it stays where YouTube has it.
        val recency = listOf("a2", "o1", "a1", "c1")
        assertEquals(listOf("a2", "o1", "a1", "c1"), orderLikes(recency, data("a2" to 900L, "o1" to 10L, "a1" to 800L, "c1" to 100L)))
    }

    @Test
    fun anAnchorDatedAgainstTheListDoesNotReorderTheUsersLikes() {
        // a1's date says it is newer than a2 above it; the copies are placed as if it were a2's.
        val recency = listOf("a2", "a1", "c9", "c3")
        assertEquals(listOf("c9", "a2", "a1", "c3"), orderLikes(recency, data("a2" to 500L, "a1" to 700L, "c9" to 600L, "c3" to 300L)))
    }

    @Test
    fun likesOfAnUnknownOriginAreTakenForCopiesAndTiesKeepTheListsOrder() {
        val recency = listOf("u1", "y1", "u3", "u2")
        assertEquals(listOf("y1", "u3", "u1", "u2"), orderLikes(recency, data("u1" to 5L, "u2" to 5L, "u3" to 7L)))
    }

    private fun like(
        id: String,
        likedAt: Long,
        matchedAfter: Long?,
    ) = UnresolvedLike(id, likedAt, matchedAfter?.let { likedAt + it })

    @Test
    fun theOriginOfOldLikesIsToldFromTheMatchAndTheShapeOfTheList() {
        // Newest first: two likes made in Meld, a YouTube-only like, a library of twelve brought in at once in a
        // scrambled order, then what YouTube had before: its own likes, one of them also liked on Spotify once.
        val pushed = listOf(like("p2", 1_000 * DAY, 0), like("p1", 990 * DAY, -3 * DAY))
        val bulk = (1..12).map { like("b$it", it * DAY, 900 * DAY) }.shuffled(kotlin.random.Random(7))
        val before = like("d1", 50 * DAY, 900 * DAY)
        val recency = listOf("p2", "y9", "p1") + bulk.map { it.youtubeId } + listOf("y2", "d1", "y1")

        val origins = resolveLikeOrigins(recency, pushed + bulk + before, (pushed + bulk + before).mapTo(hashSetOf()) { it.youtubeId })

        assertEquals(LikeOrigin.ANCHOR, origins["p2"])
        assertEquals(LikeOrigin.ANCHOR, origins["p1"])
        assertEquals(List(12) { LikeOrigin.COPIED }, (1..12).map { origins["b$it"] })
        assertEquals(LikeOrigin.OWN, origins["d1"])

        // And the list they give: the user's likes as YouTube has them, the library under them by date.
        val data = SpotifyLikeOrderData((pushed + bulk + before).associate { it.youtubeId to it.likedAt }, origins)
        assertEquals(listOf("p2", "y9", "p1", "y2", "d1", "y1") + (12 downTo 1).map { "b$it" }, orderLikes(recency, data))
    }

    @Test
    fun anOldMatchCarriedOverDoesNotMakeAnAnchorInsideALibraryBroughtInAtOnce() {
        val bulk = (1..12).map { like("b$it", it * DAY, if (it == 4) 0 else 900 * DAY) }
        val origins = resolveLikeOrigins(bulk.map { it.youtubeId }, bulk, bulk.mapTo(hashSetOf()) { it.youtubeId })
        assertEquals(setOf(LikeOrigin.COPIED), origins.values.toSet())
    }

    @Test
    fun aFewLikesAreEachToldByTheirMatchAndWhatIsNotInTheListWaits() {
        // No library brought in at once: a like made here, one copied long after, one never matched, one not listed.
        val likes = listOf(like("n1", 500 * DAY, DAY), like("l1", 10 * DAY, 400 * DAY), like("m1", 20 * DAY, null), like("gone", 1, 0))
        val origins = resolveLikeOrigins(listOf("n1", "y1", "l1", "m1"), likes, likes.mapTo(hashSetOf()) { it.youtubeId })
        assertEquals(mapOf("n1" to LikeOrigin.ANCHOR, "l1" to LikeOrigin.COPIED, "m1" to LikeOrigin.COPIED), origins)
    }
}
