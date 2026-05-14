/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import com.metrolist.spotify.models.SpotifyTrack
import java.util.concurrent.ConcurrentHashMap

object SpotifyMetadataRegistry {
    private val byYoutubeId = ConcurrentHashMap<String, SpotifyTrack>()

    fun register(youtubeId: String, track: SpotifyTrack) {
        byYoutubeId[youtubeId] = track
    }

    fun get(youtubeId: String): SpotifyTrack? = byYoutubeId[youtubeId]

    fun invalidate(youtubeId: String) {
        byYoutubeId.remove(youtubeId)
    }

    fun clearAll() {
        byYoutubeId.clear()
    }
}
