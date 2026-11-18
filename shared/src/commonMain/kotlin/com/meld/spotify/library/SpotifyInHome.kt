package com.meld.spotify.library

import com.meld.spotify.SpotifyAlbum
import com.meld.spotify.SpotifyConnection
import com.meld.spotify.SpotifyConnectionState
import com.meld.spotify.SpotifyError
import com.meld.spotify.SpotifyHomeFeed
import com.meld.spotify.SpotifyHomeItem
import com.meld.spotify.SpotifyLibraryItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform

/** One row of cards on the home screen. [title] is null where Spotify names none (its shortcuts) and for [NEW_RELEASES]. */
data class SpotifyHomeShelf(
    val key: String,
    val title: String?,
    val entries: List<SpotifyLibraryEntry>,
) {
    companion object {
        const val NEW_RELEASES = "new-releases"
    }
}

/** Spotify's part of the home screen. [shelves] is empty unless it is switched on and an account is connected. */
data class SpotifyInHomeState(
    val session: SpotifyConnectionState,
    val enabled: Boolean = true,
    val shelves: List<SpotifyHomeShelf> = emptyList(),
    val error: SpotifyError? = null,
) {
    val expired: Boolean get() = enabled && session is SpotifyConnectionState.Expired
}

/**
 * Loads the Spotify home feed once an account is connected and [enabled] says the home screen
 * shows it, and keeps it for the session: a new sign-in starts over, [refresh] asks again while
 * what was loaded stays up. A failure leaves an [SpotifyInHomeState.error] and whatever was loaded before.
 * [newReleases] is optional content: when it fails the feed is shown without it.
 */
class SpotifyInHome(
    scope: CoroutineScope,
    connection: SpotifyConnection,
    enabled: Flow<Boolean>,
    private val home: suspend () -> SpotifyHomeFeed,
    private val newReleases: suspend () -> List<SpotifyAlbum>,
) {
    private data class Content(
        val shelves: List<SpotifyHomeShelf> = emptyList(),
        val error: SpotifyError? = null,
    )

    private val content = MutableStateFlow(Content())
    private val requests = MutableStateFlow(0)

    val state: StateFlow<SpotifyInHomeState> =
        combine(connection.state, enabled, content) { session, isEnabled, loaded ->
            val shown = isEnabled && session is SpotifyConnectionState.Connected
            SpotifyInHomeState(session, isEnabled, if (shown) loaded.shelves else emptyList(), loaded.error.takeIf { shown })
        }.stateIn(scope, SharingStarted.Eagerly, SpotifyInHomeState(connection.state.value, enabled = false))

    init {
        val connected = connection.state.map { it is SpotifyConnectionState.Connected }.distinctUntilChanged()
        scope.launch {
            // Only this collector touches them, one value at a time.
            var account = false
            var loaded: Int? = null
            combine(connected, enabled.distinctUntilChanged(), requests, ::Triple).collectLatest { (isConnected, isEnabled, request) ->
                if (isConnected != account) {
                    account = isConnected
                    loaded = null
                    content.value = Content()
                }
                if (!isConnected || !isEnabled || loaded == request) return@collectLatest
                try {
                    content.value = Content(load())
                    loaded = request
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    // Anything that is not a SpotifyError is a bug in reading the response: shown as such, never as raw text.
                    val error = failure as? SpotifyError ?: SpotifyError.Changed("Unexpected failure", failure)
                    content.update { it.copy(error = error) }
                }
            }
        }
    }

    /** Asks Spotify again; also the retry after a failure. */
    fun refresh() = requests.update { it + 1 }

    private suspend fun load(): List<SpotifyHomeShelf> =
        coroutineScope {
            val releases =
                async {
                    try {
                        newReleases()
                    } catch (_: SpotifyError) {
                        emptyList()
                    }
                }
            val shelves = home().shelves()
            val albums = releases.await().map(SpotifyAlbum::toEntry).distinctBy { it.key }
            if (albums.isEmpty()) shelves else shelves + SpotifyHomeShelf(SpotifyHomeShelf.NEW_RELEASES, null, albums)
        }
}

/**
 * The feed as rows of cards. The parser has already left out what the app has no screen for
 * (episodes, shows, unknown kinds) and the sections that this empties.
 */
internal fun SpotifyHomeFeed.shelves(): List<SpotifyHomeShelf> =
    sections.mapIndexed { index, section ->
        // Spotify can repeat an item within a section, and a card is keyed by what it opens.
        SpotifyHomeShelf("$index:${section.uri}", section.title ?: greeting, section.items.map { it.toEntry() }.distinctBy { it.key })
    }

private fun SpotifyHomeItem.toEntry(): SpotifyLibraryEntry =
    when (this) {
        is SpotifyHomeItem.Playlist -> SpotifyLibraryItem.Playlist(playlist).toEntry()
        is SpotifyHomeItem.Album -> album.toEntry()
        is SpotifyHomeItem.Artist -> artist.toEntry()
    }

/** Where the app's own home refresh also refreshes the Spotify rows. */
fun refreshSpotifyInHome() {
    KoinPlatform.getKoinOrNull()?.getOrNull<SpotifyInHome>()?.refresh()
}
