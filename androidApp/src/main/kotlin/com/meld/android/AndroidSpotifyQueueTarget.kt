package com.meld.android

import com.meld.spotify.playback.SpotifyQueueTarget
import com.metrolist.android.playback.PlayerConnection
import com.metrolist.shared.model.SongItem

class AndroidSpotifyQueueTarget(
    private val connection: PlayerConnection,
) : SpotifyQueueTarget {
    override val queueState get() = connection.queueState

    override val shuffleEnabled get() = connection.shuffleModeEnabled

    // finite: recommendations follow the Spotify tracks only if the user has auto-load on, as for a playlist.
    // Without a shuffle request the player keeps its own rule: shuffle carried over from the last queue, or off.
    override fun start(
        songs: List<SongItem>,
        title: String?,
        shuffle: Boolean,
    ) = connection.playQueue(songs, queueTitle = title, finite = true, shuffleModeEnabled = shuffle.takeIf { it })

    override fun addToQueue(songs: List<SongItem>) = connection.addToQueue(songs)

    override fun playNext(songs: List<SongItem>) = connection.addToQueue(songs, playNext = true)

    override fun reorder(entryKeys: List<Long>) {
        connection.reorderQueueEntries(entryKeys)
    }
}
