package com.meld.android

import com.meld.spotify.meldModule
import com.meld.spotify.playback.PlayerQueueTarget
import com.metrolist.android.playback.PlayerConnection
import com.metrolist.android.spotifyimport.androidSpotifyImportManager
import org.koin.core.module.Module

/** What only Android can supply to Meld: its player's queue functions and its logout hook. */
val meldModule: Module =
    meldModule(
        queueTarget = { scope ->
            val connection = get<PlayerConnection>()
            PlayerQueueTarget(
                scope = scope,
                queueState = connection.queueState,
                shuffleEnabled = connection.shuffleModeEnabled,
                // finite: recommendations follow the Spotify tracks only if the user has auto-load on, as for a playlist.
                play = { songs, title -> connection.playQueue(songs, queueTitle = title, finite = true) },
                enqueue = { connection.addToQueue(it) },
                enqueueNext = { connection.addToQueue(it, playNext = true) },
                reorder = connection::reorderQueueEntries,
                toggleShuffle = connection::toggleShuffle,
            )
        },
        // The import manager is a process-wide singleton that keeps its own signed-in client.
        onLogout = { androidSpotifyImportManager.logout() },
    )
