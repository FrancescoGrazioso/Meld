/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.pages.SearchSummaryPage
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.SoundCloudMatchEntity
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.MediaMetadata
import com.metrolist.soundcloud.models.SoundCloudTrack
import com.metrolist.spotify.SpotifyMapper // Using same matching logic internally if we want to share
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Handles the matching of SoundCloud tracks to YouTube Music equivalents.
 * Uses fuzzy matching on title, artist, and duration to find the best result.
 * Caches successful matches in the local Room database.
 */
class SoundCloudYouTubeMapper(
    private val database: MusicDatabase,
) {
    /**
     * Maps a SoundCloud track to a YouTube MediaMetadata by searching YouTube Music.
     * Returns null if no suitable match is found.
     */
    suspend fun mapToYouTube(track: SoundCloudTrack): MediaMetadata? = withContext(Dispatchers.IO) {
        val cached = database.getSoundCloudMatch(track.id)
        if (cached != null) {
            Timber.d("SoundCloud match cache hit: ${track.title} -> ${cached.youtubeId} (manual=${cached.isManualOverride})")
            return@withContext buildMediaMetadata(cached.youtubeId, track, cached.title, cached.artist)
        }

        val artistName = track.user?.username ?: ""
        val query = "$artistName ${track.title}"
        Timber.d("Searching YouTube for SoundCloud track: $query")

        val searchResult = YouTube.searchSummary(query).getOrNull() ?: return@withContext null
        val bestMatch = findBestMatch(track, searchResult)

        if (bestMatch != null) {
            database.upsertSoundCloudMatch(
                SoundCloudMatchEntity(
                    soundCloudId = track.id,
                    youtubeId = bestMatch.id,
                    title = bestMatch.title,
                    artist = bestMatch.artists.firstOrNull()?.name ?: "",
                    matchScore = bestMatch.score,
                )
            )
            Timber.d("SoundCloud match found: ${track.title} -> ${bestMatch.id} (score: ${bestMatch.score})")
            return@withContext buildMediaMetadata(
                youtubeId = bestMatch.id,
                soundCloudTrack = track,
                ytTitle = bestMatch.title,
                ytArtist = bestMatch.artistName,
                ytThumbnailUrl = bestMatch.thumbnailUrl,
            )
        }

        Timber.w("No YouTube match found for SoundCloud track: ${track.title} by $artistName")
        null
    }

    /**
     * Persists a user-chosen YouTube match for a SoundCloud track.
     */
    suspend fun overrideMatch(
        soundCloudId: Long,
        youtubeId: String,
        title: String,
        artist: String,
    ) = withContext(Dispatchers.IO) {
        database.upsertSoundCloudMatch(
            SoundCloudMatchEntity(
                soundCloudId = soundCloudId,
                youtubeId = youtubeId,
                title = title,
                artist = artist,
                matchScore = 1.0,
                isManualOverride = true,
            )
        )
        Timber.d("Manual override saved: $soundCloudId -> $youtubeId ($title by $artist)")
    }

    /**
     * Resolves a SoundCloud track to a MediaItem suitable for the player queue.
     */
    suspend fun resolveToMediaItem(track: SoundCloudTrack): androidx.media3.common.MediaItem? {
        val artistName = track.user?.username ?: ""
        Timber.d("SoundCloudMapper: resolving '${track.title}' by $artistName")
        val metadata = mapToYouTube(track)
        if (metadata == null) {
            Timber.w("SoundCloudMapper: FAILED to resolve '${track.title}' - no YouTube match")
            return null
        }
        Timber.d("SoundCloudMapper: resolved '${track.title}' -> YouTube ID: ${metadata.id}")
        return metadata.toMediaItem()
    }

    private fun findBestMatch(
        soundCloudTrack: SoundCloudTrack,
        searchResult: SearchSummaryPage,
    ): MatchCandidate? {
        val soundCloudArtist = soundCloudTrack.user?.username ?: ""
        val candidates = mutableListOf<MatchCandidate>()

        val songs = searchResult.summaries
            .flatMap { it.items }
            .filterIsInstance<SongItem>()

        for (song in songs) {
            // Reusing SpotifyMapper's matchScore for now, as it does generic fuzzy matching
            val score = SpotifyMapper.matchScore(
                spotifyTitle = soundCloudTrack.title,
                spotifyArtist = soundCloudArtist,
                spotifyDurationMs = (soundCloudTrack.duration).toInt(),
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
        soundCloudTrack: SoundCloudTrack,
        ytTitle: String,
        ytArtist: String,
        ytThumbnailUrl: String? = null,
    ): MediaMetadata {
        val artistName = soundCloudTrack.user?.username ?: ""
        val thumbnail = soundCloudTrack.artwork_url
            ?: ytThumbnailUrl
            ?: "https://i.ytimg.com/vi/$youtubeId/hqdefault.jpg"

        return MediaMetadata(
            id = youtubeId,
            title = ytTitle.ifEmpty { soundCloudTrack.title },
            artists = if (ytArtist.isNotEmpty()) {
                listOf(MediaMetadata.Artist(id = null, name = ytArtist))
            } else {
                listOf(MediaMetadata.Artist(id = null, name = artistName))
            },
            duration = (soundCloudTrack.duration / 1000).toInt(),
            thumbnailUrl = thumbnail,
            album = null,
            explicit = false,
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

    companion object {
        private const val MIN_MATCH_THRESHOLD = 0.35
    }
}
