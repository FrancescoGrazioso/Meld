package com.meld.spotify.download

import com.meld.spotify.SpotifyConnection
import com.meld.spotify.SpotifyConnectionState
import com.meld.spotify.SpotifyError
import com.meld.spotify.library.SpotifyCollectionType
import com.meld.spotify.library.SpotifyLibraryStore
import com.meld.spotify.library.playableTrack
import com.metrolist.shared.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The Spotify lists the user wants kept downloaded: what is added to them on Spotify is downloaded
 * without asking, when the app starts and when the list is opened. Nothing is ever removed from the
 * downloads here: a track taken off the list stays until the user deletes it.
 */
class SpotifyKeptDownloads(
    private val store: SpotifyLibraryStore,
    private val downloads: SpotifyDownloads,
    private val connection: SpotifyConnection,
    private val settings: () -> Settings,
    private val scope: CoroutineScope,
    private val logger: (String) -> Unit = {},
    private val startDelay: Duration = 20.seconds,
) {
    private val lock = Mutex()
    private val mutableKeys = MutableStateFlow(stored())

    /** The kept lists, as "TYPE:id". */
    val keys: StateFlow<Set<String>> get() = mutableKeys

    /** Brings every kept list up to date once the account is connected, after the app has had time to start. */
    fun start() {
        scope.launch {
            mutableKeys.value = stored()
            if (mutableKeys.value.isEmpty()) return@launch
            connection.state.first { it is SpotifyConnectionState.Connected }
            delay(startDelay)
            sweep()
        }
    }

    fun set(
        type: SpotifyCollectionType,
        id: String,
        kept: Boolean,
    ) {
        val key = keptKey(type, id)
        val keys = if (kept) stored() + key else stored() - key
        settings().meldSpotifyKeptDownloads = keys.joinToString("\n")
        mutableKeys.value = keys
        if (kept) scope.launch { update(key) }
    }

    /** Downloads what is new in the list, when it is a kept one. */
    fun opened(
        type: SpotifyCollectionType,
        id: String,
    ) {
        val key = keptKey(type, id)
        if (key in mutableKeys.value) scope.launch { update(key) }
    }

    internal suspend fun sweep() = mutableKeys.value.forEach { update(it) }

    internal suspend fun update(key: String) {
        val type = SpotifyCollectionType.entries.firstOrNull { it.name == key.substringBefore(':') } ?: return
        lock.withLock {
            if (key !in mutableKeys.value) return
            try {
                val tracks =
                    store
                        .collection(type, key.substringAfter(':'))
                        .tracks
                        .loadAll()
                        .mapNotNull { it.playableTrack }
                val result = downloads.download(tracks)
                if (result.queued > 0) logger("Kept list $type: ${result.queued} new downloads")
            } catch (error: SpotifyError) {
                // Tried again at the next start or when the list is opened.
                logger("Kept list $type not read: ${error.message}")
            }
        }
    }

    private fun stored(): Set<String> =
        settings()
            .meldSpotifyKeptDownloads
            .lineSequence()
            .filter { it.isNotBlank() }
            .toSet()
}

fun keptKey(
    type: SpotifyCollectionType,
    id: String,
): String = "$type:$id"
