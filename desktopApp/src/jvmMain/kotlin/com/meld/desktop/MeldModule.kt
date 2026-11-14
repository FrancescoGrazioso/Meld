package com.meld.desktop

import com.meld.db.meldDatabaseModule
import com.meld.spotify.spotifyModule
import org.koin.dsl.module

val meldModule = module { includes(meldDatabaseModule, spotifyModule()) }
