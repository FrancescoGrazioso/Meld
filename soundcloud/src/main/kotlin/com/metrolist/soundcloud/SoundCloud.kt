package com.metrolist.soundcloud

import com.metrolist.soundcloud.models.SoundCloudPlaylist
import com.metrolist.soundcloud.models.SoundCloudTrack
import com.metrolist.soundcloud.models.SoundCloudUser
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json

/**
 * SoundCloud API client that uses the internal Web API (api-v2.soundcloud.com)
 * for most operations. Uses Ktor HttpClient similarly to Spotify integration.
 */
object SoundCloud {
    @Volatile
    var accessToken: String? = null // For authenticated user requests

    @Volatile
    var clientId: String? = null // Often needed for public API requests

    private const val BASE_URL = "https://api-v2.soundcloud.com/"

    private fun randomUserAgent(): String {
        val osOptions = arrayOf(
            "Windows NT 10.0; Win64; x64",
            "Macintosh; Intel Mac OS X 10_15_7",
            "X11; Linux x86_64",
        )
        val chromeBase = 140
        val chromeMajor = chromeBase - (0..4).random()
        val chromePatch = (0..499).random()
        val os = osOptions.random()
        return "Mozilla/5.0 ($os) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/$chromeMajor.0.$chromePatch.0 Safari/537.36"
    }

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val restClient by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(json)
            }
            defaultRequest {
                url(BASE_URL)
                header("User-Agent", randomUserAgent())
                header("Origin", "https://soundcloud.com")
                header("Referer", "https://soundcloud.com/")
            }
            expectSuccess = false
        }
    }

    class SoundCloudException(
        val statusCode: Int,
        override val message: String,
        val retryAfterSec: Long = 0,
    ) : Exception(message)

    @Volatile
    var logger: ((level: String, message: String) -> Unit)? = null

    private fun log(level: String, message: String) {
        logger?.invoke(level, message)
    }

    // ── REST core ──────────────────────────────────────────

    private suspend inline fun <reified T> get(
        endpoint: String,
        requiresAuth: Boolean = false,
        failFastOn429: Boolean = false,
        crossinline block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {},
    ): T {
        val token = accessToken
        val cid = clientId

        if (requiresAuth && token == null) {
            throw SoundCloudException(401, "Not authenticated").also {
                log("E", "REST $endpoint — no token")
            }
        }

        if (!requiresAuth && cid == null && token == null) {
             throw SoundCloudException(400, "No client_id or token provided").also {
                log("E", "REST $endpoint — no credentials")
            }
        }

        val maxRetries = if (failFastOn429) 1 else 3
        val maxRetryDelaySec = 3L
        for (attempt in 0 until maxRetries) {
            log(
                "D",
                "REST GET $endpoint" + if (attempt > 0) " [retry $attempt]" else "",
            )
            val response = restClient.get(endpoint) {
                if (token != null) {
                    header("Authorization", "OAuth $token")
                }
                if (cid != null && token == null) {
                    parameter("client_id", cid)
                }
                block()
            }
            log("D", "REST GET $endpoint -> ${response.status.value}")

            if (response.status == HttpStatusCode.Unauthorized) {
                throw SoundCloudException(401, "Token or client_id invalid")
            }
            if (response.status == HttpStatusCode.TooManyRequests) {
                val retryAfter = response.headers["Retry-After"]?.toLongOrNull() ?: (2L * (attempt + 1))
                if (failFastOn429 || retryAfter > maxRetryDelaySec) {
                    log("W", "REST $endpoint -> 429, failing fast (retryAfter=${retryAfter}s)")
                    throw SoundCloudException(429, "Rate limited", retryAfterSec = retryAfter)
                }
                if (attempt < maxRetries - 1) {
                    log("W", "REST $endpoint -> 429, waiting ${retryAfter}s (attempt ${attempt + 1}/$maxRetries)")
                    delay(retryAfter * 1000)
                    continue
                }
                throw SoundCloudException(429, "Rate limited", retryAfterSec = retryAfter)
            }
            if (response.status.value !in 200..299) {
                val bodyText = response.bodyAsText()
                log("E", "REST $endpoint FAILED: ${response.status.value} — ${bodyText.take(200)}")
                throw SoundCloudException(response.status.value, "SoundCloud API error ${response.status.value}: $bodyText")
            }
            return response.body()
        }

        throw SoundCloudException(429, "Rate limited after $maxRetries retries")
    }

    // ── Endpoints ──────────────────────────────────────────

    /**
     * Gets the current user's profile.
     */
    suspend fun me(): Result<SoundCloudUser> = runCatching {
        get<SoundCloudUser>("me", requiresAuth = true)
    }

    /**
     * Gets a single track by its ID.
     */
    suspend fun track(trackId: Long): Result<SoundCloudTrack> = runCatching {
        get<SoundCloudTrack>("tracks/$trackId")
    }

    /**
     * Gets a playlist by its ID.
     */
    suspend fun playlist(playlistId: Long): Result<SoundCloudPlaylist> = runCatching {
        get<SoundCloudPlaylist>("playlists/$playlistId")
    }
}
