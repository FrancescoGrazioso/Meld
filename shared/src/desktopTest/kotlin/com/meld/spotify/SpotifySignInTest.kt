package com.meld.spotify

import com.meld.spotify.SpotifyConnectionState.Disconnected
import com.meld.spotify.SpotifyConnectionState.Expired
import com.meld.spotify.SpotifyConnectionState.Failed
import com.meld.spotify.ui.SpotifySignIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The shared sign-in around a login window played by the test, against the real session owner. */
class SpotifySignInTest {
    /** The platform's part: a window that is open only while the sign-in asks for one, and answers once. */
    private class FakeCapture(
        private val signIn: SpotifySignIn,
    ) {
        var opened = 0

        fun open() {
            assertFalse(signIn.capturing)
            signIn.start()
            assertTrue(signIn.capturing)
            opened++
        }

        fun deliver(cookie: String) {
            signIn.cookie(cookie)
            assertFalse(signIn.capturing)
        }
    }

    private fun test(body: suspend CoroutineScope.(SpotifyBackend, SpotifySessionOwner, FakeCapture) -> Unit) =
        runBlocking<Unit> {
            val backend = SpotifyBackend()
            val owner = backend.owner(this)
            body(backend, owner, FakeCapture(SpotifySignIn(owner)))
        }

    @Test
    fun theCapturedCookieSignsIn() =
        test { backend, owner, capture ->
            capture.open()
            capture.deliver(FIRST)
            settle()

            assertEquals(TESTER, owner.state.value)
            assertTrue(FIRST in backend.session)
        }

    @Test
    fun closingTheWindowChangesNothing() =
        test { backend, owner, _ ->
            val signIn = SpotifySignIn(owner)
            signIn.start()
            signIn.cancel()
            assertFalse(signIn.capturing)
            // A cookie the closed window still delivers is not a sign-in.
            signIn.cookie(FIRST)
            settle()

            assertEquals(Disconnected, owner.state.value)
            assertEquals("", backend.session)
        }

    @Test
    fun anEmptyOrRepeatedCookieIsDropped() =
        test { backend, owner, capture ->
            capture.open()
            capture.deliver(" ")
            settle()
            assertEquals(Disconnected, owner.state.value)

            capture.open()
            capture.deliver(FIRST)
            capture.deliver("second-delivery")
            settle()
            assertEquals(TESTER, owner.state.value)
            assertTrue(FIRST in backend.session)
        }

    @Test
    fun aFailedSignInIsRetriedInANewWindow() =
        test { backend, owner, capture ->
            capture.open()
            capture.deliver("unknown-cookie")
            settle()
            assertIs<SpotifyError.SessionExpired>(assertIs<Failed>(owner.state.value).error)
            assertEquals("", backend.session)

            backend.offline = true
            capture.open()
            capture.deliver(FIRST)
            settle()
            assertIs<SpotifyError.Offline>(assertIs<Failed>(owner.state.value).error)

            backend.offline = false
            capture.open()
            capture.deliver(FIRST)
            settle()
            assertEquals(TESTER, owner.state.value)
            assertEquals(3, capture.opened)
        }

    @Test
    fun anExpiredSessionIsSignedInAgain() =
        test { backend, owner, capture ->
            owner.connect(FIRST)
            backend.accepted.clear()
            assertFailsWith<SpotifyError.SessionExpired> { owner.call { profile() } }
            assertEquals(Expired("Tester"), owner.state.value)

            backend.accepted += SECOND
            capture.open()
            capture.deliver(SECOND)
            settle()

            assertEquals(TESTER, owner.state.value)
            assertTrue(SECOND in backend.session)
        }
}
