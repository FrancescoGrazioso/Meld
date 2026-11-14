package com.meld.desktop

import com.meld.spotify.meldModule
import com.meld.spotify.playback.PlayerQueueTarget
import com.meld.spotify.playback.indicesOf
import com.metrolist.desktop.state.playback.PlayerState
import org.koin.core.module.Module

/** What only the desktop can supply to Meld: its player's queue functions. The desktop queue has no title. */
val meldModule: Module =
    meldModule(
        queueTarget = { scope ->
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
        },
    )
