package com.meld.android

import com.meld.spotify.playback.SpotifyQueueTarget
import com.metrolist.android.playback.PlayerConnection
import com.metrolist.shared.model.SongItem

class AndroidSpotifyQueueTarget(
    private val connection: PlayerConnection,
) : SpotifyQueueTarget {
    override val queueState get() = connection.queueState

    // finite: recommendations follow the Spotify tracks only if the user has auto-load on, as for a playlist.
    override fun start(
        songs: List<SongItem>,
        title: String?,
    ) = connection.playQueue(songs, queueTitle = title, finite = true)

    override fun addToQueue(songs: List<SongItem>) = connection.addToQueue(songs)

    override fun playNext(songs: List<SongItem>) = connection.addToQueue(songs, playNext = true)
}
