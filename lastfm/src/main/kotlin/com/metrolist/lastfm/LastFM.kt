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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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

    class LastFmIgnoredException(
        val method: String,
        val ignoredCode: Int,
        override val message: String
    ) : Exception(message) {
        override fun toString(): String =
            "LastFmIgnoredException(method=$method, ignoredCode=$ignoredCode, message=$message)"
    }

    suspend fun updateNowPlaying(
        artist: String, track: String,
        album: String? = null, trackNumber: Int? = null, duration: Int? = null
    ) = runCatching {
        val response = client.post {
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
            parameter("format", "json")
        }
        validateLastFmResponse(
            method = "track.updateNowPlaying",
            responseText = response.bodyAsText(),
            statusCode = response.status.value,
        )
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
            parameter("format", "json")
        }
        validateLastFmResponse(
            method = "track.scrobble",
            responseText = response.bodyAsText(),
            statusCode = response.status.value,
        )
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

    internal fun validateLastFmResponse(
        method: String,
        responseText: String,
        statusCode: Int = 200
    ) {
        val root = runCatching { json.parseToJsonElement(responseText).jsonObject }
            .getOrElse {
                if (statusCode !in 200..299) {
                    throw LastFmException(statusCode, "Last.fm $method failed with HTTP $statusCode")
                }
                throw LastFmException(-1, "Last.fm $method returned an invalid response")
            }

        root.intValue("error")?.let { code ->
            throw LastFmException(code, root.stringValue("message") ?: "Last.fm $method failed")
        }

        if (statusCode !in 200..299) {
            throw LastFmException(statusCode, "Last.fm $method failed with HTTP $statusCode")
        }

        when (method) {
            "track.scrobble" -> {
                val scrobbles = root["scrobbles"]?.jsonObjectOrNull()
                    ?: throw LastFmException(-1, "Last.fm $method returned an invalid response")
                validateScrobbleResult(scrobbles)
            }

            "track.updateNowPlaying" -> {
                val nowPlaying = root["nowplaying"]?.jsonObjectOrNull()
                    ?: throw LastFmException(-1, "Last.fm $method returned an invalid response")
                nowPlaying.findIgnoredMessage()?.throwIfIgnored(method)
            }
        }
    }

    private fun validateScrobbleResult(scrobbles: JsonObject) {
        val ignoredCount = scrobbles["@attr"]
            ?.jsonObjectOrNull()
            ?.intValue("ignored")
            ?: 0

        val ignoredMessage = scrobbles["scrobble"]
            ?.asIterable()
            ?.mapNotNull { it.jsonObjectOrNull()?.findIgnoredMessage() }
            ?.firstOrNull { it.code != 0 }

        if (ignoredCount > 0) {
            (ignoredMessage ?: IgnoredMessage(code = -1, message = "Last.fm ignored the scrobble"))
                .throwIfIgnored("track.scrobble")
        }
    }

    private fun JsonObject.findIgnoredMessage(): IgnoredMessage? {
        val ignoredMessage = this["ignoredMessage"]?.jsonObjectOrNull()
            ?: this["ignoredmessage"]?.jsonObjectOrNull()
            ?: return null

        return IgnoredMessage(
            code = ignoredMessage.intValue("code") ?: 0,
            message = ignoredMessage.stringValue("#text")
                ?: ignoredMessage.stringValue("text")
                ?: ignoredMessage.stringValue("message")
                ?: "Last.fm ignored the request"
        )
    }

    private fun IgnoredMessage.throwIfIgnored(method: String) {
        if (code == 0) return
        throw LastFmIgnoredException(method, code, message.ifBlank { "Last.fm ignored the request" })
    }

    private fun JsonElement.asIterable(): List<JsonElement> =
        runCatching { jsonArray.toList() }.getOrElse { listOf(this) }

    private fun JsonElement.jsonObjectOrNull(): JsonObject? =
        runCatching { jsonObject }.getOrNull()

    private fun JsonObject.stringValue(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.intValue(key: String): Int? =
        stringValue(key)?.toIntOrNull()

    private data class IgnoredMessage(
        val code: Int,
        val message: String
    )

    const val DEFAULT_SCROBBLE_DELAY_PERCENT = 0.5f
    const val DEFAULT_SCROBBLE_MIN_SONG_DURATION = 30
    const val DEFAULT_SCROBBLE_DELAY_SECONDS = 180
}
