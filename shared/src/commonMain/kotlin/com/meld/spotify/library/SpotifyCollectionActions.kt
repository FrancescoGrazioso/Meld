package com.meld.spotify.library

import com.meld.spotify.SpotifyError
import com.meld.spotify.SpotifyNotice
import com.meld.spotify.SpotifyNotices
import com.meld.spotify.SpotifyTrack
import com.meld.spotify.download.SpotifyDownloads
import com.meld.spotify.download.notice
import com.meld.spotify.playback.SpotifyPlayback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * What can be done with a whole playlist, album, artist or the liked songs without opening it: the menu
 * of a library row. Each action loads the list to its end first. They run on [scope], which outlives the
 * menu; a list that cannot be loaded is left alone, and its own screen says why when it is opened.
 */
class SpotifyCollectionActions(
    private val store: SpotifyLibraryStore,
    private val playback: SpotifyPlayback?,
    private val downloads: SpotifyDownloads?,
    private val scope: CoroutineScope,
) {
    val canPlay: Boolean get() = playback != null
    val canDownload: Boolean get() = downloads != null

    fun play(
        type: SpotifyCollectionType,
        id: String,
        title: String?,
        shuffle: Boolean = false,
    ) = with(type, id) { tracks ->
        playback?.play(tracks, if (shuffle) tracks.indices.random() else 0, title, shuffle = shuffle)
    }

    fun playNext(
        type: SpotifyCollectionType,
        id: String,
    ) = with(type, id) {
        playback?.playNext(it)
        SpotifyNotices.show(SpotifyNotice.PLAYING_NEXT)
    }

    fun addToQueue(
        type: SpotifyCollectionType,
        id: String,
    ) = with(type, id) {
        playback?.addToQueue(it)
        SpotifyNotices.show(SpotifyNotice.QUEUED)
    }

    fun download(
        type: SpotifyCollectionType,
        id: String,
    ) = with(type, id) { tracks -> downloads?.download(tracks)?.let { SpotifyNotices.show(it.notice()) } }

    private fun with(
        type: SpotifyCollectionType,
        id: String,
        action: suspend (List<SpotifyTrack>) -> Unit,
    ) {
        scope.launch {
            try {
                val tracks =
                    store
                        .collection(type, id)
                        .tracks
                        .loadAll()
                        .mapNotNull { it.playableTrack }
                if (tracks.isNotEmpty()) action(tracks) else SpotifyNotices.show(SpotifyNotice.NOTHING_TO_PLAY)
            } catch (error: SpotifyError) {
                // Also kept in the pager's state: the list shows it with a retry when it is opened.
                SpotifyNotices.show(SpotifyNotice.FAILED)
            }
        }
    }
}
