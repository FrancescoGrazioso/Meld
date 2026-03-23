/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

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
 * Caches successful matches in the local Room database.
 */
class SpotifyYouTubeMapper(
    private val database: MusicDatabase,
) {
    /**
     * Maps a Spotify track to a YouTube MediaMetadata by searching YouTube Music.
     * Returns null if no suitable match is found.
     */
    suspend fun mapToYouTube(track: SpotifyTrack): MediaMetadata? = withContext(Dispatchers.IO) {
        val cached = database.getSpotifyMatch(track.id)
        if (cached != null) {
            Timber.d("Spotify match cache hit: ${track.name} -> ${cached.youtubeId} (manual=${cached.isManualOverride})")
            return@withContext buildMediaMetadata(cached.youtubeId, track, cached.title, cached.artist)
        }

        val query = SpotifyMapper.buildSearchQuery(track)
        Timber.d("Searching YouTube for Spotify track: $query")

        val searchResult = YouTube.searchSummary(query).getOrNull() ?: return@withContext null
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
        return metadata.toMediaItem()
    }

    private fun findBestMatch(
        spotifyTrack: SpotifyTrack,
        searchResult: SearchSummaryPage,
    ): MatchCandidate? {
        val spotifyArtist = spotifyTrack.artists.firstOrNull()?.name ?: ""
        val candidates = mutableListOf<MatchCandidate>()

        // Extract SongItems from all search summaries
        val songs = searchResult.summaries
            .flatMap { it.items }
            .filterIsInstance<SongItem>()

        for (song in songs) {
            val score = SpotifyMapper.matchScore(
                spotifyTitle = spotifyTrack.name,
                spotifyArtist = spotifyArtist,
                spotifyDurationMs = spotifyTrack.durationMs,
                candidateTitle = song.title,
                candidateArtist = song.artists.firstOrNull()?.name ?: "",
                candidateDurationSec = song.duration,
            )
            candidates.add(
                MatchCandidate(
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
            )
        }

        return candidates.maxByOrNull { it.score }?.takeIf { it.score >= MIN_MATCH_THRESHOLD }
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
            ?: "https://i.ytimg.com/vi/$youtubeId/hqdefault.jpg"

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
        )
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

        val durationMs = if (durationSec > 0) durationSec * 1000 else 0
        val best = results.maxByOrNull { candidate ->
            SpotifyMapper.matchScore(
                spotifyTitle = candidate.name,
                spotifyArtist = candidate.artists.firstOrNull()?.name ?: "",
                spotifyDurationMs = candidate.durationMs,
                candidateTitle = title,
                candidateArtist = artist,
                candidateDurationSec = if (durationSec > 0) durationSec else null,
            )
        }

        if (best != null) {
            val score = SpotifyMapper.matchScore(
                spotifyTitle = best.name,
                spotifyArtist = best.artists.firstOrNull()?.name ?: "",
                spotifyDurationMs = best.durationMs,
                candidateTitle = title,
                candidateArtist = artist,
                candidateDurationSec = if (durationSec > 0) durationSec else null,
            )
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
    }
}
