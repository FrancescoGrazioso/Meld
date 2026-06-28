package com.metrolist.lastfm

import com.metrolist.lastfm.models.Authentication
import com.metrolist.lastfm.models.LastFmError
import com.metrolist.lastfm.models.TokenResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.*
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.security.MessageDigest

object LastFM {
    var sessionKey: String? = null

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    private val client by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(json)
            }
            defaultRequest { url("https://ws.audioscrobbler.com/2.0/") }
            expectSuccess = false
        }
    }

    private fun Map<String, String>.apiSig(secret: String): String {
        val sorted = toSortedMap()
        val toHash = sorted.entries.joinToString("") { it.key + it.value } + secret
        val digest = MessageDigest.getInstance("MD5").digest(toHash.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun HttpRequestBuilder.lastfmParams(
        method: String,
        apiKey: String,
        secret: String,
        sessionKey: String? = null,
        extra: Map<String, String> = emptyMap(),
        format: String = "json"
    ) {
        contentType(ContentType.Application.FormUrlEncoded)
        userAgent("Meld (https://github.com/FrancescoGrazioso/Meld)")
        val paramsForSig = mutableMapOf(
            "method" to method,
            "api_key" to apiKey
        ).apply {
            sessionKey?.let { put("sk", it) }
            putAll(extra)
        }
        val apiSig = paramsForSig.apiSig(secret)
        setBody(FormDataContent(Parameters.build {
            paramsForSig.forEach { (k, v) -> append(k, v) }
            append("api_sig", apiSig)
            append("format", format)
        }))
    }

    // OAuth methods (kept for backward compatibility)
    suspend fun getToken() = runCatching {
        client.post {
            lastfmParams(
                method = "auth.getToken",
                apiKey = API_KEY,
                secret = SECRET
            )
        }.body<TokenResponse>()
    }

    suspend fun getSession(token: String) = runCatching {
        client.post {
            lastfmParams(
                method = "auth.getSession",
                apiKey = API_KEY,
                secret = SECRET,
                extra = mapOf("token" to token)
            )
        }.body<Authentication>()
    }

    fun getAuthUrl(token: String): String {
        return "https://www.last.fm/api/auth/?api_key=$API_KEY&token=$token"
    }

    // Mobile session authentication
    suspend fun getMobileSession(username: String, password: String) = runCatching {
        val response = client.post {
            lastfmParams(
                method = "auth.getMobileSession",
                apiKey = API_KEY,
                secret = SECRET,
                extra = mapOf("username" to username, "password" to password)
            )
            parameter("format", "json")
        }

        val responseText = response.bodyAsText()
        if (responseText.contains("\"error\"")) {
            val error = json.decodeFromString<LastFmError>(responseText)
            throw LastFmException(error.error, error.message)
        }
        json.decodeFromString<Authentication>(responseText)
    }

    class LastFmException(val code: Int, override val message: String) : Exception(message) {
        override fun toString(): String = "LastFmException(code=$code, message=$message)"
    }

    /**
     * Thrown when last.fm accepts a scrobble request (HTTP 200) but marks the
     * scrobble as ignored via a non-zero ignoredMessage code in the response body.
     * Common codes: 1=filtered artist, 2=filtered track, 3=filtered timestamp,
     * 4=exceeded daily scrobble limit.
     */
    class ScrobbleIgnoredException(val code: Int, val body: String) :
        Exception("Scrobble ignored by last.fm (code=$code)") {
        override fun toString(): String = "ScrobbleIgnoredException(code=$code)"
    }

    suspend fun updateNowPlaying(
        artist: String, track: String,
        album: String? = null, trackNumber: Int? = null, duration: Int? = null
    ) = runCatching {
        client.post {
            lastfmParams(
                method = "track.updateNowPlaying",
                apiKey = API_KEY,
                secret = SECRET,
                sessionKey = sessionKey!!,
                extra = buildMap {
                    put("artist", artist)
                    put("track", track)
                    album?.let { put("album", it) }
                    trackNumber?.let { put("trackNumber", it.toString()) }
                    duration?.let { put("duration", it.toString()) }
                }
            )
        }
    }

    suspend fun scrobble(
        artist: String, track: String, timestamp: Long,
        album: String? = null, trackNumber: Int? = null, duration: Int? = null
    ) = runCatching {
        val response = client.post {
            lastfmParams(
                method = "track.scrobble",
                apiKey = API_KEY,
                secret = SECRET,
                sessionKey = sessionKey!!,
                extra = buildMap {
                    put("artist[0]", artist)
                    put("track[0]", track)
                    put("timestamp[0]", timestamp.toString())
                    album?.let { put("album[0]", it) }
                    trackNumber?.let { put("trackNumber[0]", it.toString()) }
                    duration?.let { put("duration[0]", it.toString()) }
                }
            )
        }
        // Parse the response to detect silently-ignored scrobbles.
        // Last.fm returns HTTP 200 even when a scrobble is ignored, so we must
        // inspect the body for ignoredMessage codes (non-zero means ignored).
        val responseText = response.bodyAsText()
        if (responseText.contains("\"ignoredMessage\"")) {
            val ignored = runCatching {
                val codeRegex = Regex(""""ignoredMessage"\s*:\s*\{[^}]*"code"\s*:\s*"([^"]+)"""")
                codeRegex.find(responseText)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            }.getOrDefault(0)
            if (ignored != 0) {
                throw ScrobbleIgnoredException(ignored, responseText)
            }
        }
        response
    }

    suspend fun setLoveStatus(
        artist: String, track: String, love: Boolean
    ) = runCatching {
        val method = if (love) "track.love" else "track.unlove"
        client.post {
            lastfmParams(
                method = method,
                apiKey = API_KEY,
                secret = SECRET,
                sessionKey = sessionKey!!,
                extra = buildMap {
                    put("artist", artist)
                    put("track", track)
                }
            )
            parameter("format", "json")
        }
    }

    // API keys passed from the app module (loaded from BuildConfig/GitHub Secrets)
    private var API_KEY = ""
    private var SECRET = ""

    /**
     * Initialize LastFM with API credentials
     * @param apiKey LastFM API key
     * @param secret LastFM secret key
     */
    fun initialize(apiKey: String, secret: String) {
        API_KEY = apiKey
        SECRET = secret
    }

    fun isInitialized(): Boolean = API_KEY.isNotEmpty() && SECRET.isNotEmpty()

    const val DEFAULT_SCROBBLE_DELAY_PERCENT = 0.5f
    const val DEFAULT_SCROBBLE_MIN_SONG_DURATION = 30
    const val DEFAULT_SCROBBLE_DELAY_SECONDS = 180
}
