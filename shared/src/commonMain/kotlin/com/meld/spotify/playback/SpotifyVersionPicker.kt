package com.meld.spotify.playback

import com.meld.spotify.SpotifyTrack
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * "Choose the YouTube version" for one Spotify track. [currentId] is the song that plays for it now, null
 * when none was found; [manual] says the user chose it. [candidates] are what YouTube Music has under the
 * track's title, the current song first.
 */
data class SpotifyVersionState(
    val track: SpotifyTrack,
    val loading: Boolean = true,
    val unreachable: Boolean = false,
    val currentId: String? = null,
    val manual: Boolean = false,
    val candidates: List<SongItem> = emptyList(),
)

/**
 * The one "Choose the YouTube version" dialog of the app: a track row opens it, the host draws it once.
 * A choice is kept as a manual override, which automatic matching never replaces.
 */
class SpotifyVersionPicker(
    private val resolver: SpotifyTrackResolver,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow<SpotifyVersionState?>(null)
    private var job: Job? = null

    /** Null while the dialog is closed. */
    val state: StateFlow<SpotifyVersionState?> = mutableState.asStateFlow()

    fun open(track: SpotifyTrack) {
        mutableState.value = SpotifyVersionState(track)
        load(track)
    }

    fun close() {
        job?.cancel()
        mutableState.value = null
    }

    fun retry() {
        val track = mutableState.value?.track ?: return
        mutableState.update { it?.copy(loading = true, unreachable = false) }
        load(track)
    }

    /** The choice holds from the next time the track is queued; what is already in the queue stays. */
    fun choose(song: SongItem) {
        val track = mutableState.value?.track ?: return
        scope.launch {
            resolver.choose(track, song)
            mutableState.update { if (it?.track == track) it.copy(currentId = song.id, manual = true) else it }
        }
    }

    /** Back to the song Meld finds by itself. */
    fun useAutomatic() {
        val track = mutableState.value?.track ?: return
        mutableState.update { it?.copy(loading = true) }
        job?.cancel()
        job =
            scope.launch {
                resolver.forgetChoice(track)
                show(track)
            }
    }

    private fun load(track: SpotifyTrack) {
        job?.cancel()
        job = scope.launch { show(track) }
    }

    private suspend fun show(track: SpotifyTrack) {
        try {
            val current = resolver.resolve(track)
            val currentSong = (current as? SpotifyResolution.Matched)?.song
            val found = resolver.candidates(track)
            // The current song leads, also when the search no longer lists it.
            val candidates = (listOfNotNull(currentSong) + found).distinctBy { it.id }
            val manual = resolver.isManual(track)
            mutableState.update {
                if (it?.track != track) it else it.copy(loading = false, currentId = currentSong?.id, manual = manual, candidates = candidates)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            mutableState.update { if (it?.track != track) it else it.copy(loading = false, unreachable = true) }
        }
    }
}
