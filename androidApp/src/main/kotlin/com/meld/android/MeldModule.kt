package com.meld.android

import com.meld.android.spotify.spotifyScreensModule
import com.meld.db.meldDatabaseModule
import com.meld.spotify.playback.PlayerQueueTarget
import com.meld.spotify.playback.SpotifyPlayback
import com.meld.spotify.playback.SpotifyQueuePlayback
import com.meld.spotify.spotifyModule
import com.metrolist.android.playback.PlayerConnection
import com.metrolist.android.spotifyimport.androidSpotifyImportManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.dsl.bind
import org.koin.dsl.module

val meldModule =
    module {
        // The import manager is a process-wide singleton that keeps its own signed-in client.
        includes(
            meldDatabaseModule,
            spotifyModule(onLogout = { androidSpotifyImportManager.logout() }),
            spotifyScreensModule,
        )
        single {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            SpotifyQueuePlayback(get(), androidQueueTarget(scope, get()), scope)
        } bind SpotifyPlayback::class
    }

/** The Android player's queue functions, with no logic of their own. */
private fun androidQueueTarget(
    scope: CoroutineScope,
    connection: PlayerConnection,
) = PlayerQueueTarget(
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
