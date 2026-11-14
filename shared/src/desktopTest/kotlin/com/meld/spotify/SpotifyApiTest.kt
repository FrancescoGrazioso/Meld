package com.meld.spotify

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import meow.nyx.spotlib.SpotifySession
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val COOKIE = "sp-dc-secret-value"
private const val FAR_FUTURE = 4_000_000_000_000L
private const val AVATAR = """{"sources":[{"url":"https://i.scdn.co/image/u1"}]}"""
private const val PROFILE = """{"data":{"me":{"profile":{"uri":"spotify:user:u1","name":"Tester","avatar":$AVATAR}}}}"""

private class FakeSpotify(
    private val clock: () -> Long = { 0L },
) {
    data class Query(
        val operation: String,
        val hash: String,
        val variables: JsonObject,
        val bearer: String?,
        val clientToken: String?,
    )

    val tokenExchanges = AtomicInteger()
    val clientTokenExchanges = AtomicInteger()
    val queries = CopyOnWriteArrayList<Query>()
    val logs = CopyOnWriteArrayList<String>()

    @Volatile
    var session: String? = null

    @Volatile
    var hashes: String? = null

    @Volatile
    var remoteHashes: String? = null

    @Volatile
    var tokenExpiresAt: () -> Long = { FAR_FUTURE }

    @Volatile
    var onToken: suspend MockRequestHandleScope.(count: Int) -> HttpResponseData = { count ->
        respond("""{"accessToken":"access-$count","accessTokenExpirationTimestampMs":${tokenExpiresAt()},"isAnonymous":false}""")
    }

    @Volatile
    var onQuery: suspend MockRequestHandleScope.(Query) -> HttpResponseData = { respond(PROFILE) }

    @Volatile
    var onRest: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { error("Unexpected Web API call") }

    val engine =
        MockEngine { request ->
            when (request.url.host) {
                "open.spotify.com" -> {
                    assertEquals("sp_dc=$COOKIE", request.headers[HttpHeaders.Cookie])
                    onToken(tokenExchanges.incrementAndGet())
                }

                "clienttoken.spotify.com" -> {
                    respond("""{"granted_token":{"token":"client-${clientTokenExchanges.incrementAndGet()}"}}""")
                }

                "api-partner.spotify.com" -> {
                    val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    val query =
                        Query(
                            operation = body.str("operationName").orEmpty(),
                            hash =
                                body
                                    .obj("extensions")
                                    ?.obj("persistedQuery")
                                    ?.str("sha256Hash")
                                    .orEmpty(),
                            variables = body.getValue("variables").jsonObject,
                            bearer = request.headers[HttpHeaders.Authorization],
                            clientToken = request.headers["client-token"],
                        )
                    queries += query
                    onQuery(query)
                }

                "api.spotify.com" -> {
                    onRest(request)
                }

                "francescograzioso.github.io" -> {
                    remoteHashes?.let { respond(it) } ?: respond("", HttpStatusCode.NotFound)
                }

                else -> {
                    error("Unexpected host ${request.url.host}")
                }
            }
        }

    fun signedIn(
        accessToken: String? = null,
        expiresAt: Long = FAR_FUTURE,
    ) = apply {
        session =
            Json.encodeToString(
                SpotifySession.serializer(),
                SpotifySession(
                    mapOf("sp_dc" to COOKIE),
                    accessToken,
                    expiresAt.takeIf { accessToken != null },
                    "client-0".takeIf {
                        accessToken !=
                            null
                    },
                    clock(),
                ),
            )
    }

    fun api() = SpotifyApi(engine, { session }, { session = it }, { hashes }, { hashes = it }, clock = clock, logger = { logs += it })
}

@OptIn(ExperimentalCoroutinesApi::class)
class SpotifyApiTest {
    @Test
    fun loginStoresTheSessionOnlyAfterSpotifyAcceptsTheCookie() =
        runBlocking<Unit> {
            val spotify = FakeSpotify()
            val api = spotify.api()
            assertFalse(api.isLoggedIn())
            assertFailsWith<SpotifyError.NotLoggedIn> { api.profile() }

            spotify.onToken = { respond("""{"accessToken":"anonymous","isAnonymous":true}""") }
            assertFailsWith<SpotifyError.SessionExpired> { api.login(COOKIE) }
            assertNull(spotify.session)
            assertFalse(api.isLoggedIn())

            spotify.onToken =
                { respond("""{"accessToken":"access-ok","accessTokenExpirationTimestampMs":$FAR_FUTURE,"isAnonymous":false}""") }
            api.login(COOKIE)
            assertTrue(api.isLoggedIn())
            val user = api.profile()
            assertEquals(SpotifyUser("u1", "spotify:user:u1", "Tester", listOf(SpotifyImage("https://i.scdn.co/image/u1"))), user)
            assertEquals("Bearer access-ok", spotify.queries.single().bearer)
            assertEquals("client-1", spotify.queries.single().clientToken)

            // A new instance picks the persisted session up without another token exchange.
            val exchanges = spotify.tokenExchanges.get()
            spotify.api().profile()
            assertEquals(exchanges, spotify.tokenExchanges.get())

            api.logout()
            assertEquals("", spotify.session)
            assertFailsWith<SpotifyError.NotLoggedIn> { api.profile() }
        }

    @Test
    fun rejectedTokenIsRefreshedOnceAndTheRequestRepeated() =
        runBlocking<Unit> {
            val spotify = FakeSpotify().signedIn(accessToken = "stale")
            spotify.onQuery =
                { query -> if (query.bearer == "Bearer stale") respond("", HttpStatusCode.Unauthorized) else respond(PROFILE) }

            assertEquals("Tester", spotify.api().profile().name)
            assertEquals(listOf("Bearer stale", "Bearer access-1"), spotify.queries.map { it.bearer })
            assertEquals(1, spotify.tokenExchanges.get())
            assertContains(spotify.session.orEmpty(), "access-1")
        }

    @Test
    fun secondRejectionAfterARefreshIsASessionExpiry() =
        runBlocking<Unit> {
            val spotify = FakeSpotify().signedIn(accessToken = "stale")
            spotify.onQuery = { respond("", HttpStatusCode.Unauthorized) }

            assertFailsWith<SpotifyError.SessionExpired> { spotify.api().profile() }
            assertEquals(2, spotify.queries.size)
            assertEquals(1, spotify.tokenExchanges.get())
            // The stored cookie survives: only an explicit logout removes it.
            assertContains(spotify.session.orEmpty(), COOKIE)
        }

    @Test
    fun concurrentCallsShareOneTokenExchange() =
        runBlocking<Unit>(Dispatchers.Default) {
            val spotify = FakeSpotify().signedIn()
            spotify.onToken = { count ->
                delay(50)
                respond("""{"accessToken":"access-$count","accessTokenExpirationTimestampMs":$FAR_FUTURE,"isAnonymous":false}""")
            }
            val api = spotify.api()

            List(20) { async { api.profile() } }.awaitAll()
            assertEquals(1, spotify.tokenExchanges.get())
            assertEquals(1, spotify.clientTokenExchanges.get())
            assertEquals(20, spotify.queries.size)
        }

    @Test
    fun concurrentRejectionsTriggerASingleRefresh() =
        runBlocking<Unit>(Dispatchers.Default) {
            val spotify = FakeSpotify().signedIn(accessToken = "stale")
            spotify.onQuery = { query ->
                delay(20)
                if (query.bearer == "Bearer stale") respond("", HttpStatusCode.Unauthorized) else respond(PROFILE)
            }
            val api = spotify.api()

            List(10) { async { api.profile() } }.awaitAll()
            assertEquals(1, spotify.tokenExchanges.get())
            assertEquals(10, spotify.queries.count { it.bearer == "Bearer access-1" })
        }

    @Test
    fun tokenInsideTheExpiryMarginIsRefreshedBeforeUse() =
        runTest {
            val spotify = FakeSpotify { currentTime }.signedIn(accessToken = "expiring", expiresAt = 30_000)
            spotify.api().profile()
            assertEquals(listOf("Bearer access-1"), spotify.queries.map { it.bearer })
        }

    @Test
    fun deviceClockAheadOfSpotifyDoesNotRefreshOnEveryRequest() =
        runTest {
            val spotify = FakeSpotify { currentTime + 10_000_000 }.signedIn()
            spotify.tokenExpiresAt = { currentTime + 3_600_000 }
            val api = spotify.api()

            repeat(5) { api.profile() }
            assertEquals(1, spotify.tokenExchanges.get())
        }

    @Test
    fun failedTokenExchangeIsNotRepeatedByEveryQueuedCaller() =
        runTest {
            val spotify = FakeSpotify { currentTime }.signedIn()
            spotify.onToken = { respond("", HttpStatusCode.BadRequest) }
            val api = spotify.api()

            repeat(3) { assertFailsWith<SpotifyError.Changed> { api.profile() } }
            assertEquals(1, spotify.tokenExchanges.get())
            assertContains(spotify.session.orEmpty(), COOKIE)

            delay(30_001)
            spotify.onToken =
                { respond("""{"accessToken":"access-ok","accessTokenExpirationTimestampMs":$FAR_FUTURE,"isAnonymous":false}""") }
            api.profile()
        }

    @Test
    fun shortRateLimitIsWaitedOutOnce() =
        runTest {
            val spotify = FakeSpotify { currentTime }.signedIn(accessToken = "access")
            spotify.onQuery = {
                if (spotify.queries.size ==
                    1
                ) {
                    respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "2"))
                } else {
                    respond(PROFILE)
                }
            }

            spotify.api().profile()
            assertEquals(2, spotify.queries.size)
            assertTrue(currentTime in 2_000..2_250, "waited ${currentTime}ms")
        }

    @Test
    fun retryAfterGivenAsAnHttpDateIsUnderstood() =
        runTest {
            val spotify = FakeSpotify { currentTime }.signedIn(accessToken = "access")
            spotify.onQuery = {
                if (spotify.queries.size == 1) {
                    respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "Thu, 01 Jan 1970 00:00:03 GMT"))
                } else {
                    respond(PROFILE)
                }
            }

            spotify.api().profile()
            assertTrue(currentTime in 3_000..3_250, "waited ${currentTime}ms")
        }

    @Test
    fun longRateLimitFailsAtOnceAndCoolsDownOtherCalls() =
        runTest {
            val spotify = FakeSpotify { currentTime }.signedIn(accessToken = "access")
            spotify.onQuery = { respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "3600")) }
            val api = spotify.api()

            assertEquals(3600, assertFailsWith<SpotifyError.RateLimited> { api.profile() }.retryAfterSeconds)
            assertEquals(0, currentTime)
            // The shared cooldown is capped, and stops the next call before it reaches the network.
            assertEquals(60, assertFailsWith<SpotifyError.RateLimited> { api.likedSongs() }.retryAfterSeconds)
            assertEquals(1, spotify.queries.size)
        }

    @Test
    fun repeatedRateLimitGivesUpAfterOneWait() =
        runTest {
            val spotify = FakeSpotify { currentTime }.signedIn(accessToken = "access")
            spotify.onQuery = { respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "1")) }

            assertFailsWith<SpotifyError.RateLimited> { spotify.api().profile() }
            assertEquals(2, spotify.queries.size)
            assertTrue(currentTime < 1_500)
        }

    @Test
    fun rejectedHashFallsBackToThePreviousOneWhichIsThenRemembered() =
        runBlocking<Unit> {
            val spotify = FakeSpotify().signedIn(accessToken = "access")
            spotify.onQuery =
                { query -> if (query.hash == PROFILE_HASH) respond("", HttpStatusCode.PreconditionFailed) else respond(PROFILE) }
            val api = spotify.api()

            api.profile()
            api.profile()
            assertEquals(listOf(PROFILE_HASH, PROFILE_PREVIOUS_HASH, PROFILE_PREVIOUS_HASH), spotify.queries.map { it.hash })
        }

    @Test
    fun whenEveryKnownHashIsRejectedTheRegistryIsDownloadedAndTheCallRepeated() =
        runBlocking<Unit> {
            val spotify = FakeSpotify().signedIn(accessToken = "access")
            spotify.remoteHashes = registryJson(hashOf('c'), PROFILE_HASH)
            spotify.onQuery = { query ->
                if (query.hash == hashOf('c')) respond(PROFILE) else respond("""{"errors":[{"message":"PersistedQueryNotFound"}]}""")
            }

            assertEquals("Tester", spotify.api().profile().name)
            assertEquals(listOf(PROFILE_HASH, PROFILE_PREVIOUS_HASH, hashOf('c')), spotify.queries.map { it.hash })
            assertEquals(spotify.remoteHashes, spotify.hashes)

            // After a restart the cached registry comes first, and the bundled hashes are still reachable behind it.
            spotify.queries.clear()
            spotify.onQuery =
                { query -> if (query.hash == PROFILE_PREVIOUS_HASH) respond(PROFILE) else respond("", HttpStatusCode.PreconditionFailed) }
            spotify.api().profile()
            assertEquals(listOf(hashOf('c'), PROFILE_HASH, PROFILE_PREVIOUS_HASH), spotify.queries.map { it.hash })
        }

    @Test
    fun noAcceptedHashAnywhereIsReportedAsAChange() =
        runBlocking<Unit> {
            val spotify = FakeSpotify().signedIn(accessToken = "access")
            spotify.onQuery = { respond("", HttpStatusCode.PreconditionFailed) }

            assertFailsWith<SpotifyError.Changed> { spotify.api().profile() }
            assertEquals(2, spotify.queries.size)
        }

    @Test
    fun pagingFollowsSpotifysCountWhenEntriesAreDropped() =
        runBlocking<Unit> {
            val spotify = FakeSpotify().signedIn(accessToken = "access")
            spotify.onQuery = { query ->
                val items =
                    when (query.variables.int("offset")) {
                        0 -> {
                            """{"uid":"u1","itemV2":{"data":{"__typename":"Track","uri":"spotify:track:t1","name":"One"}}},""" +
                                """{"uid":"u2","itemV2":{"data":{"__typename":"Episode","uri":"spotify:episode:e1","name":"Episode"}}},null"""
                        }

                        3 -> {
                            """null,{"uid":"u5","itemV2":{"data":{"__typename":"Track","uri":"spotify:track:t5","name":"Five"}}}"""
                        }

                        else -> {
                            error("Unexpected offset")
                        }
                    }
                respond("""{"data":{"playlistV2":{"name":"Road trip","content":{"totalCount":5,"items":[$items]}}}}""")
            }
            val api = spotify.api()

            val pages = spotifyPages { offset -> api.playlist("p1", offset, limit = 3).tracks }.toList()
            assertEquals(listOf(0, 3), spotify.queries.map { it.variables.int("offset") })
            assertEquals(listOf("spotify:playlist:p1"), spotify.queries.map { it.variables.str("uri") }.distinct())
            assertEquals(listOf(3, null), pages.map { it.nextOffset })
            assertEquals(listOf("t1", null, "t5"), pages.flatMap { it.items }.map { it.track?.id })
        }

    @Test
    fun albumLoadsEveryTrackAcrossPages() =
        runBlocking<Unit> {
            val spotify = FakeSpotify().signedIn(accessToken = "access")
            spotify.onQuery = { query ->
                val offset = query.variables.int("offset") ?: 0
                val limit = query.variables.int("limit") ?: 0
                val items =
                    (offset until minOf(offset + limit, 120)).joinToString(",") {
                        """{"track":{"uri":"spotify:track:t$it","name":"Track $it","duration":{"totalMilliseconds":1000}}}"""
                    }
                respond("""{"data":{"albumUnion":{"name":"Long album","type":"ALBUM","tracksV2":{"totalCount":120,"items":[$items]}}}}""")
            }

            val album = spotify.api().album("al1")
            assertEquals(listOf(0, 50, 100), spotify.queries.map { it.variables.int("offset") })
            assertEquals(120, album.totalTracks)
            assertEquals((0 until 120).map { "t$it" }, album.tracks.map { it.id })
            assertEquals(
                "Long album",
                album.tracks
                    .first()
                    .album
                    ?.name,
            )
        }

    @Test
    fun partialGraphQlErrorsKeepTheUsableData() =
        runBlocking<Unit> {
            val spotify = FakeSpotify().signedIn(accessToken = "access")
            val home = spotifyFixture("home.json").trimEnd().removeSuffix("}")
            spotify.onQuery = { respond("""$home,"errors":[{"message":"section 0JQ5 timed out"}]}""") }

            val feed = spotify.api().home(timeZone = "Europe/Rome")
            assertEquals(4, feed.sections.size)
            assertEquals(listOf("section 0JQ5 timed out"), feed.partialErrors)
            assertEquals(
                "Europe/Rome",
                spotify.queries
                    .single()
                    .variables
                    .str("timeZone"),
            )
        }

    @Test
    fun errorsWithoutDataAndFailedMutationsAreThrown() =
        runBlocking<Unit> {
            val spotify = FakeSpotify().signedIn(accessToken = "access")
            spotify.onQuery = { respond("""{"data":{"playlistV2":null},"errors":[{"message":"Playlist not found"}]}""") }
            assertContains(assertFailsWith<SpotifyError.Changed> { spotify.api().playlist("p1") }.message.orEmpty(), "Playlist not found")

            spotify.onQuery = { respond("""{"data":{"addItemsToPlaylist":{"ok":true}},"errors":[{"message":"Forbidden"}]}""") }
            assertFailsWith<SpotifyError.Changed> { spotify.api().addToPlaylist("p1", listOf("spotify:track:t1")) }

            spotify.onQuery = { respond("", HttpStatusCode.Forbidden) }
            assertFailsWith<SpotifyError.Forbidden> { spotify.api().profile() }
        }

    @Test
    fun mutationsSendTheVariablesSpotifyExpectsAndAreNeverRepeatedAfterAServerError() =
        runTest {
            val spotify = FakeSpotify { currentTime }.signedIn(accessToken = "access")
            spotify.onQuery = { respond("""{"data":{}}""") }
            val api = spotify.api()

            api.addToPlaylist("p1", listOf("spotify:track:t1"))
            api.removeFromPlaylist("p1", listOf("u1"))
            api.moveInPlaylist("p1", listOf("u1"), beforeUid = "u2")
            api.moveInPlaylist("p1", listOf("u1"), beforeUid = null)
            api.editPlaylist("p1", name = "Renamed")
            api.addToLibrary(listOf("spotify:track:t1"))
            api.removeFromLibrary(listOf("spotify:track:t1"))
            assertEquals(
                listOf(
                    """addToPlaylist {"playlistUri":"spotify:playlist:p1","playlistItemUris":["spotify:track:t1"],"newPosition":{"moveType":"BOTTOM_OF_PLAYLIST","fromUid":null}}""",
                    """removeFromPlaylist {"playlistUri":"spotify:playlist:p1","uids":["u1"]}""",
                    """moveItemsInPlaylist {"playlistUri":"spotify:playlist:p1","uids":["u1"],"newPosition":{"moveType":"BEFORE_UID","fromUid":"u2"}}""",
                    """moveItemsInPlaylist {"playlistUri":"spotify:playlist:p1","uids":["u1"],"newPosition":{"moveType":"BOTTOM_OF_PLAYLIST","fromUid":null}}""",
                    """editPlaylistAttributes {"playlistUri":"spotify:playlist:p1","newName":"Renamed"}""",
                    """addToLibrary {"libraryItemUris":["spotify:track:t1"]}""",
                    """removeFromLibrary {"libraryItemUris":["spotify:track:t1"]}""",
                ),
                spotify.queries.map { "${it.operation} ${it.variables}" },
            )

            spotify.queries.clear()
            spotify.onQuery = { respond("", HttpStatusCode.ServiceUnavailable) }
            assertEquals(503, assertFailsWith<SpotifyError.Unavailable> { api.addToPlaylist("p1", listOf("spotify:track:t1")) }.status)
            assertEquals(1, spotify.queries.size)

            // A read is repeated once.
            spotify.queries.clear()
            spotify.onQuery = { if (spotify.queries.size == 1) respond("", HttpStatusCode.ServiceUnavailable) else respond(PROFILE) }
            api.profile()
            assertEquals(2, spotify.queries.size)
        }

    @Test
    fun networkFailureIsOffline() =
        runTest {
            val spotify = FakeSpotify { currentTime }.signedIn(accessToken = "access")
            spotify.onQuery = { throw IOException("connection reset") }
            assertIs<IOException>(assertFailsWith<SpotifyError.Offline> { spotify.api().profile() }.cause)
            assertEquals(2, spotify.queries.size)

            val signedOut = FakeSpotify { currentTime }.signedIn()
            signedOut.onToken = { throw IOException("no route to host") }
            assertFailsWith<SpotifyError.Offline> { signedOut.api().profile() }
        }

    @Test
    fun cancellationIsNotTurnedIntoAnError() =
        runBlocking<Unit> {
            val spotify = FakeSpotify().signedIn(accessToken = "access")
            spotify.onQuery = { awaitCancellation() }
            assertFailsWith<CancellationException> { withTimeout(100) { spotify.api().profile() } }
        }

    @Test
    fun webApiFailsFastWhenRateLimited() =
        runTest {
            val spotify = FakeSpotify { currentTime }.signedIn(accessToken = "access")
            val requests = CopyOnWriteArrayList<HttpRequestData>()
            spotify.onRest = { request ->
                requests += request
                respond(
                    """{"total":1,"next":null,"items":[{"id":"a1","name":"Artist A","images":[{"url":"https://i.scdn.co/image/a1"}]}]}""",
                )
            }
            val api = spotify.api()

            val artists = api.topArtists(timeRange = "short_term", offset = 0, limit = 10)
            assertEquals(listOf("Artist A"), artists.items.map { it.name })
            assertNull(artists.nextOffset)
            val request = requests.single()
            assertEquals("/v1/me/top/artists", request.url.encodedPath)
            assertEquals("short_term", request.url.parameters["time_range"])
            assertEquals("Bearer access", request.headers[HttpHeaders.Authorization])
            assertNull(request.headers[HttpHeaders.Cookie])

            spotify.onRest = { respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "1")) }
            assertFailsWith<SpotifyError.RateLimited> { api.topTracks() }
            assertEquals(0, currentTime)
        }

    @Test
    fun logsNeverContainTheCookieOrAToken() =
        runBlocking<Unit> {
            val spotify = FakeSpotify()
            val api = spotify.api()
            api.login(COOKIE)
            spotify.onQuery = { query ->
                when {
                    query.bearer == "Bearer access-1" -> respond("", HttpStatusCode.Unauthorized)
                    query.hash == PROFILE_HASH -> respond("", HttpStatusCode.PreconditionFailed)
                    else -> respond("""{"data":{"me":null},"errors":[{"message":"boom"}]}""")
                }
            }
            assertFailsWith<SpotifyError.Changed> { api.profile() }

            assertTrue(spotify.logs.isNotEmpty())
            val secrets = listOf(COOKIE, "access-1", "access-2", "client-1", "client-2")
            for (line in spotify.logs) assertTrue(secrets.none { it in line }, line)
        }
}
