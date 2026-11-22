package com.meld.spotify.likes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.concurrent.Volatile

/**
 * Puts the liked songs that came from Spotify in the order they were liked there, wherever upstream sorts
 * its liked songs by date added.
 *
 * Upstream keeps no dates, only a list of ids, newest first. With a YouTube Music account that list follows
 * YouTube's, which holds the likes Meld brought in by when Meld sent them, not by when they were liked.
 * The songs from Spotify therefore swap places among themselves, by Spotify's date; every other song stays
 * where upstream has it, as a like made on YouTube has no date to compare with.
 */
object SpotifyLikedOrder {
    /** YouTube id to the epoch milliseconds its track was liked on Spotify. Compose state, so readers follow it. */
    var dates by mutableStateOf<Map<String, Long>>(emptyMap())
        private set

    @Volatile
    private var last: Triple<List<String>, Map<String, Long>, List<String>>? = null

    fun update(dates: Map<String, Long>) {
        if (dates != this.dates) this.dates = dates
    }

    /** [recency] is upstream's list; it is returned as it is while nothing from Spotify is known. */
    fun apply(recency: List<String>): List<String> {
        val dates = dates
        last?.let { (from, with, result) -> if (from === recency && with === dates) return result }
        return reorderBySpotifyDate(recency, dates).also { last = Triple(recency, dates, it) }
    }
}

internal fun reorderBySpotifyDate(
    recency: List<String>,
    dates: Map<String, Long>,
): List<String> {
    if (dates.isEmpty()) return recency
    val slots = recency.indices.filter { recency[it] in dates }
    if (slots.size < 2) return recency
    // Stable: two likes of the same instant keep upstream's order.
    val newestFirst = slots.map { recency[it] }.sortedByDescending { dates.getValue(it) }
    val result = recency.toMutableList()
    slots.forEachIndexed { index, slot -> result[slot] = newestFirst[index] }
    return result
}
