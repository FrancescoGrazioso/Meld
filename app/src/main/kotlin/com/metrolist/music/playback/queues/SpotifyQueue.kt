/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.queues

import androidx.media3.common.MediaItem
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.playback.SpotifyYouTubeMapper
import com.metrolist.spotify.Spotify
import com.metrolist.spotify.models.SpotifyTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Queue implementation that uses Spotify recommendations to build a radio-like queue.
 *
 * Optimized for fast playback start: only the initial track is resolved in
 * [getInitialStatus]. Recommendations are fetched from Spotify but resolved
 * progressively in [nextPage] batches.
 */
class SpotifyQueue(
    private val initialTrack: SpotifyTrack,
    private val mapper: SpotifyYouTubeMapper,
    override val preloadItem: MediaMetadata? = null,
) : Queue {

    companion object {
        private const val RESOLVE_BATCH_SIZE = 10
    }

    private val recommendedTracks = mutableListOf<SpotifyTrack>()
    private var resolveOffset = 0
    private var recommendationsFetched = false

    override suspend fun getInitialStatus(): Queue.Status = withContext(Dispatchers.IO) {
        // Resolve only the initial track for immediate playback
        val initialMediaItem = mapper.resolveToMediaItem(initialTrack)

        if (initialMediaItem == null) {
            Timber.w("SpotifyQueue: Could not resolve initial track '${initialTrack.name}'")
            return@withContext Queue.Status(
                title = null,
                items = emptyList(),
                mediaItemIndex = 0,
            )
        }

        // Fetch recommendations in the background (don't resolve yet)
        try {
            val seedTrackId = initialTrack.id
            val seedArtistIds = initialTrack.artists.mapNotNull { it.id }.take(2)

            val recommendations = Spotify.recommendations(
                seedTrackIds = listOf(seedTrackId),
                seedArtistIds = seedArtistIds,
                limit = 25,
            ).getOrNull()

            if (recommendations != null) {
                recommendedTracks.addAll(recommendations.tracks)
                Timber.d("SpotifyQueue: Fetched ${recommendations.tracks.size} recommendations")
            }
        } catch (e: Exception) {
            Timber.e(e, "SpotifyQueue: Failed to fetch recommendations")
        }

        recommendationsFetched = true

        Timber.d("SpotifyQueue: Resolved initial track '${initialTrack.name}' instantly, " +
            "${recommendedTracks.size} recommendations queued for resolution")

        Queue.Status(
            title = null,
            items = listOf(initialMediaItem),
            mediaItemIndex = 0,
        )
    }

    override fun hasNextPage(): Boolean =
        resolveOffset < recommendedTracks.size

    override suspend fun nextPage(): List<MediaItem> = withContext(Dispatchers.IO) {
        if (resolveOffset >= recommendedTracks.size) {
            return@withContext emptyList()
        }

        val end = (resolveOffset + RESOLVE_BATCH_SIZE).coerceAtMost(recommendedTracks.size)
        val batch = recommendedTracks.subList(resolveOffset, end)
        resolveOffset = end

        Timber.d("SpotifyQueue: Resolving batch of ${batch.size} recommended tracks " +
            "(offset=$resolveOffset/${recommendedTracks.size})")

        batch.mapNotNull { track -> mapper.resolveToMediaItem(track) }
    }
}
