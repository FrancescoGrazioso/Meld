package com.meld.spotify

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** What an action started from a menu came to, when nothing else on the screen says so. */
enum class SpotifyNotice {
    PLAYING_NEXT,
    QUEUED,
    DOWNLOAD_STARTED,
    NOTHING_NEW,
    NO_MATCH,
    NOTHING_TO_PLAY,
    COPIED,
    LIKED,
    UNLIKED,
    FAILED,
}

/**
 * The short messages of Meld's Spotify actions. Anything may [show] one; the host of the app draws the
 * latest for a moment. With no host listening a notice is dropped, which is all a test needs.
 */
object SpotifyNotices {
    private val flow = MutableSharedFlow<SpotifyNotice>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val events: SharedFlow<SpotifyNotice> get() = flow

    fun show(notice: SpotifyNotice) {
        flow.tryEmit(notice)
    }
}
