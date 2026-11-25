package com.meld.desktop

import com.meld.spotify.download.SongDownloads
import com.meld.spotify.meldModule
import com.meld.spotify.playback.PlayerQueueTarget
import com.meld.spotify.playback.indicesOf
import com.metrolist.desktop.storage.DownloadManager
import com.metrolist.desktop.storage.DownloadState
import kotlinx.coroutines.flow.map
import com.metrolist.desktop.state.playback.PlayerState
import org.koin.core.module.Module

/** What only the desktop can supply to Meld: its player's queue functions and its downloads. The desktop queue has no title. */
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
                // The queue lock is for the listener's drags, not for the order the feeder restores.
                reorder = { keys ->
                    val queue = PlayerState.queue.state.value
                    PlayerState.reorderQueue(queue.indicesOf(keys), ignoreLock = true)
                },
                toggleShuffle = PlayerState::toggleShuffle,
            )
        },
        songDownloads = {
            SongDownloads { songs ->
                // The desktop queue does not say what it took: count what is not on disk yet.
                val fresh = songs.count { !DownloadManager.isDownloaded(it.id) }
                DownloadManager.downloadAll(songs)
                fresh
            }
        },
        downloadedSongIds = {
            DownloadManager.downloadStates.map { states -> states.filterValues { it == DownloadState.DOWNLOADED }.keys }
        },
    )
