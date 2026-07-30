/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import android.content.Context
import com.metrolist.music.constants.EnableMusixmatchKey
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Time-synced lyrics from Musixmatch (the same source Spotify uses).
 *
 * This relies on Musixmatch's unofficial desktop-app API, reached with a guest
 * "user token" (as several open-source projects do). It is opt-in and disabled
 * by default: the token can be rate-limited and some tracks are restricted.
 */
object MusixmatchLyricsProvider : LyricsProvider {
    override val name = "Musixmatch"

    private const val BASE_URL = "https://apic-desktop.musixmatch.com/ws/1.1"
    private const val APP_ID = "web-desktop-app-v1.0"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    // Guest token, fetched lazily and reused for the process lifetime.
    @Volatile
    private var userToken: String? = null
    private val tokenMutex = Mutex()

    private val client by lazy {
        HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 15000
                connectTimeoutMillis = 10000
                socketTimeoutMillis = 15000
            }
            expectSuccess = false
        }
    }

    override fun isEnabled(context: Context): Boolean =
        context.dataStore[EnableMusixmatchKey] ?: false

    private suspend fun ensureToken(): String? {
        userToken?.let { return it }
        return tokenMutex.withLock {
            userToken?.let { return it }
            val fetched = runCatching {
                val text = client.get("$BASE_URL/token.get") {
                    parameter("app_id", APP_ID)
                    parameter("format", "json")
                    header("User-Agent", USER_AGENT)
                    header("Cookie", "AWSELB=0; AWSELBCORS=0")
                }.bodyAsText()

                val message = json.parseToJsonElement(text).jsonObject["message"]?.jsonObject
                val status = message?.get("header")?.jsonObject
                    ?.get("status_code")?.jsonPrimitive?.intOrNull
                if (status != 200) {
                    return@runCatching null
                }
                message.get("body")?.jsonObject
                    ?.get("user_token")?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() && it != "UpgradeOnlyUpgradeOnlyUpgradeOnlyUpgradeOnly" }
            }.getOrNull()
            userToken = fetched
            fetched
        }
    }

    /** Reads `body.macro_calls[call].message.body` for a named sub-call, or null. */
    private fun JsonObject.macroBody(call: String): JsonObject? =
        this["macro_calls"]?.jsonObject
            ?.get(call)?.jsonObject
            ?.get("message")?.jsonObject
            ?.get("body")?.jsonObject

    override suspend fun getLyrics(
        context: Context,
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<String> = runCatching {
        val token = ensureToken() ?: throw IllegalStateException("Musixmatch token unavailable")
        val durationSec = if (duration > 0) duration / 1000 else -1

        val text = client.get("$BASE_URL/macro.subtitles.get") {
            parameter("format", "json")
            parameter("namespace", "lyrics_richsynced")
            parameter("subtitle_format", "lrc")
            parameter("app_id", APP_ID)
            parameter("usertoken", token)
            parameter("q_track", title)
            parameter("q_artist", artist)
            if (!album.isNullOrBlank()) parameter("q_album", album)
            if (durationSec > 0) {
                parameter("q_duration", durationSec)
                parameter("f_subtitle_length", durationSec)
            }
            header("User-Agent", USER_AGENT)
            header("Cookie", "AWSELB=0; AWSELBCORS=0")
        }.bodyAsText()

        val body = json.parseToJsonElement(text).jsonObject["message"]
            ?.jsonObject?.get("body")?.jsonObject
            ?: throw IllegalStateException("Malformed Musixmatch response")

        // Time-synced subtitle (LRC) first — this is what makes lyrics scroll.
        val subtitle = body.macroBody("track.subtitles.get")
            ?.get("subtitle_list")?.jsonArray
            ?.firstOrNull()?.jsonObject
            ?.get("subtitle")?.jsonObject
            ?.get("subtitle_body")?.jsonPrimitive?.contentOrNull

        if (!subtitle.isNullOrBlank()) {
            return@runCatching subtitle
        }

        // Fall back to plain (unsynced) lyrics when no timed subtitle exists.
        val plain = body.macroBody("track.lyrics.get")
            ?.get("lyrics")?.jsonObject
            ?.get("lyrics_body")?.jsonPrimitive?.contentOrNull

        if (!plain.isNullOrBlank()) {
            return@runCatching plain
        }

        throw IllegalStateException("Lyrics unavailable")
    }
}
