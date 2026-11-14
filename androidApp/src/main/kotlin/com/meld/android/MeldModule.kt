package com.meld.android

import com.meld.db.meldDatabaseModule
import com.meld.spotify.playback.SpotifyPlayback
import com.meld.spotify.playback.SpotifyQueuePlayback
import com.meld.spotify.spotifyModule
import com.metrolist.android.spotifyimport.androidSpotifyImportManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.dsl.bind
import org.koin.dsl.module

val meldModule =
    module {
        // The import manager is a process-wide singleton that keeps its own signed-in client.
        includes(meldDatabaseModule, spotifyModule(onLogout = { androidSpotifyImportManager.logout() }))
        single {
            SpotifyQueuePlayback(get(), AndroidSpotifyQueueTarget(get()), CoroutineScope(SupervisorJob() + Dispatchers.Main))
        } bind SpotifyPlayback::class
    }
