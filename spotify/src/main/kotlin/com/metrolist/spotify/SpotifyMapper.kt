package com.metrolist.spotify

import com.metrolist.spotify.models.SpotifyPlaylist
import com.metrolist.spotify.models.SpotifyTrack

/**
 * Utility object for creating search queries from Spotify track data.
 * The actual mapping to Metrolist MediaMetadata is done in the app module
 * where MediaMetadata class is available.
 */
object SpotifyMapper {

    // Pre-compiled regex patterns for title normalization (avoids re-creation on each call)
    private val FEAT_PATTERN = Regex("\\(feat\\..*?\\)")
    private val FT_PATTERN = Regex("\\(ft\\..*?\\)")
    private val BRACKET_PATTERN = Regex("\\[.*?]")
    private val REMASTER_PATTERN = Regex("\\(.*?remaster.*?\\)", RegexOption.IGNORE_CASE)
    private val REMIX_PATTERN = Regex("\\(.*?remix.*?\\)", RegexOption.IGNORE_CASE)
    private val NON_ALNUM_PATTERN = Regex("[^a-z0-9\\s]")
    private val MULTI_SPACE_PATTERN = Regex("\\s+")

    /**
     * Builds a YouTube search query from a Spotify track.
     * The query is optimized for finding the matching song on YouTube Music.
     */
    fun buildSearchQuery(track: SpotifyTrack): String {
        val artist = track.artists.firstOrNull()?.name.orEmpty()
        val title = track.name
        return if (artist.isEmpty()) title else "$artist $title"
    }

    /**
     * Returns the best thumbnail URL from a Spotify playlist, preferring medium resolution.
     */
    fun getPlaylistThumbnail(playlist: SpotifyPlaylist): String? {
        return playlist.images.let { images ->
            // Prefer 300x300 or similar medium size, fallback to first
            images.firstOrNull { it.width in 200..400 }?.url
                ?: images.firstOrNull()?.url
        }
    }

    /**
     * Returns the best thumbnail URL from a Spotify track's album art.
     */
    fun getTrackThumbnail(track: SpotifyTrack): String? {
        return track.album?.images?.let { images ->
            images.firstOrNull { it.width in 200..400 }?.url
                ?: images.firstOrNull()?.url
        }
    }

    /**
     * Computes a match confidence score (0.0 - 1.0) between a Spotify track and
     * a candidate result based on title, artist, and duration similarity.
     */
    fun matchScore(
        spotifyTitle: String,
        spotifyArtist: String,
        spotifyDurationMs: Int,
        candidateTitle: String,
        candidateArtist: String,
        candidateDurationSec: Int?,
    ): Double {
        val titleScore = stringSimilarity(
            normalizeTitle(spotifyTitle),
            normalizeTitle(candidateTitle),
        )
        val artistScore = stringSimilarity(
            normalizeTitle(spotifyArtist),
            normalizeTitle(candidateArtist),
        )

        // Duration matching: tolerate up to 5 seconds difference
        val durationScore = if (candidateDurationSec != null && spotifyDurationMs > 0) {
            val spotifyDurationSec = spotifyDurationMs / 1000
            val diff = kotlin.math.abs(spotifyDurationSec - candidateDurationSec)
            when {
                diff <= 2 -> 1.0
                diff <= 5 -> 0.8
                diff <= 10 -> 0.5
                diff <= 30 -> 0.2
                else -> 0.0
            }
        } else {
            0.5 // Neutral if duration unknown
        }

        // Weighted score: title most important, then artist, then duration
        return titleScore * 0.45 + artistScore * 0.35 + durationScore * 0.20
    }

    /**
     * Normalizes a title for comparison by lowering case and removing common suffixes.
     */
    private fun normalizeTitle(title: String): String {
        return title.lowercase()
            .replace(FEAT_PATTERN, "")
            .replace(FT_PATTERN, "")
            .replace(BRACKET_PATTERN, "")
            .replace(REMASTER_PATTERN, "")
            .replace(REMIX_PATTERN, "")
            .replace(NON_ALNUM_PATTERN, "")
            .replace(MULTI_SPACE_PATTERN, " ")
            .trim()
    }

    /**
     * Simple string similarity based on common character bigrams (Dice coefficient).
     */
    private fun stringSimilarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.length < 2 || b.length < 2) return 0.0

        val bigramsA = a.windowed(2).toSet()
        val bigramsB = b.windowed(2).toSet()
        val intersection = bigramsA.intersect(bigramsB).size

        return (2.0 * intersection) / (bigramsA.size + bigramsB.size)
    }
}
