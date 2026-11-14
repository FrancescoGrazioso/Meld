package com.meld.spotify

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal fun spotifyFixture(name: String): String =
    checkNotNull(SpotifyParsersTest::class.java.getResourceAsStream("/spotify/$name")).bufferedReader().use { it.readText() }

private fun data(json: String): JsonObject =
    Json
        .parseToJsonElement(json)
        .jsonObject
        .getValue("data")
        .jsonObject

class SpotifyParsersTest {
    @Test
    fun homeSampleCapturedFromTheWebPlayer() {
        val feed = assertNotNull(parseHome(data(spotifyFixture("home.json")), emptyList()))
        assertEquals("Good afternoon", feed.greeting)
        // The episodes-only section has nothing this client can show and is left out.
        assertEquals(listOf(null, "Made For Test User", "Recommended for today", "Your favorite artists"), feed.sections.map { it.title })

        val shorts = feed.sections.first()
        assertEquals("HomeShortsSectionData", shorts.typename)
        assertEquals("spotify:section:0JQ5DAIiKWzVFULQfUm85Y", shorts.uri)
        assertEquals(48, shorts.totalCount)
        assertEquals(9, shorts.items.size)

        val mix = assertIs<SpotifyHomeItem.Playlist>(shorts.items.first())
        assertEquals("id00000000000000000007", mix.playlist.id)
        assertEquals("Name 1", mix.playlist.name)
        assertEquals("Spotify", mix.playlist.ownerName)
        assertEquals("spotify", mix.playlist.ownerId)
        assertEquals(50, mix.playlist.trackCount)
        assertTrue(
            mix.playlist.images
                .single()
                .url
                .startsWith("https://pickasso.spotifycdn.com/"),
        )
        assertEquals("topic-mix", mix.format)
        assertEquals("#6F6FC0", mix.colorHex)
        assertEquals("Test User", mix.madeFor)

        val album =
            shorts.items
                .filterIsInstance<SpotifyHomeItem.Album>()
                .first()
                .album
        assertEquals("spotify:album:id00000000000000000020", album.uri)
        assertEquals("Name 9", album.name)
        assertEquals("single", album.type)
        assertEquals(listOf("Name 6", "Name 7", "Name 8"), album.artists.map { it.name })
        assertEquals(3, album.images.size)

        val artist =
            shorts.items
                .filterIsInstance<SpotifyHomeItem.Artist>()
                .first()
                .artist
        assertEquals("id00000000000000000014", artist.id)
        assertEquals("Name 3", artist.name)
        assertEquals(640, artist.images.first().width)
    }

    @Test
    fun playlistKeepsEveryEntryAndOnlyRealTracksBecomeTracks() {
        val page = assertNotNull(parsePlaylistPage(data(spotifyFixture("playlist.json")), "spotify:playlist:p1", 0, 100))
        assertEquals(
            SpotifyPlaylist("p1", "spotify:playlist:p1", "Road trip", "Songs for the car", page.playlist.images, "owner1", "Owner", 7),
            page.playlist,
        )
        assertEquals(
            "https://i.scdn.co/image/playlist-cover",
            page.playlist.images
                .single()
                .url,
        )

        // Seven raw entries, one of them null: paging follows Spotify's count, not ours.
        assertEquals(7, page.tracks.total)
        assertNull(page.tracks.nextOffset)
        assertEquals(listOf("u1", "u2", "u3", "u4", "u6", "u7"), page.tracks.items.map { it.uid })
        assertEquals(listOf("t1", "t2", null, null, null, null), page.tracks.items.map { it.track?.id })
        assertEquals("spotify:episode:e1", page.tracks.items[2].uri)
        assertEquals("spotify:local:Artist:Album:Title:200", page.tracks.items[3].uri)

        val first = assertNotNull(page.tracks.items[0].track)
        assertEquals("spotify:track:t1", first.uri)
        assertEquals("Song One", first.name)
        assertEquals(listOf("a1" to "Artist A", "a2" to "Artist B"), first.artists.map { it.id to it.name })
        assertEquals("al1", first.album?.id)
        assertEquals("Album One", first.album?.name)
        assertEquals(
            "https://i.scdn.co/image/al1",
            first.album
                ?.images
                ?.single()
                ?.url,
        )
        assertEquals(201_000, first.durationMs)
        assertTrue(first.explicit)
        assertEquals(true, first.playable)
        assertNull(first.isrc)
        assertEquals("2026-01-02T03:04:05Z", page.tracks.items[0].addedAt)

        val second = assertNotNull(page.tracks.items[1].track)
        assertEquals("spotify:track:t2", second.uri)
        assertEquals(180_500, second.durationMs)
        assertEquals(false, second.explicit)
        assertEquals(listOf("Artist A"), second.artists.map { it.name })
    }

    @Test
    fun likedSongsPageContinuesWhileSpotifyReportsMore() {
        val page = assertNotNull(parseLikedSongs(data(spotifyFixture("liked-songs.json")), 50, 2))
        assertEquals(120, page.total)
        assertEquals(52, page.nextOffset)
        assertEquals(listOf("l1", "l2"), page.items.map { it.track?.id })
        assertEquals("2026-05-06T07:08:09Z", page.items[0].addedAt)
        val track = assertNotNull(page.items[0].track)
        assertEquals("Liked One", track.name)
        assertEquals("Album Nine", track.album?.name)
        assertEquals(222_000, track.durationMs)
        assertTrue(track.explicit)
    }

    @Test
    fun albumTracksCarryTheAlbum() {
        val (album, tracks) = assertNotNull(parseAlbumPage(data(spotifyFixture("album.json")), "spotify:album:al1", 0, 50))
        assertEquals("Album One", album.name)
        assertEquals("album", album.type)
        assertEquals("2024-03-01T00:00:00Z", album.releaseDate)
        assertEquals(2, album.totalTracks)
        assertEquals(listOf("Artist A"), album.artists.map { it.name })
        assertEquals(2, album.images.size)
        assertNull(tracks.nextOffset)
        assertEquals(listOf("t1", "t2"), tracks.items.map { it.id })
        assertEquals(listOf(album, album), tracks.items.map { it.album })
        assertEquals(listOf(true, false), tracks.items.map { it.explicit })
        assertEquals(listOf("Artist A", "Artist B"), tracks.items[1].artists.map { it.name })
    }

    @Test
    fun pageEndsOnSpotifysTotalOrOnAnEmptyResponse() {
        assertEquals(3, page(listOf("a"), rawCount = 3, total = 10, offset = 0, limit = 3).nextOffset)
        assertNull(page(listOf("a"), rawCount = 3, total = 3, offset = 0, limit = 3).nextOffset)
        assertNull(page(emptyList<String>(), rawCount = 0, total = 10, offset = 4, limit = 3).nextOffset)
        // Without a total, a full raw page means there may be more.
        assertEquals(6, page(emptyList<String>(), rawCount = 3, total = null, offset = 3, limit = 3).nextOffset)
        assertNull(page(listOf("a"), rawCount = 2, total = null, offset = 3, limit = 3).nextOffset)
    }

    @Test
    fun libraryNodeKeepsFoldersAndCollaborativePlaylists() {
        val json =
            """
            {"data":{"me":{"libraryV3":{"totalCount":60,"pagingInfo":{"limit":4,"offset":0},"items":[
              {"item":{"__typename":"LibraryPseudoPlaylistResponseWrapper","_uri":"spotify:collection:tracks","data":{"__typename":"PseudoPlaylist","name":"Liked Songs"}}},
              {"item":{"__typename":"FolderResponseWrapper","_uri":"spotify:user:u1:folder:f1","data":{"__typename":"Folder","name":"Rock","totalLength":3}}},
              {"item":{"__typename":"CollaborativePlaylistResponseWrapper","_uri":"spotify:playlist:p2","data":{"__typename":"Playlist","name":"Shared","description":"","images":{"items":[]},"ownerV2":{"data":{"name":"Friend","uri":"spotify:user:friend"}}}}},
              {"item":{"__typename":"NotFound"}}
            ]}}}}
            """.trimIndent()
        val node = assertNotNull(parseLibraryNode(data(json), 0, 4))
        assertEquals(4, node.nextOffset)
        assertEquals(60, node.total)
        assertEquals(SpotifyFolder("spotify:user:u1:folder:f1", "Rock", 3), assertIs<SpotifyLibraryItem.Folder>(node.items[0]).folder)
        val playlist = assertIs<SpotifyLibraryItem.Playlist>(node.items[1]).playlist
        assertEquals(listOf("p2", "Shared", "friend", "Friend"), listOf(playlist.id, playlist.name, playlist.ownerId, playlist.ownerName))

        val playlists = assertNotNull(parseLibraryPlaylists(data(json), 0, 4))
        assertEquals(listOf("p2"), playlists.items.map { it.id })
        assertEquals(4, playlists.nextOffset)
    }

    @Test
    fun libraryArtistsAndAlbums() {
        val json =
            """
            {"data":{"me":{"libraryV3":{"totalCount":2,"items":[
              {"item":{"__typename":"ArtistResponseWrapper","_uri":"spotify:artist:a1","data":{"__typename":"Artist","profile":{"name":"Artist A"},"visuals":{"avatarImage":{"sources":[{"url":"https://i.scdn.co/image/a1","width":160,"height":160}]}}}}},
              {"item":{"__typename":"AlbumResponseWrapper","_uri":"spotify:album:al1","data":{"__typename":"Album","name":"Album One","artists":{"items":[{"uri":"spotify:artist:a1","profile":{"name":"Artist A"}}]},"coverArt":{"sources":[{"url":"https://i.scdn.co/image/al1"}]}}}}
            ]}}}}
            """.trimIndent()
        val artists = assertNotNull(parseLibraryArtists(data(json), 0, 50))
        assertEquals(
            listOf(SpotifyArtist("a1", "spotify:artist:a1", "Artist A", listOf(SpotifyImage("https://i.scdn.co/image/a1", 160, 160)))),
            artists.items,
        )
        assertNull(artists.nextOffset)
        val albums = assertNotNull(parseLibraryAlbums(data(json), 0, 50))
        assertEquals(listOf("al1" to "Album One"), albums.items.map { it.id to it.name })
    }

    @Test
    fun searchChecksTypenamesAndPagesEachSectionOnItsOwnTotal() {
        val json =
            """
            {"data":{"searchV2":{
              "tracksV2":{"totalCount":40,"items":[
                {"item":{"__typename":"TrackResponseWrapper","data":{"__typename":"Track","uri":"spotify:track:t1","name":"Song One","artists":{"items":[{"uri":"spotify:artist:a1","profile":{"name":"Artist A"}}]},"albumOfTrack":{"uri":"spotify:album:al1","name":"Album One"},"duration":{"totalMilliseconds":1000}}}},
                {"item":{"__typename":"EpisodeOrChapterResponseWrapper","data":{"__typename":"Episode","uri":"spotify:episode:e1","name":"Episode"}}}
              ]},
              "albumsV2":{"totalCount":1,"items":[{"__typename":"AlbumResponseWrapper","data":{"__typename":"Album","uri":"spotify:album:al1","name":"Album One","type":"SINGLE","date":{"year":2020},"artists":{"items":[{"uri":"spotify:artist:a1","profile":{"name":"Artist A"}}]}}}]},
              "artists":{"totalCount":1,"items":[{"__typename":"ArtistResponseWrapper","data":{"__typename":"Artist","uri":"spotify:artist:a1","profile":{"name":"Artist A"}}}]},
              "playlists":{"totalCount":1,"items":[{"__typename":"PlaylistResponseWrapper","data":{"__typename":"Playlist","uri":"spotify:playlist:p1","name":"Road trip","ownerV2":{"data":{"name":"Owner","uri":"spotify:user:owner1"}}}}]}
            }}}
            """.trimIndent()
        val result = assertNotNull(parseSearch(data(json), 0, 2))
        assertEquals(listOf("t1"), result.tracks.items.map { it.id })
        assertEquals(2, result.tracks.nextOffset)
        assertEquals(listOf("single" to "2020"), result.albums.items.map { it.type to it.releaseDate })
        assertNull(result.albums.nextOffset)
        assertEquals(listOf("Artist A"), result.artists.items.map { it.name })
        assertEquals(listOf("owner1"), result.playlists.items.map { it.ownerId })
    }

    @Test
    fun artistOverviewAndNewReleases() {
        val artistJson =
            """
            {"data":{"artistUnion":{"profile":{"name":"Artist A"},"visuals":{"avatarImage":{"sources":[{"url":"https://i.scdn.co/image/a1"}]}},
              "discography":{"topTracks":{"items":[{"track":{"uri":"spotify:track:t1","name":"Song One","artists":{"items":[{"uri":"spotify:artist:a1","profile":{"name":"Artist A"}}]},"albumOfTrack":{"uri":"spotify:album:al1","name":"Album One"},"duration":{"totalMilliseconds":1000}}},{"track":null}]},
                "albums":{"items":[{"releases":{"items":[{"uri":"spotify:album:al1","name":"Album One","date":{"year":2020}}]}},{"releases":{"items":[{"name":"No uri"}]}}]}},
              "relatedContent":{"relatedArtists":{"items":[{"uri":"spotify:artist:a2","profile":{"name":"Artist B"}},{"uri":"spotify:artist:a3"}]}}}}}
            """.trimIndent()
        val overview = assertNotNull(parseArtistOverview(data(artistJson), "spotify:artist:a1"))
        assertEquals("Artist A", overview.artist.name)
        assertEquals(listOf("t1"), overview.topTracks.map { it.id })
        assertEquals(listOf("a2"), overview.relatedArtists.map { it.id })
        assertEquals(listOf("al1" to "2020"), overview.albums.map { it.id to it.releaseDate })

        val feedJson =
            """
            {"data":{"whatsNewFeedItems":{"totalCount":5,"items":[
              {"content":{"__typename":"AlbumResponseWrapper","data":{"__typename":"Album","uri":"spotify:album:al1","name":"Album One","albumType":"EP","date":{"isoString":"2026-11-01T00:00:00Z"},"artists":{"items":[{"uri":"spotify:artist:a1","profile":{"name":"Artist A"}}]}}}},
              {"content":{"__typename":"EpisodeOrChapterResponseWrapper","data":{"__typename":"Episode","uri":"spotify:episode:e1"}}}
            ]}}}
            """.trimIndent()
        val releases = assertNotNull(parseNewReleases(data(feedJson), 0, 2))
        assertEquals(listOf("ep" to "2026-11-01T00:00:00Z"), releases.items.map { it.type to it.releaseDate })
        assertEquals(2, releases.nextOffset)
    }

    @Test
    fun webApiTracksCarryIsrcAndExplicit() {
        val body =
            Json
                .parseToJsonElement(
                    """
                    {"total":3,"offset":0,"limit":2,"next":"https://api.spotify.com/v1/me/top/tracks?offset=2","items":[
                      {"id":"t1","uri":"spotify:track:t1","name":"Song One","duration_ms":201000,"explicit":true,"external_ids":{"isrc":"USABC2400001"},
                       "artists":[{"id":"a1","uri":"spotify:artist:a1","name":"Artist A"}],
                       "album":{"id":"al1","uri":"spotify:album:al1","name":"Album One","album_type":"album","release_date":"2024-03-01","images":[{"url":"https://i.scdn.co/image/al1","width":640,"height":640}]}},
                      {"id":null,"name":"Unavailable"}
                    ]}
                    """.trimIndent(),
                ).jsonObject
        val page = parseRestPage(body, 0, ::parseRestTrack)
        assertEquals(2, page.nextOffset)
        assertEquals(3, page.total)
        val track = page.items.single()
        assertEquals("USABC2400001", track.isrc)
        assertTrue(track.explicit)
        assertEquals(201_000, track.durationMs)
        assertEquals(listOf("Artist A"), track.artists.map { it.name })
        assertEquals("2024-03-01", track.album?.releaseDate)
    }

    @Test
    fun missingRootsAreReportedAsNull() {
        val empty = JsonObject(emptyMap())
        assertNull(parseHome(empty, emptyList()))
        assertNull(parsePlaylistPage(empty, "spotify:playlist:p1", 0, 50))
        assertNull(parseLikedSongs(empty, 0, 50))
        assertNull(parseAlbumPage(empty, "spotify:album:al1", 0, 50))
        assertNull(parseLibraryNode(empty, 0, 50))
        assertNull(parseSearch(empty, 0, 20))
        assertNull(parseUser(empty))
    }
}
