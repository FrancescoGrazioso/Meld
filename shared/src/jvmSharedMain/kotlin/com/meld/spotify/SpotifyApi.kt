package com.meld.spotify

import com.metrolist.shared.network.ExpectedHttpFailureStatusCodes
import com.metrolist.shared.network.TextResponse
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.fromHttpToGmtDate
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import meow.nyx.spotlib.ApiHttp
import meow.nyx.spotlib.AuthHttp
import meow.nyx.spotlib.LogLevel
import meow.nyx.spotlib.Queries
import meow.nyx.spotlib.SpotifyConfig
import meow.nyx.spotlib.SpotifyCookieJar
import meow.nyx.spotlib.SpotifyException
import meow.nyx.spotlib.SpotifyHttp
import meow.nyx.spotlib.SpotifyLogger
import meow.nyx.spotlib.SpotifySession
import meow.nyx.spotlib.SpotifySessionExpiredException
import java.util.TimeZone
import kotlin.random.Random

/** Emits every page from offset 0 on, following [SpotifyPage.nextOffset]. */
fun <T> spotifyPages(fetch: suspend (offset: Int) -> SpotifyPage<T>): Flow<SpotifyPage<T>> =
    flow {
        var offset: Int? = 0
        while (offset != null) {
            val page = fetch(offset)
            emit(page)
            offset = page.nextOffset
        }
    }

/**
 * Spotify web-player API for Meld. Every call throws a [SpotifyError]; cancellation is never
 * swallowed. The session is the JSON of spotlib's [SpotifySession], so it shares storage with
 * upstream's playlist import. Nothing here deletes a stored session except [logout].
 */
class SpotifyApi(
    engine: HttpClientEngine,
    private val loadSession: () -> String?,
    private val saveSession: (String) -> Unit,
    loadHashes: () -> String? = { null },
    saveHashes: (String) -> Unit = {},
    hashesUrl: String? = SpotifyHashRegistry.DEFAULT_URL,
    private val clock: () -> Long = System::currentTimeMillis,
    private val logger: (String) -> Unit = {},
) : AutoCloseable {
    private class Tokens(
        val access: String,
        val client: String,
    )

    private class GqlResult(
        val data: JsonObject,
        val errors: List<String>,
    )

    // spotlib logs the token response body at LogLevel.BODIES: never raise this level.
    private val config =
        SpotifyConfig(
            engine = engine,
            logLevel = LogLevel.SUMMARY,
            logger = SpotifyLogger { _, message -> logger(message) },
        )
    private val http = SpotifyHttp.create(config)
    private val jar = SpotifyCookieJar()
    private val api = ApiHttp(http, jar)

    // shortcut: TOTP secret is spotlib's bundled constant, add an override fed by the registry JSON when Spotify rotates it.
    private val auth = AuthHttp(api, config, jar)
    private val hashes =
        SpotifyHashRegistry(
            fetch = hashesUrl?.let { url -> { api.get(url).takeIf { it.status.isSuccess() }?.body } },
            load = loadHashes,
            save = saveHashes,
            clock = clock,
            log = logger,
        )

    private val authMutex = Mutex()
    private var accessUsableUntil = 0L
    private var authFailure: Pair<SpotifyError, Long>? = null

    @Volatile
    private var session: SpotifySession? = null

    @Volatile
    private var cooldownUntil = 0L

    fun isLoggedIn(): Boolean = (session ?: storedSession()) != null

    /** Exchanges the `sp_dc` cookie for tokens; the cookie is stored only once Spotify has accepted it. */
    suspend fun login(spDc: String) {
        require(spDc.isNotBlank()) { "Empty Spotify session cookie" }
        authMutex.withLock {
            attach(SpotifySession(cookies = mapOf("sp_dc" to spDc)))
            authFailure = null
        }
        try {
            tokens()
        } catch (error: SpotifyError.SessionExpired) {
            authMutex.withLock { detach() }
            throw error
        }
    }

    suspend fun logout() =
        authMutex.withLock {
            detach()
            authFailure = null
            saveSession("")
        }

    /** Downloads the remote hash registry (debounced). Worth calling once at startup. */
    suspend fun refreshHashes(): Boolean = hashes.refresh()

    override fun close() = http.close()

    suspend fun profile(): SpotifyUser = query("profileAttributes", buildJsonObject {}, ::parseUser)

    /** Every playlist of the library, folders flattened. */
    suspend fun libraryPlaylists(
        offset: Int = 0,
        limit: Int = 50,
    ): SpotifyPage<SpotifyPlaylist> = library("Playlists", true, null, offset, limit, ::parseLibraryPlaylists)

    /** One level of the library tree: the root when [folderUri] is null, else the children of that folder. */
    suspend fun libraryNode(
        folderUri: String? = null,
        offset: Int = 0,
        limit: Int = 50,
    ): SpotifyPage<SpotifyLibraryItem> = library("Playlists", false, folderUri, offset, limit, ::parseLibraryNode)

    suspend fun libraryArtists(
        offset: Int = 0,
        limit: Int = 50,
    ): SpotifyPage<SpotifyArtist> = library("Artists", false, null, offset, limit, ::parseLibraryArtists)

    suspend fun libraryAlbums(
        offset: Int = 0,
        limit: Int = 50,
    ): SpotifyPage<SpotifyAlbum> = library("Albums", false, null, offset, limit, ::parseLibraryAlbums)

    /** Playlist metadata plus one page of its entries. */
    suspend fun playlist(
        playlistId: String,
        offset: Int = 0,
        limit: Int = 100,
    ): SpotifyPlaylistPage {
        val uri = "spotify:playlist:$playlistId"
        val variables =
            buildJsonObject {
                put("uri", uri)
                put("offset", offset)
                put("limit", limit)
                put("enableWatchFeedEntrypoint", false)
            }
        return query("fetchPlaylist", variables) { parsePlaylistPage(it, uri, offset, limit) }
    }

    suspend fun likedSongs(
        offset: Int = 0,
        limit: Int = 50,
    ): SpotifyPage<SpotifyTrackItem> {
        val variables =
            buildJsonObject {
                put("offset", offset)
                put("limit", limit)
            }
        return query("fetchLibraryTracks", variables) { parseLikedSongs(it, offset, limit) }
    }

    /** The album with all of its tracks, however many pages that takes. */
    suspend fun album(albumId: String): SpotifyAlbum {
        val uri = "spotify:album:$albumId"

        suspend fun fetchPage(offset: Int): Pair<SpotifyAlbum, SpotifyPage<SpotifyTrack>> {
            val variables =
                buildJsonObject {
                    put("uri", uri)
                    put("locale", "")
                    put("offset", offset)
                    put("limit", ALBUM_PAGE_SIZE)
                }
            return query("getAlbum", variables) { parseAlbumPage(it, uri, offset, ALBUM_PAGE_SIZE) }
        }

        val (album, first) = fetchPage(0)
        val tracks = first.items.toMutableList()
        var next = first.nextOffset
        while (next != null) {
            val page = fetchPage(next).second
            tracks += page.items
            next = page.nextOffset
        }
        // Guards against Spotify ignoring `offset`, which would repeat the first page.
        val unique = tracks.distinctBy(SpotifyTrack::uri)
        if (album.totalTracks != null && unique.size < album.totalTracks) {
            logger("getAlbum: read ${unique.size} of ${album.totalTracks} tracks")
        }
        return album.copy(tracks = unique)
    }

    suspend fun artist(artistId: String): SpotifyArtistOverview {
        val uri = "spotify:artist:$artistId"
        val variables =
            buildJsonObject {
                put("uri", uri)
                put("locale", "")
            }
        return query("queryArtistOverview", variables) { parseArtistOverview(it, uri) }
    }

    suspend fun search(
        query: String,
        offset: Int = 0,
        limit: Int = 20,
    ): SpotifySearchResult {
        val variables =
            buildJsonObject {
                put("searchTerm", query)
                put("offset", offset)
                put("limit", limit)
                put("numberOfTopResults", 5)
                put("includeAudiobooks", false)
                put("includeArtistHasConcertsField", false)
                put("includePreReleases", false)
                put("includeLocalConcertsField", false)
                put("includeAuthors", false)
            }
        return query("searchDesktop", variables) { parseSearch(it, offset, limit) }
    }

    suspend fun home(
        timeZone: String = TimeZone.getDefault().id,
        sectionItemsLimit: Int = 10,
    ): SpotifyHomeFeed {
        val variables =
            buildJsonObject {
                put("homeEndUserIntegration", "INTEGRATION_WEB_PLAYER")
                put("timeZone", timeZone)
                put("sp_t", "")
                put("facet", "")
                put("sectionItemsLimit", sectionItemsLimit)
                put("includeEpisodeContentRatingsV2", false)
            }
        val result = gql("home", variables)
        return parseHome(result.data, result.errors) ?: throw unexpectedShape("home", result)
    }

    /** New albums from followed artists. */
    suspend fun newReleases(
        offset: Int = 0,
        limit: Int = 20,
    ): SpotifyPage<SpotifyAlbum> {
        val variables =
            buildJsonObject {
                put("offset", offset)
                put("limit", limit)
                put("onlyUnPlayedItems", false)
                putJsonArray("includedContentTypes") { add("ALBUM") }
            }
        return query("queryWhatsNewFeed", variables) { parseNewReleases(it, offset, limit) }
    }

    suspend fun addToPlaylist(
        playlistId: String,
        trackUris: List<String>,
    ) = mutate("addToPlaylist") {
        put("playlistUri", "spotify:playlist:$playlistId")
        putJsonArray("playlistItemUris") { trackUris.forEach(::add) }
        putJsonObject("newPosition") {
            put("moveType", "BOTTOM_OF_PLAYLIST")
            put("fromUid", JsonNull)
        }
    }

    /** [uids] are the playlist-scoped [SpotifyTrackItem.uid]s, not track URIs. */
    suspend fun removeFromPlaylist(
        playlistId: String,
        uids: List<String>,
    ) = mutate("removeFromPlaylist") {
        put("playlistUri", "spotify:playlist:$playlistId")
        putJsonArray("uids") { uids.forEach(::add) }
    }

    /** Moves [uids] before [beforeUid], or to the end of the playlist when it is null. */
    suspend fun moveInPlaylist(
        playlistId: String,
        uids: List<String>,
        beforeUid: String?,
    ) = mutate("moveItemsInPlaylist") {
        put("playlistUri", "spotify:playlist:$playlistId")
        putJsonArray("uids") { uids.forEach(::add) }
        putJsonObject("newPosition") {
            put("moveType", if (beforeUid != null) "BEFORE_UID" else "BOTTOM_OF_PLAYLIST")
            put("fromUid", beforeUid)
        }
    }

    suspend fun editPlaylist(
        playlistId: String,
        name: String? = null,
        description: String? = null,
    ) = mutate("editPlaylistAttributes") {
        put("playlistUri", "spotify:playlist:$playlistId")
        if (name != null) put("newName", name)
        if (description != null) put("newDescription", description)
    }

    suspend fun addToLibrary(uris: List<String>) = mutate("addToLibrary") { putJsonArray("libraryItemUris") { uris.forEach(::add) } }

    suspend fun removeFromLibrary(uris: List<String>) =
        mutate("removeFromLibrary") { putJsonArray("libraryItemUris") { uris.forEach(::add) } }

    /** Public Web API; rate limited far more aggressively than GraphQL, so a 429 fails at once. */
    suspend fun topTracks(
        timeRange: String = "medium_term",
        offset: Int = 0,
        limit: Int = 50,
    ): SpotifyPage<SpotifyTrack> = parseRestPage(rest("me/top/tracks", timeRange, offset, limit), offset, ::parseRestTrack)

    suspend fun topArtists(
        timeRange: String = "medium_term",
        offset: Int = 0,
        limit: Int = 50,
    ): SpotifyPage<SpotifyArtist> = parseRestPage(rest("me/top/artists", timeRange, offset, limit), offset, ::parseRestArtist)

    private suspend fun <T : Any> library(
        filter: String,
        flatten: Boolean,
        folderUri: String?,
        offset: Int,
        limit: Int,
        parse: (JsonObject, Int, Int) -> SpotifyPage<T>?,
    ): SpotifyPage<T> {
        val variables =
            buildJsonObject {
                putJsonArray("filters") { add(filter) }
                put("order", JsonNull)
                put("textFilter", "")
                putJsonArray("features") { listOf("LIKED_SONGS", "YOUR_EPISODES_V2", "PRERELEASES", "EVENTS").forEach(::add) }
                put("limit", limit)
                put("offset", offset)
                put("flatten", flatten)
                putJsonArray("expandedFolders") {}
                put("folderUri", folderUri)
                put("includeFoldersWhenFlattening", !flatten)
            }
        return query("libraryV3", variables) { data ->
            parse(data, offset, limit)?.also { page ->
                val raw =
                    data
                        .obj("me")
                        ?.obj("libraryV3")
                        ?.objs("items")
                        .orEmpty()
                if (page.items.size < raw.size) {
                    val typenames = raw.groupingBy { it.obj("item")?.str("__typename") }.eachCount()
                    logger("libraryV3[$filter]: kept ${page.items.size} of ${raw.size} items, typenames=$typenames")
                }
            }
        }
    }

    private suspend fun mutate(
        operation: String,
        variables: JsonObjectBuilder.() -> Unit,
    ) {
        gql(operation, buildJsonObject(variables), mutation = true)
    }

    private suspend fun <T : Any> query(
        operation: String,
        variables: JsonObject,
        parse: (JsonObject) -> T?,
    ): T {
        val result = gql(operation, variables)
        return parse(result.data) ?: throw unexpectedShape(operation, result)
    }

    private fun unexpectedShape(
        operation: String,
        result: GqlResult,
    ) = SpotifyError.Changed("$operation: unexpected response shape ${result.errors.joinToString("; ")}".trim())

    private suspend fun gql(
        operation: String,
        variables: JsonObject,
        mutation: Boolean = false,
    ): GqlResult {
        val tried = HashSet<String>()
        var refreshed = false
        while (true) {
            val hash = hashes.candidates(operation).firstOrNull { it !in tried }
            if (hash == null) {
                if (refreshed) throw SpotifyError.Changed("$operation: Spotify rejected every known query hash")
                refreshed = true
                hashes.refresh()
                continue
            }
            tried += hash
            val body =
                buildJsonObject {
                    put("variables", variables)
                    put("operationName", operation)
                    putJsonObject("extensions") {
                        putJsonObject("persistedQuery") {
                            put("version", 1)
                            put("sha256Hash", hash)
                        }
                    }
                }.toString()
            // A mutation is not repeated after a network or server error: it may already have been applied.
            val response =
                execute(retryTransient = !mutation, maxRateLimitWaitMs = MAX_RATE_LIMIT_WAIT_MS) { tokens ->
                    api.post(Queries.PATHFINDER_URL, MAX_RESPONSE_BYTES) {
                        attributes.put(ExpectedHttpFailureStatusCodes, setOf(401, 412, 429))
                        webPlayerHeaders(tokens)
                        header("spotify-app-version", config.appVersion)
                        header("client-token", tokens.client)
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                }
            val result = readGql(operation, response, mutation)
            if (result != null) {
                hashes.markAccepted(operation, hash)
                return result
            }
            logger("$operation: query hash rejected, trying the next known one")
        }
    }

    /** Returns null when Spotify does not know the persisted query hash. */
    private fun readGql(
        operation: String,
        response: TextResponse,
        mutation: Boolean,
    ): GqlResult? {
        if (response.status.value == 412) return null
        val root = readJson(operation, response)
        val errors = root.objs("errors").map { it.str("message") ?: "Unknown GraphQL error" }
        if (errors.any { it.contains("PersistedQueryNotFound", ignoreCase = true) }) return null
        val data = root.obj("data") ?: JsonObject(emptyMap())
        if (errors.isNotEmpty()) {
            if (mutation || data.values.all { it is JsonNull }) throw SpotifyError.Changed("$operation: ${errors.joinToString("; ")}")
            logger("$operation: ${errors.size} partial GraphQL errors, first: ${errors.first()}")
        }
        return GqlResult(data, errors)
    }

    private suspend fun rest(
        path: String,
        timeRange: String,
        offset: Int,
        limit: Int,
    ): JsonObject {
        val response =
            execute(retryTransient = true, maxRateLimitWaitMs = 0) { tokens ->
                api.get("https://api.spotify.com/v1/$path") {
                    attributes.put(ExpectedHttpFailureStatusCodes, setOf(401, 429))
                    webPlayerHeaders(tokens)
                    parameter("time_range", timeRange)
                    parameter("limit", limit)
                    parameter("offset", offset)
                }
            }
        return readJson(path, response)
    }

    private fun HttpRequestBuilder.webPlayerHeaders(tokens: Tokens) {
        header(HttpHeaders.Accept, "application/json")
        header(HttpHeaders.Origin, "https://open.spotify.com")
        header(HttpHeaders.Referrer, "https://open.spotify.com/")
        header("app-platform", "WebPlayer")
        header(HttpHeaders.Authorization, "Bearer ${tokens.access}")
    }

    private fun readJson(
        operation: String,
        response: TextResponse,
    ): JsonObject {
        val status = response.status.value
        if (status == 403) throw SpotifyError.Forbidden(operation)
        if (status !in 200..299) throw SpotifyError.Changed("$operation: HTTP $status")
        return response.body?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            ?: throw SpotifyError.Changed("$operation: unreadable response")
    }

    /**
     * Sends one request with valid tokens. Handles 401 (one forced refresh), 429 (one wait of at
     * most [maxRateLimitWaitMs]) and, when [retryTransient], one retry of a network or 5xx failure.
     */
    private suspend fun execute(
        retryTransient: Boolean,
        maxRateLimitWaitMs: Long,
        call: suspend (Tokens) -> TextResponse,
    ): TextResponse {
        var refreshed = false
        var rateLimited = false
        var retried = !retryTransient
        while (true) {
            val pending = cooldownUntil - clock()
            if (pending > maxRateLimitWaitMs) throw SpotifyError.RateLimited((pending + 999) / 1000)
            if (pending > 0) delay(pending + Random.nextLong(RATE_LIMIT_JITTER_MS))
            val tokens = tokens()
            val response =
                try {
                    call(tokens)
                } catch (error: IOException) {
                    if (retried) throw SpotifyError.Offline(error)
                    null
                }
            val status = response?.status?.value
            when {
                response == null || status in 500..599 -> {
                    if (retried) throw SpotifyError.Unavailable(status ?: 0)
                    retried = true
                    delay(TRANSIENT_RETRY_DELAY_MS)
                }

                status == 401 -> {
                    if (refreshed) throw SpotifyError.SessionExpired()
                    refreshed = true
                    invalidate(tokens)
                }

                status == 429 -> {
                    val wait = retryAfterMs(response.headers)
                    // Capped so a bogus Retry-After cannot lock the integration out until restart.
                    cooldownUntil = clock() + wait.coerceAtMost(MAX_COOLDOWN_MS)
                    if (rateLimited || wait > maxRateLimitWaitMs) throw SpotifyError.RateLimited((wait + 999) / 1000)
                    rateLimited = true
                }

                else -> {
                    return response
                }
            }
        }
    }

    private fun retryAfterMs(headers: Headers): Long {
        val value = headers[HttpHeaders.RetryAfter] ?: return DEFAULT_RETRY_AFTER_MS
        val millis = value.toLongOrNull()?.times(1000) ?: runCatching { value.fromHttpToGmtDate().timestamp - clock() }.getOrNull()
        return (millis ?: DEFAULT_RETRY_AFTER_MS).coerceAtLeast(0)
    }

    /** Valid tokens, refreshed by at most one caller at a time. */
    private suspend fun tokens(): Tokens =
        authMutex.withLock {
            authFailure?.let { (error, until) -> if (clock() < until) throw error }
            var current = session ?: storedSession()?.also(::attach) ?: throw SpotifyError.NotLoggedIn()
            val now = clock()
            try {
                var access = current.accessToken?.takeIf { now < accessUsableUntil }
                if (access == null) {
                    val token = auth.fetchAccessToken()
                    access = token.accessToken ?: throw SpotifyError.Changed("Spotify returned no access token")
                    current = current.copy(accessToken = access, accessTokenExpiresAtMs = token.accessTokenExpirationTimestampMs)
                    // The floor keeps a device clock set ahead from refreshing on every request; a
                    // token used past its real expiry is caught by the 401 handling instead.
                    accessUsableUntil = usableUntil(token.accessTokenExpirationTimestampMs).coerceAtLeast(now + MIN_TOKEN_TRUST_MS)
                }
                var client = current.clientToken?.takeIf { now - (current.clientTokenFetchedAtMs ?: 0) < CLIENT_TOKEN_TTL_MS }
                if (client == null) {
                    client = auth.fetchClientToken()
                    current = current.copy(clientToken = client, clientTokenFetchedAtMs = now)
                }
                if (current != session) {
                    session = current
                    saveSession(json.encodeToString(SpotifySession.serializer(), current))
                }
                Tokens(access, client)
            } catch (error: SpotifySessionExpiredException) {
                throw authFailed(SpotifyError.SessionExpired(error), AUTH_FAILURE_TTL_MS)
            } catch (error: SpotifyException) {
                throw authFailed(SpotifyError.Changed("Spotify token exchange failed", error), AUTH_FAILURE_TTL_MS)
            } catch (error: IOException) {
                throw authFailed(SpotifyError.Offline(error), OFFLINE_TTL_MS)
            }
        }

    // Remembered briefly so callers queued on the mutex fail at once instead of each repeating the exchange.
    private fun authFailed(
        error: SpotifyError,
        ttlMs: Long,
    ): SpotifyError = error.also { authFailure = it to clock() + ttlMs }

    private suspend fun invalidate(stale: Tokens) =
        authMutex.withLock {
            val current = session
            if (current?.accessToken == stale.access) session = current.copy(accessToken = null, clientToken = null)
        }

    private fun usableUntil(expiresAtMs: Long?): Long = (expiresAtMs ?: 0) - EXPIRY_MARGIN_MS

    private fun attach(attached: SpotifySession) {
        detach()
        attached.cookies.forEach { (name, value) -> jar.store(OPEN_URL, name, value) }
        accessUsableUntil = usableUntil(attached.accessTokenExpiresAtMs)
        session = attached
    }

    private fun detach() {
        session = null
        jar.clear()
    }

    private fun storedSession(): SpotifySession? =
        loadSession()
            ?.takeIf(String::isNotBlank)
            ?.let { runCatching { json.decodeFromString(SpotifySession.serializer(), it) }.getOrNull() }
            ?.takeIf(SpotifySession::hasLoginCookie)

    private companion object {
        val OPEN_URL = Url("https://open.spotify.com/")
        val json = Json { ignoreUnknownKeys = true }
        const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        const val ALBUM_PAGE_SIZE = 50
        const val EXPIRY_MARGIN_MS = 60_000L
        const val MIN_TOKEN_TRUST_MS = 60_000L
        const val CLIENT_TOKEN_TTL_MS = 13L * 24 * 3600_000
        const val AUTH_FAILURE_TTL_MS = 30_000L
        const val OFFLINE_TTL_MS = 5_000L
        const val MAX_RATE_LIMIT_WAIT_MS = 5_000L
        const val MAX_COOLDOWN_MS = 60_000L
        const val DEFAULT_RETRY_AFTER_MS = 2_000L
        const val RATE_LIMIT_JITTER_MS = 250L
        const val TRANSIENT_RETRY_DELAY_MS = 500L
    }
}
