package com.meld.spotify.library

import com.meld.spotify.SpotifyConnectionState
import com.meld.spotify.SpotifyError
import com.meld.spotify.SpotifyLibraryItem
import com.meld.spotify.SpotifyPage
import com.meld.spotify.SpotifyPlaylist
import com.meld.spotify.SpotifyTrack
import com.meld.spotify.SpotifyTrackItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What editing a playlist asks of Spotify. Every call throws a [SpotifyError]. */
interface SpotifyPlaylistRemote {
    /** Adds the track at the end; Spotify accepts a track the playlist already has. */
    suspend fun add(
        playlistId: String,
        trackUri: String,
    )

    /** [uid] is the entry's [SpotifyTrackItem.uid], so one of several copies of a track can be meant. */
    suspend fun remove(
        playlistId: String,
        uid: String,
    )

    /** Moves the entry [uid] before the entry [beforeUid], or to the end when it is null. */
    suspend fun move(
        playlistId: String,
        uid: String,
        beforeUid: String?,
    )

    /** A null value is left as it is. */
    suspend fun details(
        playlistId: String,
        name: String?,
        description: String?,
    )

    /** Creates an empty playlist at the top of the library and returns its id. */
    suspend fun create(name: String): String

    /** One level of the playlists the user may add [itemUri] to, those of others they contribute to included. */
    suspend fun editable(
        itemUri: String,
        folderUri: String?,
        offset: Int,
    ): SpotifyPage<SpotifyLibraryItem>
}

/**
 * Whether the signed-in user owns the playlist of [ownerId], and so may edit it. The library lists say nothing
 * more; an opened playlist also says what a collaborator may do, see [SpotifyTracksUiState.canEdit].
 */
fun canEditPlaylist(
    ownerId: String?,
    session: SpotifyConnectionState,
): Boolean = ownerId != null && ownerId == (session as? SpotifyConnectionState.Connected)?.id

internal sealed interface SpotifyPlaylistChange {
    data class Add(
        val track: SpotifyTrack,
    ) : SpotifyPlaylistChange

    data class Remove(
        val uid: String,
    ) : SpotifyPlaylistChange

    data class Move(
        val uid: String,
        val beforeUid: String?,
    ) : SpotifyPlaylistChange

    data class Details(
        val name: String?,
        val description: String?,
    ) : SpotifyPlaylistChange
}

/** An edit Spotify did not take, with the edits made after it: all of them have been undone in the lists. */
class SpotifyPlaylistEditFailure internal constructor(
    val error: SpotifyError,
    internal val changes: List<SpotifyPlaylistChange>,
)

/**
 * Edits the user's Spotify playlists. An edit shows at once in the lists the store holds, then goes to
 * Spotify; edits are sent one at a time in the order they were made, each on top of the one before.
 * When Spotify does not take one, it and every edit made after it on that playlist are undone in the
 * lists and reported in [failures]. Edits need a connection: nothing is kept to be sent later.
 * [scope] outlives the screens, so an edit is sent even when its screen has closed.
 */
class SpotifyPlaylistEditor(
    private val remote: SpotifyPlaylistRemote,
    private val library: SpotifyLibrary,
    private val store: SpotifyLibraryStore,
    private val scope: CoroutineScope,
) {
    private class Edit(
        val playlistId: String,
        val change: SpotifyPlaylistChange,
    ) {
        val result = CompletableDeferred<SpotifyError?>()
        var pager: SpotifyPager<SpotifyTrackItem>? = null
        var placeholder: SpotifyTrackItem? = null
        var undo: suspend () -> Unit = {}
        var dropped: SpotifyError? = null
    }

    // Guards the lists and [unconfirmed]; never held during a request to Spotify.
    private val local = Mutex()
    private val unconfirmed = HashMap<String, MutableList<Edit>>()
    private val outgoing = Channel<Edit>(Channel.UNLIMITED)
    private val mutableFailures = MutableStateFlow(emptyMap<String, SpotifyPlaylistEditFailure>())

    /** The last failed edit of each playlist, until it is retried, dismissed or followed by another edit. */
    val failures: StateFlow<Map<String, SpotifyPlaylistEditFailure>> = mutableFailures.asStateFlow()

    init {
        scope.launch { for (edit in outgoing) send(edit) }
    }

    /** Each edit returns its outcome: null once Spotify has taken it, else the error that undid it. */
    fun add(
        playlistId: String,
        track: SpotifyTrack,
    ): Deferred<SpotifyError?> = submit(playlistId, SpotifyPlaylistChange.Add(track))

    fun remove(
        playlistId: String,
        uid: String,
    ): Deferred<SpotifyError?> = submit(playlistId, SpotifyPlaylistChange.Remove(uid))

    /**
     * Moves the entry [uid] before [beforeUid], or to the end when it is null. Refused, with null, until every
     * page of the playlist is loaded: a position in a partly loaded list is not a position in the playlist.
     */
    fun move(
        playlistId: String,
        uid: String,
        beforeUid: String?,
    ): Deferred<SpotifyError?>? {
        if (store
                .cachedPlaylist(playlistId)
                ?.tracks
                ?.state
                ?.value
                ?.complete != true
        ) {
            return null
        }
        return submit(playlistId, SpotifyPlaylistChange.Move(uid, beforeUid))
    }

    fun details(
        playlistId: String,
        name: String?,
        description: String?,
    ): Deferred<SpotifyError?> = submit(playlistId, SpotifyPlaylistChange.Details(name, description))

    /** Makes the failed edits of [playlistId] again, in their order. */
    fun retry(playlistId: String) {
        failures.value[playlistId]?.changes?.forEach { submit(playlistId, it) }
    }

    fun dismissFailure(playlistId: String) = mutableFailures.update { it - playlistId }

    private fun submit(
        playlistId: String,
        change: SpotifyPlaylistChange,
    ): Deferred<SpotifyError?> {
        val edit = Edit(playlistId, change)
        dismissFailure(playlistId)
        // Undispatched: the lock is asked for inside this call and it is fair, so edits take effect in the order they were made.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            local.withLock {
                apply(edit)
                unconfirmed.getOrPut(playlistId) { mutableListOf() } += edit
                outgoing.trySend(edit)
            }
        }
        return edit.result
    }

    /** Shows [edit] in the cached lists and notes how to take it back. */
    private suspend fun apply(edit: Edit) {
        val id = edit.playlistId
        val collection = store.cachedPlaylist(id)
        val pager = collection?.tracks
        edit.pager = pager
        pager?.hold()
        // Waits for a page still on its way, so what is read below is what the edit is made on.
        pager?.edit { it }
        val info = collection?.mutableInfo?.value
        val items = pager?.state?.value?.items
        var appended = 0
        var entry: SpotifyPlaylist? = null
        val change = edit.change
        val count =
            when (change) {
                is SpotifyPlaylistChange.Add -> 1
                is SpotifyPlaylistChange.Remove -> -1
                else -> 0
            }
        when (change) {
            is SpotifyPlaylistChange.Add -> {
                if (pager != null && pager.state.value.complete) {
                    // Spotify does not return the new entry: it is shown without a uid until [confirm] has read it.
                    val placeholder = SpotifyTrackItem(null, change.track.uri, null, change.track)
                    edit.placeholder = placeholder
                    pager.edit { it + placeholder }
                } else {
                    // The end of the list is not loaded: the new entry arrives with its page.
                    appended = 1
                    pager?.edit(appended) { it }
                }
            }

            is SpotifyPlaylistChange.Remove -> {
                pager?.edit { list -> list.filterNot { it.uid == change.uid } }
            }

            is SpotifyPlaylistChange.Move -> {
                pager?.edit { list -> list.moved(change.uid, change.beforeUid) }
            }

            is SpotifyPlaylistChange.Details -> {
                collection?.mutableInfo?.value =
                    info?.copy(title = change.name ?: info.title, description = change.description ?: info.description)
            }
        }
        if (count != 0) collection?.mutableInfo?.value = info?.copy(trackCount = info.trackCount?.plus(count))
        store.editPlaylistEntry(id) { playlist ->
            entry = playlist
            when (change) {
                is SpotifyPlaylistChange.Details -> {
                    playlist.copy(name = change.name ?: playlist.name, description = change.description ?: playlist.description)
                }

                else -> {
                    playlist.copy(trackCount = playlist.trackCount?.plus(count))
                }
            }
        }
        edit.undo = {
            if (items != null) pager.edit(-appended) { items }
            collection?.mutableInfo?.value = info
            entry?.let { before -> store.editPlaylistEntry(id) { before } }
        }
    }

    private suspend fun send(edit: Edit) {
        // Undone with an earlier edit that failed: Spotify must not get an edit made on top of one it never took.
        var error = edit.dropped
        if (error == null) {
            error =
                try {
                    request(edit)
                    null
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    failure as? SpotifyError ?: SpotifyError.Changed("Unexpected failure", failure)
                }
            if (error == null) {
                local.withLock { unconfirmed[edit.playlistId]?.remove(edit) }
                confirm(edit)
            } else {
                fail(edit, error)
            }
        }
        edit.pager?.release()
        edit.result.complete(error)
    }

    private suspend fun request(edit: Edit) {
        val id = edit.playlistId
        when (val change = edit.change) {
            is SpotifyPlaylistChange.Add -> remote.add(id, change.track.uri)
            is SpotifyPlaylistChange.Remove -> remote.remove(id, change.uid)
            is SpotifyPlaylistChange.Move -> remote.move(id, change.uid, change.beforeUid)
            is SpotifyPlaylistChange.Details -> remote.details(id, change.name, change.description)
        }
    }

    /** Reads the entry Spotify made of an added track, so that it too can be moved and removed. A failure leaves it without a uid. */
    private suspend fun confirm(edit: Edit) {
        val placeholder = edit.placeholder ?: return
        val pager = edit.pager ?: return
        try {
            val position =
                pager.state.value.items
                    .indexOfFirst { it === placeholder }
            if (position < 0) return
            val entry =
                library
                    .playlist(edit.playlistId, position)
                    .tracks.items
                    .firstOrNull()
            if (entry?.uid == null || entry.uri != placeholder.uri) return
            pager.edit { list -> list.map { if (it === placeholder) entry else it } }
        } catch (error: SpotifyError) {
            // The track is in the playlist all the same; the next refresh brings its uid.
        }
    }

    private suspend fun fail(
        edit: Edit,
        error: SpotifyError,
    ) {
        local.withLock {
            val undone = unconfirmed.remove(edit.playlistId).orEmpty()
            // Last made, first undone: each one puts back exactly what it found.
            undone.asReversed().forEach {
                it.dropped = error
                it.undo()
            }
            mutableFailures.update { it + (edit.playlistId to SpotifyPlaylistEditFailure(error, undone.map(Edit::change))) }
        }
        // A request that died on the way may still have reached Spotify: the list is read again rather than trusted.
        if (error is SpotifyError.Offline || error is SpotifyError.Unavailable) {
            edit.pager?.let { it.refresh(all = it.state.value.complete) }
        }
    }
}

/** The list with the entry [uid] before the entry [beforeUid], or last when it is null; unchanged when either is not in it. */
internal fun List<SpotifyTrackItem>.moved(
    uid: String,
    beforeUid: String?,
): List<SpotifyTrackItem> {
    val item = firstOrNull { it.uid == uid } ?: return this
    val rest = filterNot { it === item }
    val position = if (beforeUid == null) rest.size else rest.indexOfFirst { it.uid == beforeUid }
    if (position < 0) return this
    return rest.take(position) + item + rest.drop(position)
}
