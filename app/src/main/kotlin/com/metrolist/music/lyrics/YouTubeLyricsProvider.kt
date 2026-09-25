/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import android.content.Context
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.WatchEndpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object YouTubeLyricsProvider : LyricsProvider {
    override val name = "YouTube Music"

    override fun isEnabled(context: Context) = true

    override suspend fun getLyrics(
        context: Context,
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val lyricsEndpoint = YouTube.next(WatchEndpoint(videoId = id)).getOrNull()?.lyricsEndpoint

            // YouTube Music's own line-timed lyrics, which is what makes the view scroll and
            // highlight. Only its mobile clients are served these; YouTube.lyrics() asks as
            // WEB_REMIX and can only ever come back with one untimed block (#174).
            if (lyricsEndpoint != null) {
                val timed = YouTube.timedLyrics(lyricsEndpoint).getOrNull()?.takeIf { it.isNotBlank() }
                if (timed != null) {
                    return@withContext Result.success(timed)
                }
            }

            // Video captions: timed as well, but present for a minority of music tracks.
            val transcript = YouTube.transcript(id).getOrNull()?.takeIf { it.isNotBlank() }
            if (transcript != null) {
                return@withContext Result.success(transcript)
            }

            Result.success(
                YouTube
                    .lyrics(
                        endpoint = lyricsEndpoint
                            ?: throw IllegalStateException("Lyrics endpoint not found"),
                    ).getOrThrow() ?: throw IllegalStateException("Lyrics unavailable")
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }
}
