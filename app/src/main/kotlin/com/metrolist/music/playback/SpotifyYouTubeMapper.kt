/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.util.LruCache
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.pages.SearchSummaryPage
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.SpotifyMatchEntity
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.MediaMetadata
import com.metrolist.spotify.Spotify
import com.metrolist.spotify.SpotifyMapper
import com.metrolist.spotify.models.SpotifyTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Handles the matching of Spotify tracks to YouTube Music equivalents.
 * Uses fuzzy matching on title, artist, and duration to find the best result.
 * Caches successful matches in the local Room database and an in-memory LRU
 * cache to avoid repeated DB queries for recently resolved tracks.
 */
class SpotifyYouTubeMapper(
    private val database: MusicDatabase,
) {

    private data class CachedMatch(
        val youtubeId: String,
        val title: String,
        val artist: String,
        val isManualOverride: Boolean = false,
    )

    /**
     * Maps a Spotify track to a YouTube MediaMetadata by searching YouTube Music.
     * Returns null if no suitable match is found.
     *
     * Resolution order: in-memory cache → Room DB → YouTube search.
     */
    suspend fun mapToYouTube(track: SpotifyTrack): MediaMetadata? = withContext(Dispatchers.IO) {
        // 1. In-memory LRU cache (zero I/O)
        memoryCache[track.id]?.let { mem ->
            Timber.d("Spotify match memory hit: ${track.name} -> ${mem.youtubeId}")
            return@withContext buildMediaMetadata(mem.youtubeId, track, mem.title, mem.artist)
        }

        // 2. Room DB cache
        val cached = database.getSpotifyMatch(track.id)
        if (cached != null) {
            Timber.d("Spotify match cache hit: ${track.name} -> ${cached.youtubeId} (manual=${cached.isManualOverride})")
            memoryCache.put(track.id, CachedMatch(
                cached.youtubeId, cached.title, cached.artist, cached.isManualOverride,
            ))
            return@withContext buildMediaMetadata(cached.youtubeId, track, cached.title, cached.artist)
        }

        // 3. YouTube search + fuzzy match
        val query = SpotifyMapper.buildSearchQuery(track)
        Timber.d("Searching YouTube for Spotify track: $query")

        // Anonymous search: this is a background match, not a user-initiated
        // search, so it must not be recorded in the user's YouTube search history.
        val searchResult = YouTube.searchSummary(query, incognito = true).getOrNull() ?: return@withContext null
        val bestMatch = findBestMatch(track, searchResult)

        if (bestMatch != null) {
            database.upsertSpotifyMatch(
                SpotifyMatchEntity(
                    spotifyId = track.id,
                    youtubeId = bestMatch.id,
                    title = bestMatch.title,
                    artist = bestMatch.artists.firstOrNull()?.name ?: "",
                    matchScore = bestMatch.score,
                )
            )
            memoryCache.put(track.id, CachedMatch(
                bestMatch.id, bestMatch.title, bestMatch.artistName,
            ))
            Timber.d("Spotify match found: ${track.name} -> ${bestMatch.id} (score: ${bestMatch.score})")
            return@withContext buildMediaMetadata(
                youtubeId = bestMatch.id,
                spotifyTrack = track,
                ytTitle = bestMatch.title,
                ytArtist = bestMatch.artistName,
                ytThumbnailUrl = bestMatch.thumbnailUrl,
            )
        }

        Timber.w("No YouTube match found for Spotify track: ${track.name} by ${track.artists.firstOrNull()?.name}")
        null
    }

    /**
     * Persists a user-chosen YouTube match for a Spotify track.
     * Manual overrides are never replaced by the automatic fuzzy matcher.
     */
    suspend fun overrideMatch(
        spotifyId: String,
        youtubeId: String,
        title: String,
        artist: String,
    ) = withContext(Dispatchers.IO) {
        database.upsertSpotifyMatch(
            SpotifyMatchEntity(
                spotifyId = spotifyId,
                youtubeId = youtubeId,
                title = title,
                artist = artist,
                matchScore = 1.0,
                isManualOverride = true,
            )
        )
        memoryCache.put(spotifyId, CachedMatch(
            youtubeId, title, artist, isManualOverride = true,
        ))
        Timber.d("Manual override saved: $spotifyId -> $youtubeId ($title by $artist)")
    }

    /**
     * Resolves a Spotify track to a MediaItem suitable for the player queue.
     * The MediaItem's id is the YouTube video ID, allowing the existing
     * ResolvingDataSource to resolve the actual stream URL.
     * Returns null if no match was found (track will be skipped).
     */
    suspend fun resolveToMediaItem(track: SpotifyTrack): androidx.media3.common.MediaItem? {
        Timber.d("SpotifyMapper: resolving '${track.name}' by ${track.artists.firstOrNull()?.name}")
        val metadata = mapToYouTube(track)
        if (metadata == null) {
            Timber.w("SpotifyMapper: FAILED to resolve '${track.name}' - no YouTube match")
            return null
        }
        Timber.d("SpotifyMapper: resolved '${track.name}' -> YouTube ID: ${metadata.id}")
        SpotifyMetadataRegistry.register(metadata.id, track)
        return metadata.toMediaItem()
    }

    private fun findBestMatch(
        spotifyTrack: SpotifyTrack,
        searchResult: SearchSummaryPage,
    ): MatchCandidate? {
        val spotifyArtist = spotifyTrack.artists.firstOrNull()?.name ?: ""

        // Pre-compute normalization and bigrams for the Spotify side once
        val precomputed = SpotifyMapper.precompute(
            title = spotifyTrack.name,
            artist = spotifyArtist,
            durationMs = spotifyTrack.durationMs,
        )

        val songs = searchResult.summaries
            .flatMap { it.items }
            .filterIsInstance<SongItem>()

        var bestCandidate: MatchCandidate? = null
        // Ranking score = raw match score minus a penalty for non-studio variants
        // (live/MV/karaoke/…). Kept separate from the stored `score` so the final
        // MIN_MATCH_THRESHOLD check and DB record use the true match quality — a
        // track that ONLY exists as a live version still resolves rather than being
        // skipped, it's just deprioritised when a studio version is also present.
        var bestAdjusted = Double.NEGATIVE_INFINITY
        val spotifyTitleLower = spotifyTrack.name.lowercase()
        val earlyExitThreshold = SpotifyMapper.earlyExitThreshold()

        for (song in songs) {
            val score = SpotifyMapper.matchScorePrecomputed(
                precomputed = precomputed,
                candidateTitle = song.title,
                candidateArtist = song.artists.firstOrNull()?.name ?: "",
                candidateDurationSec = song.duration,
            )
            val adjusted = score - variantPenalty(spotifyTitleLower, song.title)

            if (adjusted > bestAdjusted) {
                bestAdjusted = adjusted
                bestCandidate = MatchCandidate(
                    id = song.id,
                    title = song.title,
                    artistName = song.artists.firstOrNull()?.name ?: "",
                    artists = song.artists.map { MediaMetadata.Artist(id = it.id, name = it.name) },
                    duration = song.duration ?: -1,
                    thumbnailUrl = song.thumbnail,
                    albumId = song.album?.id,
                    albumTitle = song.album?.name,
                    explicit = song.explicit,
                    score = score,
                )
                // Early exit: if this match is excellent, skip remaining candidates
                if (adjusted >= earlyExitThreshold) break
            }
        }

        return bestCandidate?.takeIf { it.score >= MIN_MATCH_THRESHOLD }
    }

    private fun buildMediaMetadata(
        youtubeId: String,
        spotifyTrack: SpotifyTrack,
        ytTitle: String,
        ytArtist: String,
        ytThumbnailUrl: String? = null,
    ): MediaMetadata {
        val thumbnail = SpotifyMapper.getTrackThumbnail(spotifyTrack)
            ?: ytThumbnailUrl
            ?: "https://i.ytimg.com/vi/$youtubeId/maxresdefault.jpg"

        return MediaMetadata(
            id = youtubeId,
            title = ytTitle.ifEmpty { spotifyTrack.name },
            artists = if (ytArtist.isNotEmpty()) {
                listOf(MediaMetadata.Artist(id = null, name = ytArtist))
            } else {
                spotifyTrack.artists.map { MediaMetadata.Artist(id = null, name = it.name) }
            },
            duration = spotifyTrack.durationMs / 1000,
            thumbnailUrl = thumbnail,
            album = spotifyTrack.album?.let {
                MediaMetadata.Album(id = it.id, title = it.name)
            },
            explicit = spotifyTrack.explicit,
            isrc = spotifyTrack.isrc,
        )
    }

    /**
     * Penalises YouTube candidates that look like a non-studio variant (live, music
     * video, karaoke, cover, sped-up/slowed edits, …) when the Spotify track itself
     * is not such a variant. This steers automatic matching toward the official
     * studio audio for tracks like "As It Was", where a live/MV upload would
     * otherwise tie on title/artist and win purely on search order (see #211).
     *
     * Markers are matched as whole words to avoid false positives (e.g. "live"
     * inside "Alive"). The penalty only applies to markers present in the candidate
     * but absent from the Spotify title, so intentional live/remix tracks are unaffected.
     */
    private fun variantPenalty(spotifyTitleLower: String, candidateTitle: String): Double {
        val candMarkers = VARIANT_MARKER_REGEX.findAll(candidateTitle.lowercase())
            .map { it.value }.toSet()
        if (candMarkers.isEmpty()) return 0.0
        val spotifyMarkers = VARIANT_MARKER_REGEX.findAll(spotifyTitleLower)
            .map { it.value }.toSet()
        val extra = candMarkers - spotifyMarkers
        return (extra.size * VARIANT_PENALTY_PER_MARKER).coerceAtMost(MAX_VARIANT_PENALTY)
    }

    private data class MatchCandidate(
        val id: String,
        val title: String,
        val artistName: String,
        val artists: List<MediaMetadata.Artist>,
        val duration: Int,
        val thumbnailUrl: String?,
        val albumId: String?,
        val albumTitle: String?,
        val explicit: Boolean,
        val score: Double,
    )

    /**
     * Reverse lookup: given a YouTube track's metadata, finds the corresponding
     * Spotify URI. Uses a two-tier strategy:
     * 1. Fast path — checks the local cache for an existing Spotify↔YouTube match.
     * 2. Slow path — searches Spotify by title+artist and picks the best match.
     *
     * @return A full Spotify URI (e.g. "spotify:track:abc123") or null if not found.
     */
    suspend fun resolveToSpotifyUri(
        youtubeId: String,
        title: String,
        artist: String,
        durationSec: Int = -1,
    ): String? = withContext(Dispatchers.IO) {
        database.getSpotifyMatchByYouTubeId(youtubeId)?.let { cached ->
            Timber.d("Reverse lookup cache hit: $youtubeId -> spotify:track:${cached.spotifyId}")
            return@withContext "spotify:track:${cached.spotifyId}"
        }

        val query = if (artist.isBlank()) title else "$artist $title"
        Timber.d("Reverse lookup: searching Spotify for '$query'")

        val results = Spotify.search(query, types = listOf("track"), limit = 5)
            .getOrNull()?.tracks?.items
        if (results.isNullOrEmpty()) {
            Timber.w("Reverse lookup: no Spotify results for '$query'")
            return@withContext null
        }

        // Pre-compute for the YouTube side (the "reference" in reverse lookup)
        val ytPrecomputed = SpotifyMapper.precompute(
            title = title,
            artist = artist,
            durationMs = if (durationSec > 0) durationSec * 1000 else 0,
        )

        var best: SpotifyTrack? = null
        var bestScore = 0.0
        for (candidate in results) {
            val score = SpotifyMapper.matchScorePrecomputed(
                precomputed = ytPrecomputed,
                candidateTitle = candidate.name,
                candidateArtist = candidate.artists.firstOrNull()?.name ?: "",
                candidateDurationSec = candidate.durationMs / 1000,
            )
            if (score > bestScore) {
                best = candidate
                bestScore = score
            }
        }

        if (best != null) {
            val score = bestScore
            if (score >= MIN_MATCH_THRESHOLD) {
                val uri = best.uri ?: "spotify:track:${best.id}"
                Timber.d("Reverse lookup found: $youtubeId -> $uri (score=$score)")
                database.upsertSpotifyMatch(
                    SpotifyMatchEntity(
                        spotifyId = best.id,
                        youtubeId = youtubeId,
                        title = title,
                        artist = artist,
                        matchScore = score,
                    ),
                )
                return@withContext uri
            }
        }

        Timber.w("Reverse lookup: no match above threshold for '$query'")
        null
    }

    companion object {
        private const val MIN_MATCH_THRESHOLD = 0.35
        private const val MEM_CACHE_MAX_SIZE = 512

        /** Per-marker ranking penalty for non-studio variants, capped by [MAX_VARIANT_PENALTY]. */
        private const val VARIANT_PENALTY_PER_MARKER = 0.15
        private const val MAX_VARIANT_PENALTY = 0.30

        /** Whole-word markers that indicate a non-studio upload (live/MV/edit/etc.). */
        private val VARIANT_MARKER_REGEX = Regex(
            "\\b(live|en vivo|en directo|ao vivo|karaoke|cover|instrumental|" +
                "sped up|spedup|slowed|nightcore|8d|music video|official video|lyric video)\\b"
        )

        /**
         * Process-wide, thread-safe LRU cache of recently resolved Spotify→YouTube
         * matches. Shared across all mapper instances (each screen/queue builds its
         * own mapper) so a match resolved on one screen is reused everywhere without
         * DB I/O. [android.util.LruCache] serializes get/put internally, which is
         * required because queues resolve batches in parallel on Dispatchers.IO.
         * Bounded to 512 entries (~30 KB).
         */
        private val memoryCache = LruCache<String, CachedMatch>(MEM_CACHE_MAX_SIZE)
    }
}
