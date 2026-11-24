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

internal class SessionSpotifyPlaylists(
    private val owner: SpotifySessionOwner,
) : SpotifyPlaylistRemote {
    override suspend fun add(
        playlistId: String,
        trackUri: String,
    ) = owner.call { addToPlaylist(playlistId, listOf(trackUri)) }

    override suspend fun remove(
        playlistId: String,
        uid: String,
    ) = owner.call { removeFromPlaylist(playlistId, listOf(uid)) }

    override suspend fun move(
        playlistId: String,
        uid: String,
        beforeUid: String?,
    ) = owner.call { moveInPlaylist(playlistId, listOf(uid), beforeUid) }

    override suspend fun details(
        playlistId: String,
        name: String?,
        description: String?,
    ) = owner.call { editPlaylist(playlistId, name, description) }

    override suspend fun create(name: String) = owner.call { createPlaylist(name) }

    override suspend fun delete(playlistId: String) = owner.call { deletePlaylist(playlistId) }

    override suspend fun follow(playlistId: String) = owner.call { followPlaylist(playlistId) }

    override suspend fun editable(
        itemUri: String,
        folderUri: String?,
        offset: Int,
    ) = owner.call { editablePlaylists(itemUri, folderUri, offset) }
}
