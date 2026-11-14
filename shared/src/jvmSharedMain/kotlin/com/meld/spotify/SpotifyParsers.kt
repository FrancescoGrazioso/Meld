package com.meld.spotify

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonObject.objs(key: String): List<JsonObject> = arr(key)?.filterIsInstance<JsonObject>().orEmpty()

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

private fun String.spotifyId(): String = substringAfterLast(':')

/** [rawCount] is the number of entries Spotify returned, before any was dropped by parsing. */
internal fun <T> page(
    items: List<T>,
    rawCount: Int,
    total: Int?,
    offset: Int,
    limit: Int,
): SpotifyPage<T> {
    val end = offset + rawCount
    val hasMore = rawCount > 0 && if (total != null) end < total else rawCount >= limit
    return SpotifyPage(items, total, end.takeIf { hasMore })
}

private fun <T> JsonObject?.pageOf(
    offset: Int,
    limit: Int,
    parse: (JsonObject) -> T?,
): SpotifyPage<T> =
    page(
        items = this?.objs("items").orEmpty().mapNotNull(parse),
        rawCount = this?.arr("items")?.size ?: 0,
        total = this?.int("totalCount"),
        offset = offset,
        limit = limit,
    )

private fun JsonObject?.images(): List<SpotifyImage> =
    this?.objs("sources").orEmpty().mapNotNull { source ->
        source.str("url")?.let { SpotifyImage(it, source.int("width"), source.int("height")) }
    }

internal fun parseUser(data: JsonObject): SpotifyUser? {
    val profile = data.obj("me")?.obj("profile") ?: return null
    val uri = profile.str("uri") ?: return null
    return SpotifyUser(uri.spotifyId(), uri, profile.str("name"), profile.obj("avatar").images())
}

private fun parseArtist(
    data: JsonObject,
    uri: String? = data.str("uri"),
): SpotifyArtist? {
    if (uri == null || !uri.startsWith("spotify:artist:")) return null
    val name = data.obj("profile")?.str("name") ?: data.str("name") ?: return null
    return SpotifyArtist(uri.spotifyId(), uri, name, data.obj("visuals")?.obj("avatarImage").images())
}

private fun JsonObject.artists(): List<SpotifyArtist> = obj("artists")?.objs("items").orEmpty().mapNotNull { parseArtist(it) }

private fun parseAlbum(
    data: JsonObject,
    uri: String? = data.str("uri"),
): SpotifyAlbum? {
    if (uri == null || !uri.startsWith("spotify:album:")) return null
    val date = data.obj("date")
    return SpotifyAlbum(
        id = uri.spotifyId(),
        uri = uri,
        name = data.str("name").orEmpty(),
        type = (data.str("type") ?: data.str("albumType"))?.lowercase(),
        artists = data.artists(),
        images = data.obj("coverArt").images(),
        releaseDate = date?.str("isoString") ?: date?.int("year")?.toString(),
    )
}

private fun parsePlaylist(
    data: JsonObject,
    uri: String? = data.str("uri"),
): SpotifyPlaylist? {
    if (uri == null || !uri.startsWith("spotify:playlist:")) return null
    val owner = data.obj("ownerV2")?.obj("data")
    return SpotifyPlaylist(
        id = uri.spotifyId(),
        uri = uri,
        name = data.str("name").orEmpty(),
        description = data.str("description"),
        images =
            data
                .obj("images")
                ?.objs("items")
                .orEmpty()
                .flatMap { it.images() },
        ownerId = owner?.str("uri")?.spotifyId() ?: owner?.str("id"),
        ownerName = owner?.str("name"),
        trackCount = data.obj("content")?.int("totalCount"),
    )
}

/** Returns null for anything that is not a named Spotify track, so episodes and local files never pose as tracks. */
internal fun parseTrack(
    data: JsonObject,
    uri: String? = data.str("uri") ?: data.str("_uri"),
    album: SpotifyAlbum? = data.obj("albumOfTrack")?.let { parseAlbum(it) },
): SpotifyTrack? {
    if (uri == null || !uri.startsWith("spotify:track:")) return null
    if (data.str("__typename").let { it != null && it != "Track" }) return null
    val name = data.str("name")?.takeIf(String::isNotBlank) ?: return null
    return SpotifyTrack(
        id = uri.spotifyId(),
        uri = uri,
        name = name,
        artists = data.artists(),
        album = album,
        durationMs =
            data.obj("duration")?.long("totalMilliseconds")
                ?: data.obj("trackDuration")?.long("totalMilliseconds")
                ?: data.long("durationMs")
                ?: 0,
        explicit = data.obj("contentRating")?.str("label") == "EXPLICIT",
        playable = data.obj("playability")?.bool("playable"),
    )
}

private fun parseTrackItem(
    entry: JsonObject,
    wrapperKey: String,
): SpotifyTrackItem {
    val wrapper = entry.obj(wrapperKey)
    val data = wrapper?.obj("data")
    val uri = wrapper?.str("_uri") ?: wrapper?.str("uri") ?: data?.str("uri")
    return SpotifyTrackItem(
        uid = entry.str("uid") ?: wrapper?.str("uid"),
        uri = uri,
        addedAt = entry.obj("addedAt")?.str("isoString"),
        track = data?.let { parseTrack(it, uri) },
    )
}

internal fun parsePlaylistPage(
    data: JsonObject,
    playlistUri: String,
    offset: Int,
    limit: Int,
): SpotifyPlaylistPage? {
    val root = data.obj("playlistV2") ?: return null
    val playlist = parsePlaylist(root, playlistUri) ?: return null
    return SpotifyPlaylistPage(playlist, root.obj("content").pageOf(offset, limit) { parseTrackItem(it, "itemV2") })
}

internal fun parseLikedSongs(
    data: JsonObject,
    offset: Int,
    limit: Int,
): SpotifyPage<SpotifyTrackItem>? {
    val tracks = data.obj("me")?.obj("library")?.obj("tracks") ?: return null
    return tracks.pageOf(offset, limit) { parseTrackItem(it, "track") }
}

private fun <T> parseLibrary(
    data: JsonObject,
    offset: Int,
    limit: Int,
    parse: (typename: String, wrapper: JsonObject, itemData: JsonObject?) -> T?,
): SpotifyPage<T>? {
    val library = data.obj("me")?.obj("libraryV3") ?: return null
    return library.pageOf(offset, limit) { entry ->
        entry.obj("item")?.let { parse(it.str("__typename").orEmpty(), it, it.obj("data")) }
    }
}

// Typenames are matched by substring because Spotify has shipped several wrapper names for the same thing.
private fun parseLibraryPlaylist(
    wrapper: JsonObject,
    data: JsonObject?,
): SpotifyPlaylist? = data?.takeIf { it.str("__typename") == "Playlist" }?.let { parsePlaylist(it, wrapper.str("_uri") ?: it.str("uri")) }

internal fun parseLibraryPlaylists(
    data: JsonObject,
    offset: Int,
    limit: Int,
): SpotifyPage<SpotifyPlaylist>? =
    parseLibrary(data, offset, limit) { typename, wrapper, itemData ->
        if (typename.contains("Playlist", ignoreCase = true)) parseLibraryPlaylist(wrapper, itemData) else null
    }

internal fun parseLibraryNode(
    data: JsonObject,
    offset: Int,
    limit: Int,
): SpotifyPage<SpotifyLibraryItem>? =
    parseLibrary(data, offset, limit) { typename, wrapper, itemData ->
        when {
            typename.contains("Playlist", ignoreCase = true) -> {
                parseLibraryPlaylist(wrapper, itemData)?.let(SpotifyLibraryItem::Playlist)
            }

            typename.contains("Folder", ignoreCase = true) -> {
                val uri = wrapper.str("_uri")
                val name = itemData?.str("name") ?: wrapper.str("name")
                if (uri == null || name == null) return@parseLibrary null
                val children = itemData?.int("totalLength") ?: itemData?.int("numberOfItems") ?: wrapper.int("totalLength") ?: 0
                SpotifyLibraryItem.Folder(SpotifyFolder(uri, name, children))
            }

            else -> {
                null
            }
        }
    }

internal fun parseLibraryArtists(
    data: JsonObject,
    offset: Int,
    limit: Int,
): SpotifyPage<SpotifyArtist>? =
    parseLibrary(data, offset, limit) { typename, wrapper, itemData ->
        itemData?.takeIf { typename.contains("Artist", ignoreCase = true) }?.let { parseArtist(it, wrapper.str("_uri") ?: it.str("uri")) }
    }

internal fun parseLibraryAlbums(
    data: JsonObject,
    offset: Int,
    limit: Int,
): SpotifyPage<SpotifyAlbum>? =
    parseLibrary(data, offset, limit) { typename, wrapper, itemData ->
        itemData?.takeIf { typename.contains("Album", ignoreCase = true) }?.let { parseAlbum(it, wrapper.str("_uri") ?: it.str("uri")) }
    }

/** One page of an album: the album itself (without tracks) and that page of its tracks. */
internal fun parseAlbumPage(
    data: JsonObject,
    albumUri: String,
    offset: Int,
    limit: Int,
): Pair<SpotifyAlbum, SpotifyPage<SpotifyTrack>>? {
    val root = data.obj("albumUnion") ?: return null
    val tracksNode = root.obj("tracksV2")
    val album = parseAlbum(root, albumUri)?.copy(totalTracks = tracksNode?.int("totalCount")) ?: return null
    return album to tracksNode.pageOf(offset, limit) { entry -> entry.obj("track")?.let { parseTrack(it, album = album) } }
}

internal fun parseArtistOverview(
    data: JsonObject,
    artistUri: String,
): SpotifyArtistOverview? {
    val root = data.obj("artistUnion") ?: return null
    val artist = parseArtist(root, artistUri) ?: return null
    return SpotifyArtistOverview(
        artist = artist,
        topTracks =
            root
                .obj("discography")
                ?.obj("topTracks")
                ?.objs("items")
                .orEmpty()
                .mapNotNull { entry -> entry.obj("track")?.let { parseTrack(it) } },
        relatedArtists =
            root
                .obj("relatedContent")
                ?.obj("relatedArtists")
                ?.objs("items")
                .orEmpty()
                .mapNotNull { parseArtist(it) },
    )
}

private fun JsonObject.wrapped(
    wrapperType: String,
    dataType: String,
): JsonObject? = takeIf { str("__typename") == wrapperType }?.obj("data")?.takeIf { it.str("__typename") == dataType }

internal fun parseSearch(
    data: JsonObject,
    offset: Int,
    limit: Int,
): SpotifySearchResult? {
    val root = data.obj("searchV2") ?: return null
    return SpotifySearchResult(
        tracks =
            root.obj("tracksV2").pageOf(offset, limit) { entry ->
                val wrapper = entry.obj("item")
                val track = wrapper?.wrapped("TrackResponseWrapper", "Track")
                track?.let { parseTrack(it, wrapper.str("_uri") ?: wrapper.str("uri") ?: it.str("uri")) }
            },
        albums =
            root.obj("albumsV2").pageOf(offset, limit) { entry ->
                entry.wrapped("AlbumResponseWrapper", "Album")?.let { parseAlbum(it) }
            },
        artists =
            root.obj("artists").pageOf(offset, limit) { entry ->
                entry.wrapped("ArtistResponseWrapper", "Artist")?.let { parseArtist(it) }
            },
        playlists =
            root.obj("playlists").pageOf(offset, limit) { entry ->
                entry.wrapped("PlaylistResponseWrapper", "Playlist")?.let { parsePlaylist(it) }
            },
    )
}

internal fun parseNewReleases(
    data: JsonObject,
    offset: Int,
    limit: Int,
): SpotifyPage<SpotifyAlbum>? {
    val feed = data.obj("whatsNewFeedItems") ?: return null
    return feed.pageOf(offset, limit) { entry -> entry.obj("content")?.wrapped("AlbumResponseWrapper", "Album")?.let { parseAlbum(it) } }
}

internal fun parseHome(
    data: JsonObject,
    partialErrors: List<String>,
): SpotifyHomeFeed? {
    val home = data.obj("home") ?: return null
    val sections =
        home
            .obj("sectionContainer")
            ?.obj("sections")
            ?.objs("items")
            .orEmpty()
            .mapNotNull(::parseHomeSection)
    return SpotifyHomeFeed(home.obj("greeting")?.str("transformedLabel"), sections, partialErrors)
}

private fun parseHomeSection(section: JsonObject): SpotifyHomeSection? {
    val typename = section.obj("data")?.str("__typename") ?: return null
    val title = section.obj("data")?.obj("title")
    val sectionItems = section.obj("sectionItems")
    val items = sectionItems?.objs("items").orEmpty().mapNotNull { it.obj("content")?.let(::parseHomeItem) }
    if (items.isEmpty()) return null
    return SpotifyHomeSection(
        uri = section.str("uri").orEmpty(),
        title = title?.str("transformedLabel") ?: title?.str("translatedBaseText") ?: title?.str("text"),
        typename = typename,
        totalCount = sectionItems?.int("totalCount") ?: 0,
        items = items,
    )
}

private fun parseHomeItem(content: JsonObject): SpotifyHomeItem? {
    val data = content.obj("data") ?: return null
    return when (content.str("__typename")) {
        "PlaylistResponseWrapper" -> {
            parsePlaylist(data)?.let { playlist ->
                SpotifyHomeItem.Playlist(
                    playlist = playlist,
                    format = data.str("format"),
                    colorHex =
                        data
                            .obj("images")
                            ?.objs("items")
                            ?.firstOrNull()
                            ?.obj("extractedColors")
                            ?.obj("colorDark")
                            ?.str("hex"),
                    madeFor = data.objs("attributes").firstOrNull { it.str("key") == "madeFor.username" }?.str("value"),
                )
            }
        }

        "AlbumResponseWrapper" -> {
            parseAlbum(data)?.let(SpotifyHomeItem::Album)
        }

        "ArtistResponseWrapper" -> {
            parseArtist(data)?.let(SpotifyHomeItem::Artist)
        }

        else -> {
            null
        }
    }
}

/** Parses a paging object of the public Web API (`items`, `total`, `next`). */
internal fun <T> parseRestPage(
    body: JsonObject,
    offset: Int,
    parse: (JsonObject) -> T?,
): SpotifyPage<T> {
    val rawCount = body.arr("items")?.size ?: 0
    return SpotifyPage(
        items = body.objs("items").mapNotNull(parse),
        total = body.int("total"),
        nextOffset = (offset + rawCount).takeIf { rawCount > 0 && body.str("next") != null },
    )
}

private fun JsonObject.restImages(): List<SpotifyImage> =
    objs("images").mapNotNull { image -> image.str("url")?.let { SpotifyImage(it, image.int("width"), image.int("height")) } }

internal fun parseRestArtist(artist: JsonObject): SpotifyArtist? {
    val id = artist.str("id") ?: return null
    return SpotifyArtist(id, artist.str("uri") ?: "spotify:artist:$id", artist.str("name").orEmpty(), artist.restImages())
}

internal fun parseRestTrack(track: JsonObject): SpotifyTrack? {
    val id = track.str("id") ?: return null
    val name = track.str("name")?.takeIf(String::isNotBlank) ?: return null
    return SpotifyTrack(
        id = id,
        uri = track.str("uri") ?: "spotify:track:$id",
        name = name,
        artists = track.objs("artists").mapNotNull(::parseRestArtist),
        album =
            track.obj("album")?.let { album ->
                album.str("id")?.let { albumId ->
                    SpotifyAlbum(
                        id = albumId,
                        uri = album.str("uri") ?: "spotify:album:$albumId",
                        name = album.str("name").orEmpty(),
                        type = album.str("album_type"),
                        images = album.restImages(),
                        releaseDate = album.str("release_date"),
                    )
                }
            },
        durationMs = track.long("duration_ms") ?: 0,
        explicit = track.bool("explicit") ?: false,
        isrc = track.obj("external_ids")?.str("isrc"),
    )
}
