/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.queues

import androidx.media3.common.MediaItem
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.playback.SoundCloudYouTubeMapper
import com.metrolist.soundcloud.SoundCloud
import com.metrolist.soundcloud.models.SoundCloudTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Queue implementation that loads tracks from a SoundCloud playlist.
 *
 * Optimized for fast playback start: only the selected track is resolved
 * during [getInitialStatus], while the remaining tracks are resolved
 * progressively in [nextPage] batches.
 */
class SoundCloudPlaylistQueue(
    private val playlistId: Long,
    private val initialTracks: List<SoundCloudTrack> = emptyList(),
    private val startIndex: Int = 0,
    private val mapper: SoundCloudYouTubeMapper,
    override val preloadItem: MediaMetadata? = null,
) : Queue {

    companion object {
        private const val RESOLVE_BATCH_SIZE = 20
        private const val INITIAL_WINDOW_BEFORE = 5
        private const val INITIAL_WINDOW_AFTER = 19
    }

    // All SoundCloud tracks fetched so far
    private val allTracks = mutableListOf<SoundCloudTrack>()

    // Index into [allTracks] for the next batch to resolve to YouTube
    private var resolveOffset = 0

    override suspend fun getInitialStatus(): Queue.Status = withContext(Dispatchers.IO) {
        try {
            if (initialTracks.isNotEmpty()) {
                allTracks.addAll(initialTracks)
            } else {
                val result = SoundCloud.playlist(playlistId).getOrThrow()
                allTracks.addAll(result.tracks)
            }

            val targetIndex = startIndex.coerceIn(0, (allTracks.size - 1).coerceAtLeast(0))

            // Resolve a window of tracks around the selected one
            val windowStart = (targetIndex - INITIAL_WINDOW_BEFORE).coerceAtLeast(0)
            val windowEnd = (targetIndex + INITIAL_WINDOW_AFTER + 1).coerceAtMost(allTracks.size)
            val windowTracks = allTracks.subList(windowStart, windowEnd)

            val resolvedItems = coroutineScope {
                windowTracks.map { track -> async { mapper.resolveToMediaItem(track) } }
                    .awaitAll()
                    .filterNotNull()
            }

            if (resolvedItems.isEmpty()) {
                Timber.w("SoundCloudPlaylistQueue: Could not resolve any track in initial window")
                return@withContext Queue.Status(title = null, items = emptyList(), mediaItemIndex = 0)
            }

            resolveOffset = windowEnd

            val mediaItemIndex = (targetIndex - windowStart)
                .coerceIn(0, (resolvedItems.size - 1).coerceAtLeast(0))

            Timber.d("SoundCloudPlaylistQueue: Resolved ${resolvedItems.size} tracks " +
                "(window $windowStart..$windowEnd, target=$targetIndex, total=${allTracks.size})")

            Queue.Status(
                title = null,
                items = resolvedItems,
                mediaItemIndex = mediaItemIndex,
            )
        } catch (e: Exception) {
            Timber.e(e, "SoundCloudPlaylistQueue: Failed initial fetch")
            Queue.Status(title = null, items = emptyList(), mediaItemIndex = 0)
        }
    }

    override suspend fun getFullStatus(): Queue.Status? = withContext(Dispatchers.IO) {
        try {
            allTracks.clear()
            if (initialTracks.isNotEmpty()) {
                allTracks.addAll(initialTracks)
            } else {
                val result = SoundCloud.playlist(playlistId).getOrThrow()
                allTracks.addAll(result.tracks)
            }
            if (allTracks.isEmpty()) return@withContext null

            val targetIndex = startIndex.coerceIn(0, allTracks.size - 1)
            val resolvedItems = mutableListOf<MediaItem>()
            var mediaItemIndex = 0

            for (i in allTracks.indices) {
                if (i == targetIndex) mediaItemIndex = resolvedItems.size
                mapper.resolveToMediaItem(allTracks[i])?.let { resolvedItems.add(it) }
            }

            if (resolvedItems.isEmpty()) return@withContext null
            mediaItemIndex = mediaItemIndex.coerceIn(0, resolvedItems.size - 1)
            resolveOffset = allTracks.size

            Timber.d("SoundCloudPlaylistQueue: getFullStatus resolved ${resolvedItems.size} tracks (startIndex=$targetIndex)")
            Queue.Status(title = null, items = resolvedItems, mediaItemIndex = mediaItemIndex)
        } catch (e: Exception) {
            Timber.e(e, "SoundCloudPlaylistQueue: getFullStatus failed")
            null
        }
    }

    override suspend fun shuffleRemainingTracks() = withContext(Dispatchers.IO) {
        if (resolveOffset < allTracks.size) {
            val remaining = allTracks.subList(resolveOffset, allTracks.size)
            val shuffled = remaining.shuffled()
            for (i in shuffled.indices) {
                remaining[i] = shuffled[i]
            }
            Timber.d("SoundCloudPlaylistQueue: Shuffled ${remaining.size} remaining tracks " +
                "(resolveOffset=$resolveOffset, total=${allTracks.size})")
        }
    }

    override fun hasNextPage(): Boolean =
        resolveOffset < allTracks.size

    override suspend fun nextPage(): List<MediaItem> = withContext(Dispatchers.IO) {
        if (resolveOffset >= allTracks.size) {
            return@withContext emptyList()
        }

        // Resolve the next batch
        val end = (resolveOffset + RESOLVE_BATCH_SIZE).coerceAtMost(allTracks.size)
        val batch = allTracks.subList(resolveOffset, end)
        resolveOffset = end

        Timber.d("SoundCloudPlaylistQueue: Resolving batch of ${batch.size} tracks " +
            "(offset=$resolveOffset/${allTracks.size})")

        coroutineScope {
            batch.map { track -> async { mapper.resolveToMediaItem(track) } }
                .awaitAll()
                .filterNotNull()
        }
    }
}
