package com.meld.spotify.download

import com.meld.spotify.SpotifyTrack
import com.meld.spotify.playback.SpotifyResolution
import com.meld.spotify.playback.SpotifyTrackResolver
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.ensureActive

/**
 * The app's own song downloads, as each platform runs them. Returns how many of [songs] it took into
 * its queue: songs already downloaded or already waiting are not counted.
 */
fun interface SongDownloads {
    suspend fun queue(songs: List<SongItem>): Int
}

/**
 * How a download of Spotify tracks went. [found] tracks have a YouTube song, of which [queued] were
 * new to the app's downloads; [notFound] have none; [unreachable] could not be looked up because
 * YouTube did not answer, and are worth another try.
 */
data class SpotifyDownloadResult(
    val queued: Int,
    val found: Int,
    val notFound: Int,
    val unreachable: Int,
)

/**
 * Downloads Spotify tracks through the app's own downloads: each track is matched to the YouTube song
 * that plays for it, exactly as for playback, and those songs are queued. Nothing here stores audio.
 */
class SpotifyDownloads(
    private val resolver: SpotifyTrackResolver,
    private val downloads: SongDownloads,
    /** The ids of the songs the app holds as downloads, as they change. */
    val downloadedIds: Flow<Set<String>> = flowOf(emptySet()),
) {
    /** [onProgress] is told how many of the tracks have been looked up so far. */
    suspend fun download(
        tracks: List<SpotifyTrack>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): SpotifyDownloadResult {
        val wanted = tracks.distinctBy { it.id }
        val songs = LinkedHashMap<String, SongItem>()
        var notFound = 0
        var unreachable = 0
        var done = 0
        onProgress(0, wanted.size)
        for (batch in wanted.chunked(BATCH)) {
            currentCoroutineContext().ensureActive()
            resolver.resolveAll(batch).forEach { resolution ->
                when (resolution) {
                    // The song that would play for the track, also when the match is not a confident one.
                    is SpotifyResolution.Matched -> songs.getOrPut(resolution.song.id) { resolution.song }
                    SpotifyResolution.NoMatch -> notFound++
                    is SpotifyResolution.Failed -> unreachable++
                }
            }
            done += batch.size
            onProgress(done, wanted.size)
        }
        val queued = if (songs.isEmpty()) 0 else downloads.queue(songs.values.toList())
        return SpotifyDownloadResult(queued = queued, found = songs.size, notFound = notFound, unreachable = unreachable)
    }

    private companion object {
        const val BATCH = 10
    }
}
