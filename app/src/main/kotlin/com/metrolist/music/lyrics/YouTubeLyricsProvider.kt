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
            // Prefer the timed transcript ([mm:ss.SSS] format) so lyrics scroll/highlight
            // in sync with playback. YouTube.lyrics() only returns the plain description
            // text (no timestamps), which the UI renders as static, unsynced lyrics (#174).
            val synced = YouTube.transcript(id).getOrNull()?.takeIf { it.isNotBlank() }
            if (synced != null) {
                return@withContext Result.success(synced)
            }

            val nextResult = YouTube.next(WatchEndpoint(videoId = id)).getOrThrow()
            Result.success(
                YouTube
                    .lyrics(
                        endpoint = nextResult.lyricsEndpoint
                            ?: throw IllegalStateException("Lyrics endpoint not found"),
                    ).getOrThrow() ?: throw IllegalStateException("Lyrics unavailable")
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }
}
