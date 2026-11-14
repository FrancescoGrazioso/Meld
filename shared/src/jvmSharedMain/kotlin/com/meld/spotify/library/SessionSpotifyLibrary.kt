package com.meld.spotify.library

import com.meld.spotify.SpotifySessionOwner

/** Reads through the session owner, so a session Spotify no longer accepts becomes the Expired state. */
internal class SessionSpotifyLibrary(
    private val owner: SpotifySessionOwner,
) : SpotifyLibrary {
    override suspend fun playlists(
        folderUri: String?,
        offset: Int,
    ) = owner.call { libraryNode(folderUri, offset) }

    override suspend fun albums(offset: Int) = owner.call { libraryAlbums(offset) }

    override suspend fun artists(offset: Int) = owner.call { libraryArtists(offset) }

    override suspend fun likedSongs(offset: Int) = owner.call { likedSongs(offset) }

    override suspend fun playlist(
        id: String,
        offset: Int,
    ) = owner.call { playlist(id, offset) }

    override suspend fun album(id: String) = owner.call { album(id) }

    override suspend fun artist(id: String) = owner.call { artist(id) }
}
