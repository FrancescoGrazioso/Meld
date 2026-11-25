package com.meld.spotify.library

import com.meld.spotify.SpotifyTrackItem

/** How a track list is shown; [SPOTIFY] is the order Spotify gives it, the only one a playlist can be reordered in. */
enum class SpotifyTrackSort { SPOTIFY, TITLE, ARTIST, ALBUM, DURATION, DATE_ADDED }

/** True when the list is shown as Spotify has it, whole and in its own order. */
fun isSpotifyArrangement(
    query: String,
    sort: SpotifyTrackSort,
): Boolean = query.isBlank() && sort == SpotifyTrackSort.SPOTIFY

/**
 * The entries matching [query] in their title, artists or album, in the order of [sort]. Entries that
 * compare equal keep Spotify's order; [SpotifyTrackSort.DATE_ADDED] puts the newest first and those
 * without a date last. An entry without a track (an episode, a removed track) has nothing to search or
 * sort by: it stays only in the plain view.
 */
fun List<SpotifyTrackItem>.arranged(
    query: String,
    sort: SpotifyTrackSort,
): List<SpotifyTrackItem> {
    if (isSpotifyArrangement(query, sort)) return this
    val words = query.trim().lowercase().split(' ').filter { it.isNotEmpty() }
    val matching =
        filter { item ->
            val track = item.track ?: return@filter false
            val text = "${track.name} ${track.artists.joinToString(" ") { it.name }} ${track.album?.name.orEmpty()}".lowercase()
            words.all { it in text }
        }
    return when (sort) {
        SpotifyTrackSort.SPOTIFY -> matching
        SpotifyTrackSort.TITLE -> matching.sortedBy { it.track?.name.orEmpty().lowercase() }
        SpotifyTrackSort.ARTIST -> matching.sortedBy { it.track?.artists?.firstOrNull()?.name.orEmpty().lowercase() }
        SpotifyTrackSort.ALBUM -> matching.sortedBy { it.track?.album?.name.orEmpty().lowercase() }
        SpotifyTrackSort.DURATION -> matching.sortedBy { it.track?.durationMs ?: 0L }
        // ISO dates sort as text; the empty string of a missing date ends up last.
        SpotifyTrackSort.DATE_ADDED -> matching.sortedByDescending { it.addedAt.orEmpty() }
    }
}
