package com.meld.spotify

import com.metrolist.shared.spotifyimport.SpotifyImportManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import meow.nyx.spotlib.ApiHttp
import meow.nyx.spotlib.Queries
import meow.nyx.spotlib.SpotifyConfig
import meow.nyx.spotlib.SpotifyCookieJar
import meow.nyx.spotlib.SpotifySession
import meow.nyx.spotlib.Totp
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock

/** Meld's common-code client against the upstream JVM client it replaced, which shares the stored session. */
class SpotifyUpstreamParityTest {
    @Test
    fun theOneTimeCodeEqualsUpstreamsAtAnyInstant() {
        assertEquals(Totp.version(), SpotifyTotp.VERSION)
        val period = 30_000L
        val boundaries =
            listOf(0L, 1_700_000_010_000L, 1_799_999_990_000L, 4_102_444_800_000L, 253_402_300_799_000L)
                .map { it / period * period }
                .flatMap { listOf(it - 1, it, it + 1, it + period - 1, it + period) }
                .filter { it >= 0 }
        val random = Random(61)
        val instants = boundaries + List(2_000) { random.nextLong(0, 4_102_444_800_000L) }
        assertEquals(instants.map { Totp.code(it) }, instants.map { SpotifyTotp.code(it) })
    }

    @Test
    fun cookiesAreKeptAndSentAsUpstreamDoes() =
        runTest {
            val exchanges =
                listOf(
                    "https://open.spotify.com/api/token" to
                        listOf("sp_t=device; Domain=.spotify.com; Path=/", "sp_landing=x; Path=/", "sp_gone=1; Domain=.spotify.com"),
                    "https://api.spotify.com/v1/me" to
                        listOf("sp_gone=; Max-Age=0; Domain=.spotify.com", "sp_old=1; Expires=Thu, 01 Jan 1970 00:00:00 GMT"),
                    "https://open.spotify.com/api/token" to emptyList(),
                    "https://api-partner.spotify.com/pathfinder/v2/query" to emptyList(),
                    "https://clienttoken.spotify.com/v1/clienttoken" to emptyList(),
                    "https://francescograzioso.github.io/Meld/spotify-gql-hashes.json" to emptyList(),
                    "https://api.spotify.com/v1/me" to emptyList(),
                )

            suspend fun sent(get: suspend (HttpClient, String) -> Unit): List<Set<String>?> {
                val seen = mutableListOf<Set<String>?>()
                val engine =
                    MockEngine { request ->
                        val setCookies = exchanges[seen.size].second
                        seen += request.headers[HttpHeaders.Cookie]?.split("; ")?.toSet()
                        respond("", headers = headersOf(HttpHeaders.SetCookie, setCookies))
                    }
                HttpClient(engine).use { http -> exchanges.forEach { (url, _) -> get(http, url) } }
                return seen
            }

            val jar = SpotifyCookieJar().apply { store(Url("https://open.spotify.com"), "sp_dc", "login") }
            val upstream = sent { http, url -> ApiHttp(http, jar).get(url) }
            var meld: SpotifyWebClient? = null
            val ours =
                sent { http, url ->
                    val client =
                        meld ?: SpotifyWebClient(http, { Clock.System.now().toEpochMilliseconds() }, {}).also {
                            it.storeCookie("sp_dc", "login")
                            meld = it
                        }
                    client.get(url)
                }

            assertEquals(upstream, ours)
            assertEquals(setOf("sp_t=device", "sp_gone=1"), ours[1])
            assertEquals(setOf("sp_dc=login", "sp_t=device", "sp_landing=x"), ours[2])
            assertEquals(listOf(null, null, null, setOf("sp_t=device")), ours.drop(3))
        }

    @Test
    fun aSessionWrittenByUpstreamGoesThroughMeldAndBack() =
        runTest {
            val now = 1_800_000_000_000L
            val expiry = now + 3_600_000
            val written =
                SpotifySession(
                    cookies = mapOf("sp_dc" to "login", "sp_t" to "device", "sp_key" to "key"),
                    accessToken = "stale",
                    accessTokenExpiresAtMs = now - 1,
                )
            var stored = SpotifyImportManager.sessionJsonForTest(written)
            var deviceId: String? = null
            val upstream = SpotifyConfig(engine = MockEngine { respond("") })
            val engine =
                MockEngine { request ->
                    val cookie = request.headers[HttpHeaders.Cookie]
                    assertEquals(listOf(upstream.userAgent), request.headers.getAll(HttpHeaders.UserAgent))
                    when (request.url.host) {
                        "open.spotify.com" -> {
                            assertEquals(setOf("sp_dc=login", "sp_t=device", "sp_key=key"), cookie?.split("; ")?.toSet())
                            respond("""{"accessToken":"fresh","accessTokenExpirationTimestampMs":$expiry,"isAnonymous":false}""")
                        }

                        "clienttoken.spotify.com" -> {
                            assertNull(cookie)
                            val body = Json.parseToJsonElement((request.body as TextContent).text) as JsonObject
                            val client = body.obj("client_data")
                            assertEquals(upstream.appVersion, client?.str("client_version"))
                            assertEquals(upstream.webClientId, client?.str("client_id"))
                            deviceId = client?.obj("js_sdk_data")?.str("device_id")
                            respond("""{"granted_token":{"token":"granted"}}""")
                        }

                        else -> {
                            assertNull(cookie)
                            assertEquals(Queries.PATHFINDER_URL, request.url.toString())
                            assertEquals(upstream.appVersion, request.headers["spotify-app-version"])
                            respond("""{"data":{"me":{"profile":{"uri":"spotify:user:u1","name":"Tester","avatar":{"sources":[]}}}}}""")
                        }
                    }
                }
            SpotifyApi(HttpClient(engine), { stored }, { stored = it }, hashesUrl = null, clock = { now }).use { it.profile() }

            assertEquals("device", deviceId)
            val expected =
                written.copy(
                    accessToken = "fresh",
                    accessTokenExpiresAtMs = expiry,
                    clientToken = "granted",
                    clientTokenFetchedAtMs = now,
                )
            // Read the way upstream's import manager reads it, and identical to what upstream itself would have written.
            assertEquals(expected, Json.decodeFromString(SpotifySession.serializer(), stored))
            assertEquals(SpotifyImportManager.sessionJsonForTest(expected), stored)
        }
}
