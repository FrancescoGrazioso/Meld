package com.meld.spotify.library

import com.meld.spotify.SpotifyAlbum
import com.meld.spotify.SpotifyArtist
import com.meld.spotify.SpotifyConnection
import com.meld.spotify.SpotifyConnectionState
import com.meld.spotify.SpotifyError
import com.meld.spotify.SpotifyLibraryItem
import com.meld.spotify.SpotifySearchResult
import com.meld.spotify.SpotifyTrack
import com.meld.spotify.playback.SpotifyPlayback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SpotifySearchKind {
    TRACKS,
    ARTISTS,
    ALBUMS,
    PLAYLISTS,
}

/** What Spotify found for [query]. [next] holds, per kind that has more, the offset its next page starts at. */
data class SpotifySearchState(
    val query: String = "",
    val tracks: List<SpotifyTrack> = emptyList(),
    val artists: List<SpotifyLibraryEntry> = emptyList(),
    val albums: List<SpotifyLibraryEntry> = emptyList(),
    val playlists: List<SpotifyLibraryEntry> = emptyList(),
    val searching: Boolean = false,
    val loadingMore: Boolean = false,
    val error: SpotifyError? = null,
    val next: Map<SpotifySearchKind, Int> = emptyMap(),
) {
    val isEmpty: Boolean get() = tracks.isEmpty() && artists.isEmpty() && albums.isEmpty() && playlists.isEmpty()

    fun entries(kind: SpotifySearchKind): List<SpotifyLibraryEntry> =
        when (kind) {
            SpotifySearchKind.TRACKS -> emptyList()
            SpotifySearchKind.ARTISTS -> artists
            SpotifySearchKind.ALBUMS -> albums
            SpotifySearchKind.PLAYLISTS -> playlists
        }
}

/**
 * Spotify's results for the query the app's search screen has submitted. The last answer is kept,
 * so coming back from a result does not ask again. Like the app's own search it asks once per
 * submitted query, with no delay; a newer query cancels the one still under way. Its functions are
 * called from the UI thread. Without a [playback] the results can be browsed but not played, and
 * without a connected account nothing is asked at all.
 */
class SpotifyInSearch(
    private val scope: CoroutineScope,
    private val connection: SpotifyConnection,
    private val find: suspend (query: String, offset: Int) -> SpotifySearchResult,
    private val playback: SpotifyPlayback?,
) {
    private val mutableState = MutableStateFlow(SpotifySearchState())
    private var job: Job? = null

    val state: StateFlow<SpotifySearchState> = mutableState.asStateFlow()

    val canPlay: Boolean get() = playback != null

    /** A blank [query] clears the results and asks nothing; the query already shown is not asked for again unless it failed. */
    fun search(query: String) {
        val term = query.trim()
        val current = mutableState.value
        if (term == current.query && current.error == null) return
        job?.cancel()
        if (term.isEmpty() || connection.state.value !is SpotifyConnectionState.Connected) {
            mutableState.value = SpotifySearchState()
            return
        }
        mutableState.value = SpotifySearchState(term, searching = true)
        job =
            request(term) {
                val result = find(term, 0)
                SpotifySearchState(term).plus(result, SpotifySearchKind.entries)
            }
    }

    /** The next page of [kind]; Spotify answers with every kind, the others are not needed again. */
    fun loadMore(kind: SpotifySearchKind) {
        val current = mutableState.value
        val offset = current.next[kind] ?: return
        if (current.searching || current.loadingMore || current.error != null) return
        mutableState.value = current.copy(loadingMore = true)
        job =
            request(current.query) {
                val result = find(current.query, offset)
                mutableState.value.plus(result, listOf(kind))
            }
    }

    /** After a failure: the search again when nothing is shown, else the page of [kind] that did not load. */
    fun retry(kind: SpotifySearchKind?) {
        val current = mutableState.value
        if (current.error == null) return
        if (current.isEmpty) return search(current.query)
        mutableState.value = current.copy(error = null)
        kind?.let(::loadMore)
    }

    /** Plays the track at [index], followed by the rest of [tracks]: the track results as the screen lists them. */
    fun play(
        tracks: List<SpotifyTrack>,
        index: Int,
    ) {
        if (tracks.getOrNull(index)?.playable == false) return
        val playable = tracks.filter { it.playable != false }
        if (playable.isEmpty()) return
        playback?.play(playable, tracks.take(index).count { it.playable != false }, mutableState.value.query)
    }

    fun playNext(track: SpotifyTrack) {
        playback?.playNext(listOf(track))
    }

    fun addToQueue(track: SpotifyTrack) {
        playback?.addToQueue(listOf(track))
    }

    private fun request(
        term: String,
        load: suspend () -> SpotifySearchState,
    ): Job =
        scope.launch {
            val loaded =
                try {
                    load()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    // Anything that is not a SpotifyError is a bug in reading the response: shown as such, never as raw text.
                    val error = failure as? SpotifyError ?: SpotifyError.Changed("Unexpected failure", failure)
                    mutableState.value.copy(error = error)
                }
            // An answer to a query that is no longer the one shown is dropped.
            mutableState.update { if (it.query == term) loaded.copy(searching = false, loadingMore = false) else it }
        }
}

/** This state with the [kinds] of [result] appended; a result Spotify repeats across pages is kept once. */
private fun SpotifySearchState.plus(
    result: SpotifySearchResult,
    kinds: List<SpotifySearchKind>,
): SpotifySearchState {
    fun has(kind: SpotifySearchKind) = kind in kinds

    fun List<SpotifyLibraryEntry>.and(more: List<SpotifyLibraryEntry>) = (this + more).distinctBy { it.key }
    val offsets =
        mapOf(
            SpotifySearchKind.TRACKS to result.tracks.nextOffset,
            SpotifySearchKind.ARTISTS to result.artists.nextOffset,
            SpotifySearchKind.ALBUMS to result.albums.nextOffset,
            SpotifySearchKind.PLAYLISTS to result.playlists.nextOffset,
        )
    return copy(
        tracks = if (has(SpotifySearchKind.TRACKS)) (tracks + result.tracks.items).distinctBy { it.id } else tracks,
        artists = if (has(SpotifySearchKind.ARTISTS)) artists.and(result.artists.items.map(SpotifyArtist::toEntry)) else artists,
        albums = if (has(SpotifySearchKind.ALBUMS)) albums.and(result.albums.items.map(SpotifyAlbum::toEntry)) else albums,
        playlists =
            if (has(SpotifySearchKind.PLAYLISTS)) {
                playlists.and(result.playlists.items.map { SpotifyLibraryItem.Playlist(it).toEntry() })
            } else {
                playlists
            },
        next = (next - kinds.toSet()) + kinds.mapNotNull { kind -> offsets[kind]?.let { kind to it } },
    )
}
