package com.meld.desktop

import com.meld.db.meldDatabaseModule
import com.meld.desktop.spotify.DesktopSpotifyQueueTarget
import com.meld.spotify.playback.SpotifyPlayback
import com.meld.spotify.playback.SpotifyQueuePlayback
import com.meld.spotify.spotifyModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.dsl.bind
import org.koin.dsl.module

val meldModule =
    module {
        includes(meldDatabaseModule, spotifyModule())
        // Created on first use, so nothing here touches the player until a Spotify track list is opened.
        single {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            SpotifyQueuePlayback(get(), DesktopSpotifyQueueTarget(scope), scope)
        } bind SpotifyPlayback::class
    }
