package com.meld.spotify.library

import com.meld.spotify.SpotifyAlbum
import com.meld.spotify.SpotifyArtist
import com.meld.spotify.SpotifyArtistOverview
import com.meld.spotify.SpotifyLibraryItem
import com.meld.spotify.SpotifyPage
import com.meld.spotify.SpotifyPlaylistPage
import com.meld.spotify.SpotifyTrackItem
import kotlinx.serialization.Serializable

/**
 * What shared screens may read from the user's Spotify account. Every call throws a
 * [com.meld.spotify.SpotifyError]. Registered in Koin on JVM targets only.
 */
interface SpotifyLibrary {
    /** One level of the playlist tree: the root when [folderUri] is null, else the children of that folder. */
    suspend fun playlists(
        folderUri: String?,
        offset: Int,
    ): SpotifyPage<SpotifyLibraryItem>

    suspend fun albums(offset: Int): SpotifyPage<SpotifyAlbum>

    suspend fun artists(offset: Int): SpotifyPage<SpotifyArtist>

    suspend fun likedSongs(offset: Int): SpotifyPage<SpotifyTrackItem>

    suspend fun playlist(
        id: String,
        offset: Int,
    ): SpotifyPlaylistPage

    /** The album with all of its tracks. */
    suspend fun album(id: String): SpotifyAlbum

    suspend fun artist(id: String): SpotifyArtistOverview
}

/** The kinds of track list a Spotify detail screen can show. */
@Serializable
enum class SpotifyCollectionType {
    LIKED,
    PLAYLIST,
    ALBUM,
    ARTIST,
}
