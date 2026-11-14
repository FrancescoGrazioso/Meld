package com.meld.android

import com.meld.db.meldDatabaseModule
import com.meld.spotify.spotifyModule
import com.metrolist.android.spotifyimport.androidSpotifyImportManager
import org.koin.dsl.module

val meldModule =
    module {
        // The import manager is a process-wide singleton that keeps its own signed-in client.
        includes(meldDatabaseModule, spotifyModule(onLogout = { androidSpotifyImportManager.logout() }))
    }
