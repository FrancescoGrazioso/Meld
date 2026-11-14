package com.meld.spotify.playback

import com.meld.spotify.SpotifyTrack

/**
 * Plays Spotify tracks by matching each one to a YouTube Music song before it enters the queue.
 * Registered in Koin on JVM targets only.
 */
interface SpotifyPlayback {
    /**
     * Starts playback at [startIndex]. [more] returns the next batch of the same source, or an
     * empty list when the source is exhausted; it is asked only as the queue runs low.
     */
    fun play(
        tracks: List<SpotifyTrack>,
        startIndex: Int = 0,
        title: String? = null,
        shuffle: Boolean = false,
        more: (suspend () -> List<SpotifyTrack>)? = null,
    )

    fun playNext(tracks: List<SpotifyTrack>)

    fun addToQueue(tracks: List<SpotifyTrack>)
}
