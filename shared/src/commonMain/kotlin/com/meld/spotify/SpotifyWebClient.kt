package com.meld.spotify

import com.metrolist.shared.network.HttpFailureLog
import com.metrolist.shared.network.TextResponse
import com.metrolist.shared.network.bodyAsTextLimited
import com.metrolist.shared.network.sharedHttpTransportHandlesFailures
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpStatement
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.parseServerSetCookieHeader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import meow.nyx.spotlib.SpotifyAuthException
import meow.nyx.spotlib.SpotifySessionExpiredException
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal const val SPOTIFY_PATHFINDER_URL = "https://api-partner.spotify.com/pathfinder/v2/query"
internal const val SPOTIFY_APP_VERSION = "1.2.96.390.gd46a85db"
private const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64; rv:153.0) Gecko/20100101 Firefox/153.0"
private const val WEB_CLIENT_ID = "d8a5ed958d274c2e8ee717e6a4b0971d"
private const val OPEN_HOST = "open.spotify.com"
private const val TOKEN_URL = "https://$OPEN_HOST/api/token"
private const val CLIENT_TOKEN_URL = "https://clienttoken.spotify.com/v1/clienttoken"
private const val DEFAULT_MAX_BYTES = 256 * 1024
private val COOKIE_EXEMPT_HOSTS = setOf("api-partner.spotify.com", "clienttoken.spotify.com")

@Serializable
internal class SpotifyAccessToken(
    val accessToken: String? = null,
    val accessTokenExpirationTimestampMs: Long? = null,
    val isAnonymous: Boolean = true,
)

/**
 * The web-player transport and token exchange, in common code. It behaves as the part of upstream's
 * JVM-only `meow.nyx.spotlib` that Meld used to borrow: same requests, cookies, errors and log lines.
 * Nothing here may log a cookie, a token, the one-time code or a URL query.
 */
internal class SpotifyWebClient(
    base: HttpClient,
    private val clock: () -> Long,
    private val log: (String) -> Unit,
) {
    private class StoredCookie(
        val name: String,
        val value: String,
        val domain: String,
    )

    private val http =
        base.config {
            install(HttpFailureLog) { transportHandledByEngine = sharedHttpTransportHandlesFailures }
            install(HttpTimeout) {
                requestTimeoutMillis = null
                connectTimeoutMillis = 30_000
                socketTimeoutMillis = 60_000
            }
            expectSuccess = false
            followRedirects = false
        }
    private val cookies = MutableStateFlow(emptyList<StoredCookie>())

    fun close() = http.close()

    fun storeCookie(
        name: String,
        value: String?,
        domain: String = OPEN_HOST,
    ) = cookies.update { stored ->
        stored.filterNot { it.name.equals(name, ignoreCase = true) && it.domain.equals(domain, ignoreCase = true) } +
            listOfNotNull(value?.let { StoredCookie(name, it, domain) })
    }

    fun clearCookies() {
        cookies.value = emptyList()
    }

    suspend fun get(
        url: String,
        maxBytes: Int = DEFAULT_MAX_BYTES,
        block: HttpRequestBuilder.() -> Unit = {},
    ): TextResponse = send(http.prepareGet(url) { withCookies(url, block) }, maxBytes)

    suspend fun post(
        url: String,
        maxBytes: Int = DEFAULT_MAX_BYTES,
        block: HttpRequestBuilder.() -> Unit = {},
    ): TextResponse = send(http.preparePost(url) { withCookies(url, block) }, maxBytes)

    suspend fun fetchAccessToken(): SpotifyAccessToken {
        val code = SpotifyTotp.code(clock())
        val url = "$TOKEN_URL?reason=init&productType=web-player&totp=$code&totpServer=$code&totpVer=${SpotifyTotp.VERSION}"
        val response =
            get(url) {
                header(HttpHeaders.Accept, "*/*")
                header(HttpHeaders.Referrer, "https://$OPEN_HOST/")
            }
        val status = response.status.value
        if (status >= 400) {
            val message = "/api/token failed: HTTP $status"
            if (status == 401 || status == 403) throw SpotifySessionExpiredException(message, httpFailureLogged = true)
            throw SpotifyAuthException(message, httpFailureLogged = true)
        }
        val body = response.body ?: throw SpotifyAuthException("token response too large")
        val token = json.decodeFromString(SpotifyAccessToken.serializer(), body)
        if (token.isAnonymous) {
            throw SpotifySessionExpiredException("Spotify reports the session is anonymous. Sign in to Spotify and try again.")
        }
        if (token.accessToken == null) throw SpotifyAuthException("/api/token returned no access token")
        log("Access token exchange succeeded")
        return token
    }

    @OptIn(ExperimentalUuidApi::class)
    suspend fun fetchClientToken(): String {
        val deviceId = cookiesFor(OPEN_HOST).firstOrNull { it.name == "sp_t" }?.value ?: Uuid.random().toString()
        val payload =
            buildJsonObject {
                putJsonObject("client_data") {
                    put("client_version", SPOTIFY_APP_VERSION)
                    put("client_id", WEB_CLIENT_ID)
                    putJsonObject("js_sdk_data") {
                        put("device_brand", "unknown")
                        put("device_model", "unknown")
                        put("os", "android")
                        put("os_version", "unknown")
                        put("device_id", deviceId)
                        put("device_type", "computer")
                    }
                }
            }
        val response =
            post(CLIENT_TOKEN_URL) {
                header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                header(HttpHeaders.Origin, "https://$OPEN_HOST")
                header(HttpHeaders.Referrer, "https://$OPEN_HOST/")
                contentType(ContentType.Application.Json)
                setBody(payload.toString())
            }
        if (response.status.value >= 400) {
            throw SpotifyAuthException("clienttoken failed: HTTP ${response.status.value}", httpFailureLogged = true)
        }
        val body = response.body ?: throw SpotifyAuthException("token response too large")
        val token =
            (json.parseToJsonElement(body) as? JsonObject)?.obj("granted_token")?.str("token")
                ?: throw SpotifyAuthException("clienttoken returned no token")
        log("Client token exchange succeeded")
        return token
    }

    private fun HttpRequestBuilder.withCookies(
        url: String,
        block: HttpRequestBuilder.() -> Unit,
    ) {
        header(HttpHeaders.UserAgent, USER_AGENT)
        val host = Url(url).host
        if (host !in COOKIE_EXEMPT_HOSTS) {
            cookiesFor(host).takeIf { it.isNotEmpty() }?.let { sent ->
                header(HttpHeaders.Cookie, sent.joinToString("; ") { "${it.name}=${it.value}" })
            }
        }
        block()
    }

    private suspend fun send(
        statement: HttpStatement,
        maxBytes: Int,
    ): TextResponse =
        statement.execute { response ->
            val request = response.call.request
            response.headers.getAll(HttpHeaders.SetCookie)?.forEach { capture(request.url.host, it) }
            log("${request.method.value} ${request.url.host}${request.url.encodedPath} -> ${response.status.value}")
            TextResponse(response.status, response.headers, response.bodyAsTextLimited(maxBytes))
        }

    private fun capture(
        host: String,
        header: String,
    ) {
        val cookie = runCatching { parseServerSetCookieHeader(header) }.getOrNull() ?: return
        val expired = cookie.maxAge == 0 || cookie.expires?.let { it.timestamp <= clock() } == true
        storeCookie(cookie.name, cookie.value.takeUnless { expired }, cookie.domain?.takeIf(String::isNotBlank) ?: host)
    }

    // A cookie goes to the host that set it and, like a browser's, to the subdomains of its domain.
    private fun cookiesFor(host: String): List<StoredCookie> =
        cookies.value.filter {
            val domain = it.domain.removePrefix(".")
            host.equals(domain, ignoreCase = true) || host.endsWith(".$domain", ignoreCase = true)
        }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
