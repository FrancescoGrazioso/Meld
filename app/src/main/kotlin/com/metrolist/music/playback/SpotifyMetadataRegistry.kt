/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.util.LruCache
import com.metrolist.spotify.models.SpotifyTrack

/**
 * Maps a resolved YouTube video id back to the originating [SpotifyTrack] so the
 * player can recover Spotify metadata (ISRC, album, etc.) for a queued item.
 *
 * Backed by a bounded, thread-safe [LruCache] so the registry can't grow without
 * limit over a long-lived process (one entry per unique resolved track otherwise
 * lived forever). The eldest entries are evicted once the bound is reached.
 */
object SpotifyMetadataRegistry {
    private const val MAX_ENTRIES = 1024

    private val byYoutubeId = LruCache<String, SpotifyTrack>(MAX_ENTRIES)

    fun register(youtubeId: String, track: SpotifyTrack) {
        byYoutubeId.put(youtubeId, track)
    }

    fun get(youtubeId: String): SpotifyTrack? = byYoutubeId.get(youtubeId)

    fun invalidate(youtubeId: String) {
        byYoutubeId.remove(youtubeId)
    }

    fun clearAll() {
        byYoutubeId.evictAll()
    }
}
