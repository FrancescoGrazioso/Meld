/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.network.flac

enum class FlacProvider(val displayName: String) {
    TIDAL("Tidal"),
    QOBUZ("Qobuz"),
    AMAZON("Amazon Music")
}

enum class FlacAudioQuality(val displayName: String, val bitDepth: Int, val sampleRate: Int) {
    LOSSLESS_16("CD Quality (16-bit/44.1kHz)", 16, 44100),
    HI_RES_24("Hi-Res (24-bit/96kHz+)", 24, 96000);

    fun toQualityCode(): String = when (this) {
        LOSSLESS_16 -> "16"
        HI_RES_24 -> "24"
    }

    companion object {
        fun fromQualityCode(code: String): FlacAudioQuality = when (code) {
            "16" -> LOSSLESS_16
            else -> HI_RES_24
        }
    }
}

data class CrossPlatformLinks(
    val spotifyTrackId: String? = null,
    val isrc: String? = null,
    val tidalTrackId: String? = null,
    val tidalUrl: String? = null,
    val amazonAsin: String? = null,
    val amazonUrl: String? = null,
    val qobuzTrackId: String? = null,
    val qobuzUrl: String? = null,
    val deezerTrackId: String? = null,
    val deezerUrl: String? = null,
) {
    val hasTidal: Boolean get() = !tidalTrackId.isNullOrBlank() || !tidalUrl.isNullOrBlank()
    val hasAmazon: Boolean get() = !amazonAsin.isNullOrBlank() || !amazonUrl.isNullOrBlank()
    val hasQobuz: Boolean get() = !qobuzTrackId.isNullOrBlank() || !qobuzUrl.isNullOrBlank()
    val hasDeezer: Boolean get() = !deezerTrackId.isNullOrBlank() || !deezerUrl.isNullOrBlank()
    val hasAny: Boolean get() = hasTidal || hasAmazon || hasQobuz || hasDeezer || !isrc.isNullOrBlank()
}

data class FlacTrackQuery(
    val mediaId: String,
    val title: String,
    val artists: List<String>,
    val album: String? = null,
    val isrc: String? = null,
    val spotifyTrackId: String? = null,
    val durationMs: Long? = null,
    val quality: FlacAudioQuality = FlacAudioQuality.HI_RES_24,
)

data class FlacStreamResult(
    val mediaUri: String,
    val provider: FlacProvider,
    val label: String,
    val mimeType: String = "audio/flac",
    val bitrate: Int = 1411200,
    val bitDepth: Int? = null,
    val sampleRate: Int? = null,
    val isrc: String? = null,
    val trackId: String? = null,
    val expiresAtMs: Long = System.currentTimeMillis() + 5 * 60 * 1000L,
)
