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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Queue implementation that loads tracks from a Spotify playlist.
 *
 * Optimized for fast playback start: only the selected track is resolved
 * during [getInitialStatus], while the remaining tracks are resolved
 * progressively in [nextPage] batches as the player approaches the end of
 * the currently loaded queue.
 */
class SpotifyPlaylistQueue(
    private val playlistId: String,
    private val initialTracks: List<SpotifyTrack> = emptyList(),
    private val startIndex: Int = 0,
    private val mapper: SpotifyYouTubeMapper,
    override val preloadItem: MediaMetadata? = null,
) : Queue {

    companion object {
        private const val SPOTIFY_PAGE_SIZE = 50
        private const val RESOLVE_BATCH_SIZE = 10
    }

    // All Spotify tracks fetched so far (may span multiple API pages)
    private val allTracks = mutableListOf<SpotifyTrack>()

    // Index into [allTracks] for the next batch to resolve to YouTube
    private var resolveOffset = 0

    // Spotify API pagination state
    private var apiFetchOffset = 0
    private var apiTotal = 0
    private var apiHasMore = true

    override suspend fun getInitialStatus(): Queue.Status = withContext(Dispatchers.IO) {
        try {
            if (initialTracks.isNotEmpty()) {
                // Tracks were already loaded by the ViewModel
                allTracks.addAll(initialTracks)
                apiTotal = initialTracks.size
                apiFetchOffset = apiTotal
                apiHasMore = false // All tracks already available
            } else {
                // Fetch the first API page
                val result = Spotify.playlistTracks(
                    playlistId, limit = SPOTIFY_PAGE_SIZE, offset = 0
                ).getOrThrow()
                apiTotal = result.total
                val fetched = result.items.mapNotNull { it.track?.takeIf { t -> !t.isLocal } }
                allTracks.addAll(fetched)
                apiFetchOffset = result.items.size
                apiHasMore = apiFetchOffset < apiTotal
            }

            // If startIndex is beyond current fetched tracks, fetch more
            while (startIndex >= allTracks.size && apiHasMore) {
                fetchNextApiPage()
            }

            // Resolve only the selected track for immediate playback
            val targetIndex = startIndex.coerceIn(0, (allTracks.size - 1).coerceAtLeast(0))
            val targetTrack = allTracks.getOrNull(targetIndex)
            val resolvedItem = targetTrack?.let { mapper.resolveToMediaItem(it) }

            if (resolvedItem == null) {
                Timber.w("SpotifyPlaylistQueue: Could not resolve track at index $targetIndex")
                return@withContext Queue.Status(
                    title = null,
                    items = emptyList(),
                    mediaItemIndex = 0,
                )
            }

            // Start resolving forward from the track after the selected one
            resolveOffset = targetIndex + 1

            Timber.d("SpotifyPlaylistQueue: Resolved track '${targetTrack.name}' instantly, " +
                "${allTracks.size} tracks fetched, total=$apiTotal")

            Queue.Status(
                title = null,
                items = listOf(resolvedItem),
                mediaItemIndex = 0,
            )
        } catch (e: Exception) {
            Timber.e(e, "SpotifyPlaylistQueue: Failed initial fetch")
            Queue.Status(
                title = null,
                items = emptyList(),
                mediaItemIndex = 0,
            )
        }
    }

    override fun hasNextPage(): Boolean =
        resolveOffset < allTracks.size || apiHasMore

    override suspend fun nextPage(): List<MediaItem> = withContext(Dispatchers.IO) {
        // If we've resolved all fetched tracks but the API has more, fetch another page
        if (resolveOffset >= allTracks.size && apiHasMore) {
            fetchNextApiPage()
        }

        if (resolveOffset >= allTracks.size) {
            return@withContext emptyList()
        }

        // Resolve the next batch
        val end = (resolveOffset + RESOLVE_BATCH_SIZE).coerceAtMost(allTracks.size)
        val batch = allTracks.subList(resolveOffset, end)
        resolveOffset = end

        Timber.d("SpotifyPlaylistQueue: Resolving batch of ${batch.size} tracks " +
            "(offset=$resolveOffset/${allTracks.size}, apiTotal=$apiTotal)")

        coroutineScope {
            batch.map { track -> async { mapper.resolveToMediaItem(track) } }
                .awaitAll()
                .filterNotNull()
        }
    }

    private suspend fun fetchNextApiPage() {
        if (!apiHasMore) return
        try {
            val result = Spotify.playlistTracks(
                playlistId, limit = SPOTIFY_PAGE_SIZE, offset = apiFetchOffset,
            ).getOrThrow()
            val fetched = result.items.mapNotNull { it.track?.takeIf { t -> !t.isLocal } }
            allTracks.addAll(fetched)
            apiFetchOffset += result.items.size
            apiHasMore = apiFetchOffset < apiTotal
            Timber.d("SpotifyPlaylistQueue: Fetched API page, now have ${allTracks.size} tracks")
        } catch (e: Exception) {
            Timber.e(e, "SpotifyPlaylistQueue: Failed to fetch next API page")
            apiHasMore = false
        }
    }
}
