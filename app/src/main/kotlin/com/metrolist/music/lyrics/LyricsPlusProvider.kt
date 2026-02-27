/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import android.content.Context
import com.metrolist.music.constants.EnableLyricsPlus
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

@Serializable
private data class LyricLineResponse(
    val time: Long,
    val duration: Long,
    val text: String,
)

@Serializable
private data class LyricsPlusResponse(
    val type: String? = null,
    val lyrics: List<LyricLineResponse>? = null,
    val cached: String? = null,
)

object LyricsPlusProvider : LyricsProvider {
    override val name = "LyricsPlus"

    private val baseUrls = listOf(
        "https://lyricsplus.binimum.org",
        "https://lyricsplus.atomix.one",
        "https://lyricsplus-seven.vercel.app", // might fail since its on vercel...
        //"https://lyricsplus.prjktla.workers.dev", seems to be easily rate-limited
        //"https://lyrics-plus-backend.vercel.app", deployment paused
    )

    private val client by lazy {
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(
                    Json {
                        isLenient = true
                        ignoreUnknownKeys = true
                    },
                )
            }

            install(HttpTimeout) {
                requestTimeoutMillis = 15000
                connectTimeoutMillis = 10000
                socketTimeoutMillis = 15000
            }

            expectSuccess = false
        }
    }

    override fun isEnabled(context: Context): Boolean = context.dataStore[EnableLyricsPlus] ?: false

    private suspend fun fetchFromUrl(
        url: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): LyricsPlusResponse? = runCatching {
        val response = client.get("$url/v2/lyrics/get") {
            parameter("title", title)
            parameter("artist", artist)
            parameter("duration", if (duration > 0) duration / 1000 else -1)
            if (!album.isNullOrBlank()) {
                parameter("album", album)
            }
            parameter("source", "apple,lyricsplus,musixmatch,spotify,musixmatch-word")
        }

        if (response.status == HttpStatusCode.OK) {
            response.body<LyricsPlusResponse>()
        } else {
            null
        }
    }.getOrNull()

    private suspend fun fetchLyrics(
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): LyricsPlusResponse? {
        for (baseUrl in baseUrls) {
            try {
                val result = fetchFromUrl(baseUrl, title, artist, duration, album)
                if (result != null && !result.lyrics.isNullOrEmpty()) {
                    return result
                }
            } catch (e: Exception) {
                Timber.tag("LyricsPlus").d(e, "Failed to fetch from $baseUrl")
                continue
            }
        }
        return null
    }

    private fun convertToLrc(response: LyricsPlusResponse?): String? {
        if (response?.lyrics == null || response.lyrics.isEmpty()) {
            return null
        }

        return response.lyrics.mapNotNull { line ->
            val minutes = line.time / 1000 / 60
            val seconds = (line.time / 1000) % 60
            val millis = line.time % 1000 / 10
            
            if (line.text.isNotBlank()) {
                String.format("[%02d:%02d.%02d]%s", minutes, seconds, millis, line.text)
            } else {
                null
            }
        }.joinToString("\n")
    }

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<String> = runCatching {
        val response = fetchLyrics(title, artist, duration, album)
        val lrc = convertToLrc(response)
        
        if (lrc.isNullOrBlank()) {
            throw IllegalStateException("Lyrics unavailable")
        }
        
        lrc
    }

    override suspend fun getAllLyrics(
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
        callback: (String) -> Unit,
    ) {
        getLyrics(id, title, artist, duration, album)
            .onSuccess { lrcString ->
                callback(lrcString)
            }
    }
}
