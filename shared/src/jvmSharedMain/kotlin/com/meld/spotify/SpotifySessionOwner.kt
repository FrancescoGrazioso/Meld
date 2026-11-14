package com.meld.spotify

import com.meld.spotify.SpotifyConnectionState.Connected
import com.meld.spotify.SpotifyConnectionState.Connecting
import com.meld.spotify.SpotifyConnectionState.Disconnected
import com.meld.spotify.SpotifyConnectionState.Expired
import com.meld.spotify.SpotifyConnectionState.Failed
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import meow.nyx.spotlib.SpotifySession

/**
 * The one owner of the Spotify session. The session is stored in upstream's `spotifySession`
 * setting, which its playlist import and Canvas also read: they may refresh it or sign in through
 * [saveFromGuest], but only this class ever clears it.
 */
class SpotifySessionOwner(
    engine: HttpClientEngine,
    private val scope: CoroutineScope,
    private val loadSession: () -> String,
    private val saveSession: (String) -> Unit,
    private val loadAccount: () -> String,
    private val saveAccount: (String) -> Unit,
    loadHashes: () -> String? = { null },
    saveHashes: (String) -> Unit = {},
    private val onLogout: () -> Unit = {},
    private val logger: (String) -> Unit = {},
) : SpotifyConnection {
    @Serializable
    private data class Account(
        val name: String? = null,
        val image: String? = null,
        val expired: Boolean = false,
    )

    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(stored())

    /** Use [call] instead when the caller cannot handle an expired session itself. */
    val api = SpotifyApi(engine, loadSession, saveSession, loadHashes, saveHashes, logger = logger)

    override val state: StateFlow<SpotifyConnectionState> = mutableState.asStateFlow()

    override fun login(spDc: String) {
        scope.launch { connect(spDc) }
    }

    override fun logout() {
        scope.launch { disconnect() }
    }

    override fun refresh() {
        scope.launch { verify() }
    }

    /** Runs [block] on the API; a session Spotify no longer accepts becomes the persisted [Expired] state. */
    suspend fun <T> call(block: suspend SpotifyApi.() -> T): T =
        try {
            api.block()
        } catch (error: SpotifyError.SessionExpired) {
            mutex.withLock { expire() }
            throw error
        }

    suspend fun connect(spDc: String) =
        mutex.withLock {
            mutableState.value = Connecting
            try {
                api.login(spDc)
            } catch (error: SpotifyError) {
                logger("Sign-in failed: ${error.message}")
                // A failed sign-in changes nothing that is stored, so a session that was there still counts.
                mutableState.value = stored().takeIf { it is Connected } ?: Failed(error.failure())
                return@withLock
            }
            // Drops the expired flag and the profile of whoever was signed in before.
            saveAccount("")
            loadProfile()
        }

    suspend fun verify() =
        mutex.withLock {
            if (spDc(loadSession()) != null) loadProfile()
        }

    suspend fun disconnect() =
        mutex.withLock {
            // First, so that no guest still holds a client that could write the session back.
            onLogout()
            api.logout()
            saveAccount("")
            mutableState.value = Disconnected
        }

    /**
     * Session writes of upstream's own Spotify consumers. A refresh of the current session is
     * stored, a sign-in they completed is adopted, and their request to wipe the session is
     * ignored: it is checked against Spotify here instead.
     */
    fun saveFromGuest(value: String) {
        val cookie = spDc(value)
        val current = spDc(loadSession())
        when {
            cookie == null -> {
                if (current != null) refresh()
            }

            cookie == current -> {
                saveSession(value)
            }

            else -> {
                saveSession(value)
                saveAccount("")
                login(cookie)
            }
        }
    }

    private suspend fun loadProfile() {
        try {
            val user = api.profile()
            saveAccount(json.encodeToString(Account.serializer(), Account(user.name, user.images.firstOrNull()?.url)))
        } catch (error: SpotifyError.SessionExpired) {
            expire()
            return
        } catch (error: SpotifyError) {
            // Not a verdict on the session: it stays, and the profile is loaded by the next refresh.
            logger("Profile unavailable: ${error.message}")
        }
        mutableState.value = stored()
    }

    // The dead cookie is removed so that upstream's Canvas falls back to anonymous lookups; the account name stays.
    private suspend fun expire() {
        val account = account()
        api.logout()
        saveAccount(json.encodeToString(Account.serializer(), account.copy(expired = true)))
        mutableState.value = stored()
    }

    private fun stored(): SpotifyConnectionState {
        val account = account()
        return when {
            spDc(loadSession()) != null -> Connected(account.name, account.image)
            account.expired -> Expired(account.name)
            else -> Disconnected
        }
    }

    private fun account(): Account =
        loadAccount()
            .takeIf(String::isNotBlank)
            ?.let { runCatching { json.decodeFromString(Account.serializer(), it) }.getOrNull() }
            ?: Account()

    private fun spDc(session: String): String? =
        session
            .takeIf(String::isNotBlank)
            ?.let { runCatching { json.decodeFromString(SpotifySession.serializer(), it) }.getOrNull() }
            ?.cookies
            ?.get("sp_dc")
            ?.takeIf(String::isNotBlank)

    private fun SpotifyError.failure(): SpotifyFailure =
        when (this) {
            is SpotifyError.Offline -> SpotifyFailure.OFFLINE

            is SpotifyError.RateLimited -> SpotifyFailure.RATE_LIMITED

            is SpotifyError.Unavailable -> SpotifyFailure.UNAVAILABLE

            is SpotifyError.Changed -> SpotifyFailure.CHANGED

            is SpotifyError.SessionExpired,
            is SpotifyError.NotLoggedIn,
            is SpotifyError.Forbidden,
            -> SpotifyFailure.REJECTED
        }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
