package com.meld.spotify

import com.meld.spotify.SpotifyConnectionState.Connected
import com.meld.spotify.SpotifyConnectionState.Disconnected
import com.meld.spotify.SpotifyConnectionState.Expired
import com.meld.spotify.SpotifyConnectionState.Failed
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import meow.nyx.spotlib.SpotifySession
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private const val FIRST = "first-cookie"
private const val SECOND = "second-cookie"
private const val TOKEN = """{"accessToken":"access","accessTokenExpirationTimestampMs":4000000000000,"isAnonymous":false}"""
private const val PROFILE_AVATAR = """{"sources":[{"url":"https://i.scdn.co/image/u1"}]}"""
private const val PROFILE_BODY = """{"data":{"me":{"profile":{"uri":"spotify:user:u1","name":"Tester","avatar":$PROFILE_AVATAR}}}}"""
private val TESTER = Connected("Tester", "https://i.scdn.co/image/u1")

/** Spotify plus the two settings the owner persists to, so an owner can be re-created over them. */
private class SpotifyBackend {
    val accepted = mutableSetOf(FIRST)
    var offline = false
    var session = ""
    var account = ""
    var guestLogouts = 0

    private val engine =
        MockEngine { request ->
            if (offline) throw IOException("no route to host")
            val known = accepted.any { request.headers[HttpHeaders.Cookie] == "sp_dc=$it" }
            when (request.url.host) {
                "open.spotify.com" -> respond(if (known) TOKEN else """{"accessToken":"anonymous","isAnonymous":true}""")
                "clienttoken.spotify.com" -> respond("""{"granted_token":{"token":"client"}}""")
                "api-partner.spotify.com" -> if (accepted.isEmpty()) respond("", HttpStatusCode.Unauthorized) else respond(PROFILE_BODY)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

    fun owner(scope: CoroutineScope) =
        SpotifySessionOwner(
            engine = engine,
            scope = scope,
            loadSession = { session },
            saveSession = { session = it },
            loadAccount = { account },
            saveAccount = { account = it },
            onLogout = { guestLogouts++ },
        )
}

private fun sessionOf(cookie: String) = Json.encodeToString(SpotifySession.serializer(), SpotifySession(cookies = mapOf("sp_dc" to cookie)))

private suspend fun CoroutineScope.settle() =
    coroutineContext.job.children
        .toList()
        .joinAll()

class SpotifySessionOwnerTest {
    @Test
    fun signingInConnectsAndLoadsTheProfile() =
        runBlocking<Unit> {
            val backend = SpotifyBackend()
            val owner = backend.owner(this)
            assertEquals(Disconnected, owner.state.value)

            owner.connect(FIRST)

            assertEquals(TESTER, owner.state.value)
            assertContains(backend.session, FIRST)
            assertEquals(TESTER, backend.owner(this).state.value)
        }

    @Test
    fun failedSignInSaysWhyStoresNothingAndCanBeRetried() =
        runBlocking<Unit> {
            val backend = SpotifyBackend()
            val owner = backend.owner(this)

            backend.offline = true
            owner.connect(FIRST)
            assertEquals(Failed(SpotifyFailure.OFFLINE), owner.state.value)

            backend.offline = false
            owner.connect("unknown-cookie")
            assertEquals(Failed(SpotifyFailure.REJECTED), owner.state.value)
            assertEquals("", backend.session)
            assertEquals(Disconnected, backend.owner(this).state.value)

            owner.connect(FIRST)
            assertEquals(TESTER, owner.state.value)
        }

    @Test
    fun expiredSessionSurvivesARestartUntilTheNextSignIn() =
        runBlocking<Unit> {
            val backend = SpotifyBackend()
            val owner = backend.owner(this)
            owner.connect(FIRST)

            backend.accepted.clear()
            assertFailsWith<SpotifyError.SessionExpired> { owner.call { profile() } }

            assertEquals(Expired("Tester"), owner.state.value)
            assertEquals("", backend.session)
            assertEquals(Expired("Tester"), backend.owner(this).state.value)

            // A sign-in that fails does not clear the flag.
            val restarted = backend.owner(this)
            restarted.connect(SECOND)
            assertEquals(Failed(SpotifyFailure.REJECTED), restarted.state.value)
            assertEquals(Expired("Tester"), backend.owner(this).state.value)

            backend.accepted += SECOND
            restarted.connect(SECOND)
            assertEquals(TESTER, restarted.state.value)
            assertEquals(TESTER, backend.owner(this).state.value)
            restarted.disconnect()
            assertEquals(Disconnected, backend.owner(this).state.value)
        }

    @Test
    fun disconnectingClearsTheSessionTheProfileAndTheExpiredFlag() =
        runBlocking<Unit> {
            val backend = SpotifyBackend()
            val owner = backend.owner(this)
            owner.connect(FIRST)

            owner.disconnect()

            assertEquals(Disconnected, owner.state.value)
            assertEquals("", backend.session)
            assertEquals("", backend.account)
            assertEquals(1, backend.guestLogouts)
            assertFailsWith<SpotifyError.NotLoggedIn> { owner.api.profile() }
            assertEquals(Disconnected, backend.owner(this).state.value)
        }

    @Test
    fun guestCannotWipeASessionThatSpotifyStillAccepts() =
        runBlocking<Unit> {
            val backend = SpotifyBackend()
            val owner = backend.owner(this)
            owner.connect(FIRST)
            val stored = backend.session

            owner.saveFromGuest("")
            settle()
            assertEquals(stored, backend.session)
            assertEquals(TESTER, owner.state.value)

            // A refresh of the same session is stored as the guest wrote it.
            val refreshed = Json.encodeToString(SpotifySession.serializer(), SpotifySession(mapOf("sp_dc" to FIRST), "guest-token"))
            owner.saveFromGuest(refreshed)
            settle()
            assertEquals(refreshed, backend.session)
            assertEquals(TESTER, owner.state.value)
        }

    @Test
    fun guestWipeOfADeadSessionBecomesTheExpiredState() =
        runBlocking<Unit> {
            val backend = SpotifyBackend()
            val owner = backend.owner(this)
            owner.connect(FIRST)

            backend.accepted.clear()
            owner.saveFromGuest("")
            settle()

            assertEquals(Expired("Tester"), owner.state.value)
            assertEquals(Expired("Tester"), backend.owner(this).state.value)
        }

    @Test
    fun signInCompletedByAGuestIsAdopted() =
        runBlocking<Unit> {
            val backend = SpotifyBackend()
            val owner = backend.owner(this)
            owner.connect(FIRST)
            backend.accepted.clear()
            owner.verify()
            assertEquals(Expired("Tester"), owner.state.value)

            backend.accepted += SECOND
            owner.saveFromGuest(sessionOf(SECOND))
            settle()

            assertEquals(TESTER, owner.state.value)
            assertContains(backend.session, SECOND)
            assertEquals(TESTER, backend.owner(this).state.value)
        }
}
