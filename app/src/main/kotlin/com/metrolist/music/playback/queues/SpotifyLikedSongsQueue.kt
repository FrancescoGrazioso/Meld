/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.queues

import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.playback.SpotifyYouTubeMapper
import com.metrolist.spotify.Spotify
import com.metrolist.spotify.models.SpotifyTrack

/**
 * Queue implementation for Spotify Liked Songs (saved tracks). All the
 * pagination / fast-start / resolution logic lives in [SpotifyPagedQueue];
 * this class only supplies the liked-songs fetch and the optional pre-provided list.
 */
class SpotifyLikedSongsQueue(
    startIndex: Int = 0,
    mapper: SpotifyYouTubeMapper,
    preloadItem: MediaMetadata? = null,
    /**
     * Pre-ordered list of tracks to play, matching exactly what the UI displays (after
     * the user's sort/reverse). When provided, [startIndex] indexes into THIS list and no
     * Spotify pagination happens, so playback order always matches the visible list.
     * When null, the queue falls back to fetching from the Spotify API in its native order.
     */
    private val tracks: List<SpotifyTrack>? = null,
) : SpotifyPagedQueue(startIndex, mapper, preloadItem) {

    override val logTag: String = "SpotifyLikedSongsQueue"

    override val providedTracks: List<SpotifyTrack>? = tracks?.filter { !it.isLocal }

    override suspend fun fetchPage(offset: Int, limit: Int): PageResult {
        val result = Spotify.likedSongs(limit = limit, offset = offset).getOrThrow()
        return PageResult(
            tracks = result.items.map { it.track }.filter { !it.isLocal },
            total = result.total,
            rawCount = result.items.size,
        )
    }
}
