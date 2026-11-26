package com.meld.spotify.likes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.concurrent.Volatile

/**
 * How a song liked on Spotify came to be liked here, which says what its place in upstream's list is worth.
 * Stored with the liked track, so the numbers stay as they are.
 */
object LikeOrigin {
    /** Written before origins were kept; told apart once by [resolveLikeOrigins]. */
    const val UNKNOWN = 0

    /** Meld copied the like over from Spotify long after it was made: its place in the list is when Meld copied it. */
    const val COPIED = 1

    /** Liked here and on Spotify at about the same time: its place in the list and Spotify's date agree. */
    const val ANCHOR = 2

    /** The user liked it here on their own; Spotify's date is that of another like and says nothing of this one. */
    const val OWN = 3
}

/**
 * What the like sync knows of the liked songs that are also liked on Spotify, by YouTube id: when Spotify
 * says they were liked, in epoch milliseconds, and the [LikeOrigin] of each.
 */
data class SpotifyLikeOrderData(
    val dates: Map<String, Long> = emptyMap(),
    val origins: Map<String, Int> = emptyMap(),
)

/**
 * Puts the liked songs in the order they were liked, wherever upstream sorts its liked songs by date added.
 *
 * Upstream keeps no dates, only a list of ids, newest first. With a YouTube Music account that list follows
 * YouTube's, which is right for every like the user made and wrong for the likes Meld copied from Spotify:
 * those sit where they were when Meld sent them. See [orderLikes] for how the two are put together.
 */
object SpotifyLikedOrder {
    /** Compose state, so readers follow it. */
    var data by mutableStateOf(SpotifyLikeOrderData())
        private set

    @Volatile
    private var last: Triple<List<String>, SpotifyLikeOrderData, List<String>>? = null

    fun update(data: SpotifyLikeOrderData) {
        if (data != this.data) this.data = data
    }

    /** [recency] is upstream's list; it is returned as it is while nothing from Spotify is known. */
    fun apply(recency: List<String>): List<String> {
        val data = data
        last?.let { (from, with, result) -> if (from === recency && with === data) return result }
        return orderLikes(recency, data).also { last = Triple(recency, data, it) }
    }
}

/**
 * [recency], newest first, with the likes Meld copied from Spotify moved to where their Spotify date puts them.
 *
 * Every other song keeps the order upstream has it in: a like the user made is where YouTube lists it. Of
 * those, an [LikeOrigin.ANCHOR] also has a date, and the copied likes are placed against the anchors: each
 * goes right above the first anchor that is not newer than it. A song without a date is thus taken to be
 * newer than the copies around it, unless an anchor above it says otherwise; the songs YouTube lists under
 * every anchor, liked there before Meld came, end up above the old likes copied from Spotify.
 *
 * An anchor dated later than one above it is held to that one's date, so the order of the user's own likes
 * never changes. Two copies of the same instant keep upstream's order.
 */
internal fun orderLikes(
    recency: List<String>,
    data: SpotifyLikeOrderData,
): List<String> {
    val dates = data.dates
    if (dates.isEmpty()) return recency

    fun isCopy(id: String) = id in dates && (data.origins[id] ?: LikeOrigin.UNKNOWN).let { it == LikeOrigin.COPIED || it == LikeOrigin.UNKNOWN }

    val copies = recency.filter(::isCopy).sortedByDescending { dates.getValue(it) }
    if (copies.isEmpty()) return recency
    val result = ArrayList<String>(recency.size)
    var next = 0
    var ceiling = Long.MAX_VALUE
    for (id in recency) {
        if (isCopy(id)) continue
        if (data.origins[id] == LikeOrigin.ANCHOR) {
            ceiling = minOf(ceiling, dates[id] ?: ceiling)
            while (next < copies.size && dates.getValue(copies[next]) > ceiling) result += copies[next++]
        }
        result += id
    }
    while (next < copies.size) result += copies[next++]
    return if (result == recency) recency else result
}

/** A liked track whose origin is to be told: its song, Spotify's date, and when Meld first matched the two. */
internal data class UnresolvedLike(
    val youtubeId: String,
    val likedAt: Long,
    /** Null when no match is on record. */
    val matchedAt: Long?,
)

/** A like matched this long after it was made was not made here: Meld found it on Spotify and copied it. */
internal const val ANCHOR_WINDOW_MILLIS = 7L * 24 * 60 * 60 * 1000

/** This many copied likes in a row, with no like of the user's between them, is a library brought in at once. */
internal const val BULK_RUN = 10

/**
 * Tells the origin of likes written before it was kept, from the two traces that are left: when Meld first
 * matched the track, and where upstream's list has the song.
 *
 * A like matched within [ANCHOR_WINDOW_MILLIS] of Spotify's date was made about when Meld learned of it:
 * an anchor. One matched long after was copied. Where [BULK_RUN] or more such copies stand in one row of
 * Spotify likes, from the first to the last of them is a library brought in at once, and all of it is
 * copied, whatever an old match carried over from the app before says. And a Spotify like that YouTube lists under the lowest of
 * those rows was liked there before Meld brought anything in: the user's own.
 *
 * [dated] are all the songs with a Spotify date, already resolved ones included; a song missing from
 * [recency] is left out of the answer and asked about again later.
 */
internal fun resolveLikeOrigins(
    recency: List<String>,
    unresolved: List<UnresolvedLike>,
    dated: Set<String>,
): Map<String, Int> {
    if (unresolved.isEmpty()) return emptyMap()
    val byId = unresolved.associateBy { it.youtubeId }

    fun late(like: UnresolvedLike) = like.matchedAt == null || like.matchedAt - like.likedAt > ANCHOR_WINDOW_MILLIS

    // The rows of Spotify likes in upstream's list, as index ranges.
    val bulk = BooleanArray(recency.size)
    var lowestBulk = -1
    var start = 0
    while (start < recency.size) {
        if (recency[start] !in dated) {
            start++
            continue
        }
        var end = start
        while (end + 1 < recency.size && recency[end + 1] in dated) end++
        // The library is what lies between the first and the last like copied late; a like made here just
        // before or after it only happens to touch the row.
        val lateOnes = (start..end).filter { index -> byId[recency[index]]?.let(::late) == true }
        if (lateOnes.size >= BULK_RUN) {
            for (index in lateOnes.first()..lateOnes.last()) bulk[index] = true
            lowestBulk = lateOnes.last()
        }
        start = end + 1
    }
    val result = HashMap<String, Int>()
    recency.forEachIndexed { index, id ->
        val like = byId[id] ?: return@forEachIndexed
        result[id] =
            when {
                bulk[index] -> LikeOrigin.COPIED
                lowestBulk in 0 until index -> LikeOrigin.OWN
                late(like) -> LikeOrigin.COPIED
                else -> LikeOrigin.ANCHOR
            }
    }
    return result
}
