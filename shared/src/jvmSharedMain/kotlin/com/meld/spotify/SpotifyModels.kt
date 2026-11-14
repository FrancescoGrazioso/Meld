package com.meld.spotify

/**
 * One page of a Spotify listing. [nextOffset] is derived from Spotify's own total and the raw
 * item count of the response, never from [items], which may be shorter after parsing.
 */
data class SpotifyPage<T>(
    val items: List<T>,
    val total: Int?,
    val nextOffset: Int?,
)

data class SpotifyImage(
    val url: String,
    val width: Int? = null,
    val height: Int? = null,
)

data class SpotifyUser(
    val id: String,
    val uri: String,
    val name: String?,
    val images: List<SpotifyImage> = emptyList(),
)

data class SpotifyArtist(
    val id: String,
    val uri: String,
    val name: String,
    val images: List<SpotifyImage> = emptyList(),
)

data class SpotifyAlbum(
    val id: String,
    val uri: String,
    val name: String,
    val type: String? = null,
    val artists: List<SpotifyArtist> = emptyList(),
    val images: List<SpotifyImage> = emptyList(),
    val releaseDate: String? = null,
    val totalTracks: Int? = null,
    val tracks: List<SpotifyTrack> = emptyList(),
)

data class SpotifyTrack(
    val id: String,
    val uri: String,
    val name: String,
    val artists: List<SpotifyArtist>,
    val album: SpotifyAlbum?,
    val durationMs: Long,
    val explicit: Boolean,
    val isrc: String? = null,
    val playable: Boolean? = null,
)

/**
 * An entry of a playlist or of the liked songs. [track] is null for anything that is not a
 * playable Spotify track (episode, local file, removed item); the entry is kept so positions
 * and [uid]s stay aligned with Spotify's.
 */
data class SpotifyTrackItem(
    val uid: String?,
    val uri: String?,
    val addedAt: String?,
    val track: SpotifyTrack?,
)

data class SpotifyPlaylist(
    val id: String,
    val uri: String,
    val name: String,
    val description: String? = null,
    val images: List<SpotifyImage> = emptyList(),
    val ownerId: String? = null,
    val ownerName: String? = null,
    val trackCount: Int? = null,
)

data class SpotifyPlaylistPage(
    val playlist: SpotifyPlaylist,
    val tracks: SpotifyPage<SpotifyTrackItem>,
)

data class SpotifyFolder(
    val uri: String,
    val name: String,
    val childCount: Int,
)

sealed interface SpotifyLibraryItem {
    data class Playlist(
        val playlist: SpotifyPlaylist,
    ) : SpotifyLibraryItem

    data class Folder(
        val folder: SpotifyFolder,
    ) : SpotifyLibraryItem
}

data class SpotifyArtistOverview(
    val artist: SpotifyArtist,
    val topTracks: List<SpotifyTrack>,
    val relatedArtists: List<SpotifyArtist>,
)

data class SpotifySearchResult(
    val tracks: SpotifyPage<SpotifyTrack>,
    val albums: SpotifyPage<SpotifyAlbum>,
    val artists: SpotifyPage<SpotifyArtist>,
    val playlists: SpotifyPage<SpotifyPlaylist>,
)

/** [partialErrors] lists GraphQL errors Spotify returned alongside the sections that did load. */
data class SpotifyHomeFeed(
    val greeting: String?,
    val sections: List<SpotifyHomeSection>,
    val partialErrors: List<String> = emptyList(),
)

data class SpotifyHomeSection(
    val uri: String,
    val title: String?,
    val typename: String,
    val totalCount: Int,
    val items: List<SpotifyHomeItem>,
)

sealed interface SpotifyHomeItem {
    data class Playlist(
        val playlist: SpotifyPlaylist,
        val format: String?,
        val colorHex: String?,
        val madeFor: String?,
    ) : SpotifyHomeItem

    data class Album(
        val album: SpotifyAlbum,
    ) : SpotifyHomeItem

    data class Artist(
        val artist: SpotifyArtist,
    ) : SpotifyHomeItem
}

sealed class SpotifyError(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    class NotLoggedIn : SpotifyError("No Spotify session")

    /** Spotify refused the session cookie, or a freshly issued token: the user has to sign in again. */
    class SessionExpired(
        cause: Throwable? = null,
    ) : SpotifyError("Spotify session expired", cause)

    class RateLimited(
        val retryAfterSeconds: Long,
    ) : SpotifyError("Spotify rate limited, retry after ${retryAfterSeconds}s")

    class Offline(
        cause: Throwable,
    ) : SpotifyError("Spotify is unreachable", cause)

    class Unavailable(
        val status: Int,
    ) : SpotifyError("Spotify server error (HTTP $status)")

    class Forbidden(
        operation: String,
    ) : SpotifyError("$operation: forbidden")

    /** Spotify no longer accepts what this client sends or returns something it cannot read. */
    class Changed(
        message: String,
        cause: Throwable? = null,
    ) : SpotifyError(message, cause)
}
