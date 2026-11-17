package com.meld.spotify.likes

import com.meld.spotify.SpotifyPage
import com.meld.spotify.SpotifySessionOwner
import com.meld.spotify.SpotifyTrack
import com.meld.spotify.SpotifyTrackItem
import com.metrolist.shared.api.SongVote
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.state.SongLikeState
import kotlin.concurrent.Volatile

/** What the like sync asks of Spotify. Every call throws a [com.meld.spotify.SpotifyError]. */
interface SpotifyLikesRemote {
    suspend fun likedSongs(offset: Int): SpotifyPage<SpotifyTrackItem>

    suspend fun searchTracks(text: String): List<SpotifyTrack>

    suspend fun addToLibrary(uris: List<String>)

    suspend fun removeFromLibrary(uris: List<String>)
}

/** Through the session owner, so a session Spotify no longer accepts becomes the Expired state. */
internal class SessionSpotifyLikes(
    private val owner: SpotifySessionOwner,
) : SpotifyLikesRemote {
    override suspend fun likedSongs(offset: Int) = owner.call { likedSongs(offset) }

    override suspend fun searchTracks(text: String) = owner.call { search(text).tracks.items }

    override suspend fun addToLibrary(uris: List<String>) = owner.call { addToLibrary(uris) }

    override suspend fun removeFromLibrary(uris: List<String>) = owner.call { removeFromLibrary(uris) }
}

/** The app's own liked songs, as far as the like sync touches them. */
interface LocalLikes {
    /** The liked song with this id, null when it is not liked. */
    fun song(id: String): SongItem?

    fun isDisliked(id: String): Boolean

    /** Likes [songs] that are not liked yet. */
    fun add(songs: List<SongItem>)

    fun remove(ids: Collection<String>)
}

/**
 * Upstream's like state. [add] is its import path: the songs go to the end of the list, do not start the
 * downloads a like made by hand starts, and are queued as YouTube votes. That vote is what keeps them
 * through upstream's YouTube refresh, which drops every like YouTube does not confirm or have pending;
 * so a like coming from Spotify is also liked on YouTube Music when that account is signed in.
 */
internal object SongLikeStateLikes : LocalLikes {
    override fun song(id: String) = SongLikeState.likedSongsFlow.value.firstOrNull { it.id == id }

    override fun isDisliked(id: String) = SongLikeState.isDisliked(id)

    override fun add(songs: List<SongItem>) {
        if (songs.isNotEmpty()) SongLikeState.appendLikes(songs)
    }

    override fun remove(ids: Collection<String>) {
        SongLikeState.removeLikes(ids)
    }
}

/** Where upstream's SongLikeState reports every vote the user casts; imports are not reported. */
object LocalVotes {
    @Volatile
    internal var listener: ((songId: String, liked: Boolean) -> Unit)? = null

    internal fun onVotes(votes: Map<String, SongVote>) {
        val listener = listener ?: return
        votes.forEach { (id, vote) -> listener(id, vote == SongVote.LIKE) }
    }
}
