/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import androidx.media3.common.C
import com.metrolist.lastfm.LastFM
import com.metrolist.music.models.MediaMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.min

class ScrobbleManager(
    private val scope: CoroutineScope,
    var minSongDuration: Int = LastFM.DEFAULT_SCROBBLE_MIN_SONG_DURATION,
    var scrobbleDelayPercent: Float = LastFM.DEFAULT_SCROBBLE_DELAY_PERCENT,
    var scrobbleDelaySeconds: Int = LastFM.DEFAULT_SCROBBLE_DELAY_SECONDS,
    private val client: LastFmScrobbleClient = LastFmScrobbleClient.Default,
) {
    private var scrobbleJob: Job? = null
    private var scrobbleRemainingMillis: Long = 0L
    private var scrobbleTimerStartedAt: Long = 0L
    private var songStartedAt: Long = 0L
    private var songStarted = false
    private var currentMetadata: MediaMetadata? = null
    private var currentSongDuration = 0
    private var currentSongScrobbled = false
    var useNowPlaying = true

    fun destroy() {
        scrobbleJob?.cancel()
        resetCurrentSong()
    }

    fun onSongStart(metadata: MediaMetadata?, duration: Long? = null) {
        if (metadata == null) return
        val startedAt = System.currentTimeMillis() / 1000
        val durationSeconds = resolveDuration(metadata, duration)
        songStartedAt = startedAt
        songStarted = true
        currentMetadata = metadata
        currentSongDuration = durationSeconds
        currentSongScrobbled = false
        startScrobbleTimer(metadata, durationSeconds, startedAt)
        if (useNowPlaying) {
            updateNowPlaying(metadata, durationSeconds)
        }
    }

    fun onSongResume(metadata: MediaMetadata) {
        resumeScrobbleTimer(metadata)
    }

    fun onSongPause() {
        pauseScrobbleTimer()
    }

    fun onSongStop(finalize: Boolean = false) {
        if (finalize || hasReachedScrobbleThreshold()) {
            scrobbleCurrentSongIfNeeded()
        }
        stopScrobbleTimer()
        resetCurrentSong()
    }

    private fun startScrobbleTimer(
        metadata: MediaMetadata,
        duration: Int,
        startedAt: Long = songStartedAt
    ) {
        scrobbleJob?.cancel()
        if (duration <= minSongDuration) return

        val threshold = duration * 1000L * scrobbleDelayPercent
        scrobbleRemainingMillis = min(threshold.toLong(), scrobbleDelaySeconds * 1000L)

        if (scrobbleRemainingMillis <= 0) {
            scrobbleSongIfNeeded(metadata, startedAt, duration)
            return
        }
        scrobbleTimerStartedAt = System.currentTimeMillis()
        scrobbleJob = scope.launch {
            delay(scrobbleRemainingMillis)
            scrobbleSongIfNeeded(metadata, startedAt, duration)
            scrobbleJob = null
            scrobbleRemainingMillis = 0L
            scrobbleTimerStartedAt = 0L
        }
    }

    private fun pauseScrobbleTimer() {
        scrobbleJob?.cancel()
        if (scrobbleTimerStartedAt != 0L) {
            val elapsed = System.currentTimeMillis() - scrobbleTimerStartedAt
            scrobbleRemainingMillis -= elapsed
            if (scrobbleRemainingMillis < 0) scrobbleRemainingMillis = 0
            scrobbleTimerStartedAt = 0L
        } else {
        }
    }

    private fun resumeScrobbleTimer(metadata: MediaMetadata) {
        if (scrobbleRemainingMillis <= 0) return
        scrobbleJob?.cancel()
        scrobbleTimerStartedAt = System.currentTimeMillis()
        scrobbleJob = scope.launch {
            delay(scrobbleRemainingMillis)
            scrobbleSongIfNeeded(metadata, songStartedAt, currentSongDuration)
            scrobbleJob = null
            scrobbleRemainingMillis = 0L
            scrobbleTimerStartedAt = 0L
        }
    }

    private fun stopScrobbleTimer() {
        scrobbleJob?.cancel()
        scrobbleJob = null
        scrobbleRemainingMillis = 0
    }

    private fun hasReachedScrobbleThreshold(): Boolean {
        if (currentSongDuration <= minSongDuration || currentSongScrobbled) return false
        return remainingScrobbleMillis() <= 0L
    }

    private fun remainingScrobbleMillis(): Long =
        if (scrobbleTimerStartedAt == 0L) {
            scrobbleRemainingMillis
        } else {
            scrobbleRemainingMillis - (System.currentTimeMillis() - scrobbleTimerStartedAt)
        }

    private fun scrobbleCurrentSongIfNeeded() {
        val metadata = currentMetadata ?: return
        if (currentSongDuration <= minSongDuration) return
        scrobbleSongIfNeeded(metadata, songStartedAt, currentSongDuration)
    }

    private fun scrobbleSongIfNeeded(
        metadata: MediaMetadata,
        startedAt: Long,
        duration: Int,
    ) {
        if (currentSongScrobbled || startedAt <= 0L) return
        currentSongScrobbled = true
        scrobbleSong(metadata, startedAt, duration)
    }

    private fun scrobbleSong(
        metadata: MediaMetadata,
        startedAt: Long,
        duration: Int,
    ) {
        scope.launch {
            val artist = metadata.artists.joinToString { it.name }
            client.scrobble(
                artist = artist,
                track = metadata.title,
                duration = duration.takeIf { it > 0 },
                timestamp = startedAt,
                album = metadata.album?.title,
            ).onSuccess {
                Timber.d("Last.fm: scrobbled \"${metadata.title}\" by $artist")
            }.onFailure { e ->
                if (e is LastFM.LastFmIgnoredException) {
                    Timber.w(
                        "Last.fm: scrobble ignored for \"${metadata.title}\" by $artist " +
                            "(code ${e.ignoredCode}): ${e.message}"
                    )
                } else {
                    Timber.w(e, "Last.fm: scrobble failed for \"${metadata.title}\" by $artist")
                }
            }
        }
    }

    private fun resolveDuration(metadata: MediaMetadata, duration: Long? = null): Int =
        when {
            duration == null || duration == C.TIME_UNSET || duration <= 0 -> metadata.duration
            else -> (duration / 1000).toInt()
        }

    private fun resetCurrentSong() {
        scrobbleRemainingMillis = 0L
        scrobbleTimerStartedAt = 0L
        songStartedAt = 0L
        songStarted = false
        currentMetadata = null
        currentSongDuration = 0
        currentSongScrobbled = false
    }

    private fun updateNowPlaying(metadata: MediaMetadata, duration: Int) {
        scope.launch {
            client.updateNowPlaying(
                artist = metadata.artists.joinToString { it.name },
                track = metadata.title,
                album = metadata.album?.title,
                duration = duration.takeIf { it > 0 },
            ).onFailure { e ->
                if (e is LastFM.LastFmIgnoredException) {
                    Timber.w(
                        "Last.fm: updateNowPlaying ignored for \"${metadata.title}\" " +
                            "(code ${e.ignoredCode}): ${e.message}"
                    )
                } else {
                    Timber.w(e, "Last.fm: updateNowPlaying failed for \"${metadata.title}\"")
                }
            }
        }
    }

    fun onPlayerStateChanged(isPlaying: Boolean, metadata: MediaMetadata?, duration: Long? = null) {
        if (metadata == null) return
        if (isPlaying) {
            if (!songStarted || currentMetadata?.id != metadata.id) {
                if (songStarted) {
                    onSongStop()
                }
                onSongStart(metadata, duration)
            } else {
                onSongResume(metadata)
            }
        } else {
            onSongPause()
        }
    }
}

interface LastFmScrobbleClient {
    suspend fun updateNowPlaying(
        artist: String,
        track: String,
        album: String? = null,
        duration: Int? = null,
    ): Result<Unit>

    suspend fun scrobble(
        artist: String,
        track: String,
        timestamp: Long,
        album: String? = null,
        duration: Int? = null,
    ): Result<Unit>

    object Default : LastFmScrobbleClient {
        override suspend fun updateNowPlaying(
            artist: String,
            track: String,
            album: String?,
            duration: Int?,
        ): Result<Unit> =
            LastFM.updateNowPlaying(
                artist = artist,
                track = track,
                album = album,
                duration = duration,
            )

        override suspend fun scrobble(
            artist: String,
            track: String,
            timestamp: Long,
            album: String?,
            duration: Int?,
        ): Result<Unit> =
            LastFM.scrobble(
                artist = artist,
                track = track,
                duration = duration,
                timestamp = timestamp,
                album = album,
            )
    }
}
