package com.meld.desktop

import com.meld.db.meldDatabaseModule
import com.meld.spotify.playback.PlayerQueueTarget
import com.meld.spotify.playback.SpotifyPlayback
import com.meld.spotify.playback.SpotifyQueuePlayback
import com.meld.spotify.playback.indicesOf
import com.meld.spotify.spotifyModule
import com.metrolist.desktop.state.playback.PlayerState
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
            SpotifyQueuePlayback(get(), desktopQueueTarget(scope), scope)
        } bind SpotifyPlayback::class
    }

/** The desktop player's queue functions, with no logic of their own. The desktop queue has no title. */
private fun desktopQueueTarget(scope: CoroutineScope) =
    PlayerQueueTarget(
        scope = scope,
        queueState = PlayerState.queue.state,
        shuffleEnabled = PlayerState.queue.shuffleEnabled,
        play = { songs, _ -> PlayerState.playTrack(songs.first(), songs) },
        enqueue = { PlayerState.addToQueue(it) },
        enqueueNext = { PlayerState.playNext(it) },
        reorder = { PlayerState.reorderQueue(PlayerState.queue.state.value.indicesOf(it)) },
        toggleShuffle = PlayerState::toggleShuffle,
    )
