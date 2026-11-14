package com.meld.spotify.playback

import com.meld.spotify.SpotifyTrack

/**
 * Plays Spotify tracks by matching each one to a YouTube Music song before it enters the queue.
 * Registered in Koin on JVM targets only.
 */
interface SpotifyPlayback {
    /**
     * Starts playback at [startIndex]; the tracks before it are queued before it once it plays.
     * With [shuffle] the player's shuffle is switched on and the other tracks follow in random order.
     *
     * [more] returns the next batch of the same source (never empty), or null when the source is
     * exhausted. It throws when the batch could not be loaded and is then asked again after a pause.
     * It is asked only as the queue runs low, or ahead of that while the player shuffles.
     */
    fun play(
        tracks: List<SpotifyTrack>,
        startIndex: Int = 0,
        title: String? = null,
        shuffle: Boolean = false,
        more: (suspend () -> List<SpotifyTrack>?)? = null,
    )

    fun playNext(tracks: List<SpotifyTrack>)

    fun addToQueue(tracks: List<SpotifyTrack>)
}
