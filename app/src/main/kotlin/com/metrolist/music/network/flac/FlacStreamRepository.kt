/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.network.flac

import com.metrolist.music.network.flac.providers.AmazonFlacProvider
import com.metrolist.music.network.flac.providers.QobuzFlacProvider
import com.metrolist.music.network.flac.providers.TidalFlacProvider
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

object FlacStreamRepository {
    private const val TAG = "FlacStreamRepo"
    private const val STREAM_CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutes
    private const val MISS_CACHE_TTL_MS = 15 * 60 * 1000L  // 15 minutes

    private val songLinkClient = SongLinkClient()
    private val tidalProvider = TidalFlacProvider()
    private val qobuzProvider = QobuzFlacProvider()
    private val amazonProvider = AmazonFlacProvider()

    // In-memory stream cache: "mediaId:quality" -> FlacStreamResult
    private val streamCache = ConcurrentHashMap<String, FlacStreamResult>()

    // Negative miss cache: mediaId -> expiration timestamp
    private val missUntilMs = ConcurrentHashMap<String, Long>()

    // Track links cache: mediaId -> CrossPlatformLinks
    private val linksCache = ConcurrentHashMap<String, CrossPlatformLinks>()

    fun cacheKey(mediaId: String, quality: FlacAudioQuality): String =
        "flac:${quality.toQualityCode()}:$mediaId"

    fun invalidate(mediaId: String) {
        streamCache.keys.filter { it.endsWith(":$mediaId") }.forEach { streamCache.remove(it) }
        missUntilMs.remove(mediaId)
        linksCache.remove(mediaId)
    }

    /**
     * Resolves a lossless FLAC audio stream URL for the provided track query.
     * Follows the waterfall pipeline: Tidal -> Qobuz -> Amazon.
     */
    fun resolveFlacStream(query: FlacTrackQuery): FlacStreamResult? {
        val cacheKey = cacheKey(query.mediaId, query.quality)
        val now = System.currentTimeMillis()

        // 1. Check in-memory stream cache
        streamCache[cacheKey]?.let { cached ->
            if (cached.expiresAtMs > now) {
                Timber.tag(TAG).d("Stream cache hit for %s (%s)", query.mediaId, cached.provider.displayName)
                return cached
            } else {
                streamCache.remove(cacheKey)
            }
        }

        // 2. Check negative cache for catalog misses
        val missDeadline = missUntilMs[query.mediaId]
        if (missDeadline != null && missDeadline > now) {
            val remainingMin = (missDeadline - now) / 60_000
            Timber.tag(TAG).d("Skipping FLAC resolution for %s (negative cache active for %dm)", query.mediaId, remainingMin)
            return null
        }

        Timber.tag(TAG).i("Resolving FLAC stream for %s (title: %s, artist: %s, isrc: %s, spotifyId: %s)",
            query.mediaId, query.title, query.artists.firstOrNull(), query.isrc, query.spotifyTrackId)

        // 3. Resolve cross-platform links
        val links = getOrResolveLinks(query)

        // 4. Waterfall provider resolution: Tidal -> Qobuz -> Amazon
        var resolvedStream: FlacStreamResult? = null

        // 4a. Try Tidal
        if (links?.tidalTrackId != null) {
            Timber.tag(TAG).d("Attempting Tidal FLAC stream (trackId: %s)...", links.tidalTrackId)
            resolvedStream = tidalProvider.resolveStream(
                trackId = links.tidalTrackId,
                quality = query.quality,
                isrc = links.isrc ?: query.isrc,
            )
        }

        // 4b. Try Qobuz
        if (resolvedStream == null && links?.qobuzTrackId != null) {
            Timber.tag(TAG).d("Attempting Qobuz FLAC stream (trackId: %s)...", links.qobuzTrackId)
            resolvedStream = qobuzProvider.resolveStream(
                trackId = links.qobuzTrackId,
                quality = query.quality,
                isrc = links.isrc ?: query.isrc,
            )
        }

        // 4c. Try Amazon Music
        if (resolvedStream == null && links?.amazonAsin != null) {
            Timber.tag(TAG).d("Attempting Amazon FLAC stream (ASIN: %s)...", links.amazonAsin)
            resolvedStream = amazonProvider.resolveStream(
                asin = links.amazonAsin,
                quality = query.quality,
                isrc = links.isrc ?: query.isrc,
            )
        }

        // 5. Cache result or persist catalog miss
        if (resolvedStream != null) {
            Timber.tag(TAG).i("Successfully hijacked stream with FLAC source: %s (%s, %s)",
                query.mediaId, resolvedStream.provider.displayName, resolvedStream.label)
            android.util.Log.d(TAG, "Successfully hijacked stream with FLAC source: ${query.mediaId} (${resolvedStream.provider.displayName})")
            streamCache[cacheKey] = resolvedStream
            missUntilMs.remove(query.mediaId)
            return resolvedStream
        } else {
            Timber.tag(TAG).d("No FLAC stream available for %s across providers, marking catalog miss", query.mediaId)
            missUntilMs[query.mediaId] = now + MISS_CACHE_TTL_MS
            return null
        }
    }

    private fun getOrResolveLinks(query: FlacTrackQuery): CrossPlatformLinks? {
        linksCache[query.mediaId]?.let { return it }

        val spotifyId = query.spotifyTrackId
        var links = if (!spotifyId.isNullOrBlank()) {
            songLinkClient.resolveLinksForSpotifyTrack(spotifyId)
        } else {
            null
        }

        // Fall back to title + artist track search if direct Spotify ID was missing or unresolved
        if (links == null && query.title.isNotBlank() && query.artists.isNotEmpty()) {
            val primaryArtist = query.artists.first()
            links = songLinkClient.resolveLinksByTrackInfo(
                title = query.title,
                artist = primaryArtist,
                album = query.album,
            )
        }

        if (links != null) {
            linksCache[query.mediaId] = links
        }
        return links
    }
}
