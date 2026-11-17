package com.meld.spotify.likes

import com.meld.db.MeldDatabase
import com.meld.db.SpotifyMatchDao
import com.meld.db.buildMeldDatabase
import com.meld.spotify.SpotifyAlbum
import com.meld.spotify.SpotifyArtist
import com.meld.spotify.SpotifyArtistOverview
import com.meld.spotify.SpotifyConnection
import com.meld.spotify.SpotifyConnectionState
import com.meld.spotify.SpotifyConnectionState.Connected
import com.meld.spotify.SpotifyConnectionState.Disconnected
import com.meld.spotify.SpotifyConnectionState.Expired
import com.meld.spotify.SpotifyError
import com.meld.spotify.SpotifyLibraryItem
import com.meld.spotify.SpotifyPage
import com.meld.spotify.SpotifyPlaylistPage
import com.meld.spotify.SpotifyTrack
import com.meld.spotify.SpotifyTrackItem
import com.meld.spotify.library.SpotifyCollectionType
import com.meld.spotify.library.SpotifyLibrary
import com.meld.spotify.library.SpotifyLibraryStore
import com.meld.spotify.match.MatchConfidence
import com.meld.spotify.playback.FakeMatchDao
import com.meld.spotify.playback.FakeYouTube
import com.meld.spotify.playback.SpotifyTrackResolver
import com.meld.spotify.playback.storedMatch
import com.meld.spotify.playback.track
import com.meld.spotify.playback.ytSong
import com.metrolist.shared.api.SongVote
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.SongLikeState
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val USER = "user-1"

private fun uri(number: Int) = "spotify:track:sp$number"

private class FakeConnection : SpotifyConnection {
    override val state = MutableStateFlow<SpotifyConnectionState>(Connected("Tester", null, USER))

    override fun login(spDc: String) = Unit

    override fun logout() = Unit

    override fun refresh() = Unit
}

/** The account's Liked Songs, newest first. "Artist Song N" is found as the track spN unless [found] says otherwise. */
private class FakeSpotify : SpotifyLikesRemote {
    val liked = mutableListOf<SpotifyTrackItem>()
    val added = mutableListOf<String>()
    val removed = mutableListOf<String>()
    var error: SpotifyError? = null
    var failAtOffset: Int? = null
    var reportedTotal: Int? = null
    var pageRequests = 0
    var searches = 0
    var found: (String) -> List<SpotifyTrack> = { listOf(track(it.substringAfterLast(' ').toInt())) }

    fun like(numbers: Iterable<Int>) = numbers.forEach { liked.add(0, SpotifyTrackItem(null, uri(it), null, track(it))) }

    fun like(vararg numbers: Int) = like(numbers.asList())

    fun unlike(numbers: Iterable<Int>) {
        val uris = numbers.mapTo(hashSetOf(), ::uri)
        liked.removeAll { it.uri in uris }
    }

    fun unlike(vararg numbers: Int) = unlike(numbers.asList())

    override suspend fun likedSongs(offset: Int): SpotifyPage<SpotifyTrackItem> {
        error?.let { throw it }
        pageRequests++
        if (offset == failAtOffset) throw SpotifyError.Offline(IOException("connection reset"))
        val end = minOf(offset + 50, liked.size)
        return SpotifyPage(liked.subList(offset, end).toList(), reportedTotal ?: liked.size, end.takeIf { it < liked.size })
    }

    override suspend fun searchTracks(text: String): List<SpotifyTrack> {
        error?.let { throw it }
        searches++
        return found(text)
    }

    override suspend fun addToLibrary(uris: List<String>) {
        error?.let { throw it }
        added += uris
        like(uris.filter { uri -> liked.none { it.uri == uri } }.map { it.removePrefix("spotify:track:sp").toInt() })
    }

    override suspend fun removeFromLibrary(uris: List<String>) {
        error?.let { throw it }
        removed += uris
        liked.removeAll { it.uri in uris }
    }
}

private class FakeLocal : LocalLikes {
    val songs = LinkedHashMap<String, SongItem>()
    val disliked = mutableSetOf<String>()

    fun like(number: Int) {
        songs["yt$number"] = ytSong("yt$number", "Song $number")
    }

    fun unlike(number: Int) {
        songs.remove("yt$number")
    }

    override fun song(id: String) = songs[id]

    override fun isDisliked(id: String) = id in disliked

    override fun add(songs: List<SongItem>) = songs.forEach { this.songs.getOrPut(it.id) { it } }

    override fun remove(ids: Collection<String>) = ids.forEach { songs.remove(it) }
}

/** One run of the app over the database in [file]; [stored] is what the settings held when it started. */
private class App(
    file: File,
    scope: CoroutineScope,
    stored: MapSettings = MapSettings(),
    val local: LocalLikes = FakeLocal(),
    inMemoryMatches: Boolean = false,
) {
    val database: MeldDatabase = buildMeldDatabase(file)
    val matches: SpotifyMatchDao = if (inMemoryMatches) FakeMatchDao() else database.spotifyMatchDao()
    val likes = database.spotifyLikeDao()
    val spotify = FakeSpotify()
    val youtube = FakeYouTube()
    val connection = FakeConnection()
    val settings = Settings(stored)
    val songs get() = (local as FakeLocal).songs
    val background = CoroutineScope(Dispatchers.Default + Job())
    val store =
        SpotifyLibraryStore(
            object : SpotifyLibrary {
                override suspend fun likedSongs(offset: Int) = spotify.likedSongs(offset)

                override suspend fun playlists(
                    folderUri: String?,
                    offset: Int,
                ): SpotifyPage<SpotifyLibraryItem> = error("unused")

                override suspend fun albums(offset: Int): SpotifyPage<SpotifyAlbum> = error("unused")

                override suspend fun artists(offset: Int): SpotifyPage<SpotifyArtist> = error("unused")

                override suspend fun playlist(
                    id: String,
                    offset: Int,
                ): SpotifyPlaylistPage = error("unused")

                override suspend fun album(id: String): SpotifyAlbum = error("unused")

                override suspend fun artist(id: String): SpotifyArtistOverview = error("unused")
            },
            connection,
            background,
        )
    private var time = 1_000L

    fun sync(scope: CoroutineScope) =
        SpotifyLikeSync(
            dao = likes,
            matches = matches,
            remote = spotify,
            resolver = SpotifyTrackResolver(matches, youtube::search, { youtube.songsById(it) }, scope, { time }),
            local = local,
            connection = connection,
            settings = { settings },
            scope = scope,
            now = { time++ },
            onSpotifyChanged = store::likedSongsChanged,
        )

    val sync = sync(scope)

    fun close() {
        background.cancel()
        database.close()
    }
}

private fun withFile(block: suspend CoroutineScope.(File) -> Unit) =
    runBlocking {
        val path = Files.createTempFile("meld-likes", ".db")
        Files.deleteIfExists(path)
        try {
            block(path.toFile())
        } finally {
            Files.deleteIfExists(path)
        }
    }

/** An app with the account already taken over, as after the first connection state. */
private fun withApp(
    inMemoryMatches: Boolean = false,
    block: suspend App.(SpotifyLikeSync) -> Unit,
) = withFile { file ->
    val app = App(file, this, inMemoryMatches = inMemoryMatches)
    try {
        app.sync.adopt(app.connection.state.value)
        app.block(app.sync)
    } finally {
        app.close()
    }
}

private suspend fun awaitUntil(condition: suspend () -> Boolean) =
    withTimeout(20_000) {
        while (!condition()) delay(20)
    }

class SpotifyLikeSyncTest {
    @Test
    fun likeMadeOfflineSurvivesARestartAndIsSentOnce() =
        withFile { file ->
            val stored = MapSettings()
            var app = App(file, this, stored)
            app.sync.adopt(app.connection.state.value)
            (app.local as FakeLocal).like(1)
            app.spotify.error = SpotifyError.Offline(IOException("no route to host"))
            app.sync.record("yt1", liked = true)
            assertIs<SpotifyError.Offline>(app.sync.flush())
            assertEquals(1, app.sync.status.value.pending)
            app.close()

            // The process died; the next one finds the like where it was left.
            app = App(file, this, stored)
            try {
                (app.local as FakeLocal).like(1)
                app.sync.adopt(app.connection.state.value)
                assertEquals(1, app.sync.status.value.pending)
                assertNull(app.sync.flush())
                assertNull(app.sync.flush())
                assertEquals(listOf(uri(1)), app.spotify.added)
                assertEquals(0, app.sync.status.value.pending)
                assertEquals(setOf("sp1"), app.sync.likedIds.value)
            } finally {
                app.close()
            }
        }

    @Test
    fun oppositeIntentsCollapseBeforeAnythingIsSent() =
        withApp { sync ->
            val local = local as FakeLocal
            local.like(2)
            sync.record("yt2", liked = true)
            local.unlike(2)
            sync.record("yt2", liked = false)
            assertEquals(emptyList(), likes.outboxIds())
            assertNull(sync.flush())

            local.like(3)
            sync.record("yt3", liked = true)
            sync.record("yt3", liked = true)
            assertNull(sync.flush())
            assertEquals(listOf(uri(3)), spotify.added)

            // Spotify has it: un-liked and liked again before sending leaves it there, untouched.
            local.unlike(3)
            sync.record("yt3", liked = false)
            local.like(3)
            sync.record("yt3", liked = true)
            assertNull(sync.flush())
            assertEquals(listOf(uri(3)), spotify.added)
            assertEquals(emptyList(), spotify.removed)
            assertEquals(emptyList(), likes.outboxIds())

            local.unlike(3)
            sync.record("yt3", liked = false)
            assertNull(sync.flush())
            assertEquals(listOf(uri(3)), spotify.removed)
            assertEquals(emptySet(), sync.likedIds.value)
            assertEquals(1, spotify.searches)
        }

    @Test
    fun failuresKeepTheEntryUntilSpotifyAcceptsItAlsoAfterSigningInAgain() =
        withApp { sync ->
            val local = local as FakeLocal
            local.like(1)
            sync.record("yt1", liked = true)
            listOf(
                SpotifyError.Offline(IOException("timeout")),
                SpotifyError.RateLimited(7),
                SpotifyError.Unavailable(503),
                SpotifyError.SessionExpired(),
            ).forEach { error ->
                spotify.error = error
                assertEquals(error, sync.flush())
                assertEquals(1, sync.status.value.pending)
            }
            spotify.error = null

            // The session is gone: nothing is sent, and what the user likes meanwhile is still noted.
            connection.state.value = Expired("Tester")
            sync.adopt(connection.state.value)
            local.like(2)
            sync.record("yt2", liked = true)
            assertNull(sync.flush())
            assertEquals(emptyList(), spotify.added)
            assertEquals(2, sync.status.value.pending)

            connection.state.value = Connected("Tester", null, USER)
            sync.adopt(connection.state.value)
            assertNull(sync.flush())
            assertEquals(listOf(uri(1), uri(2)), spotify.added)
            assertEquals(0, sync.status.value.pending)
        }

    @Test
    fun anotherAccountNeverReceivesWhatWasMeantForTheFirst() =
        withApp { sync ->
            val local = local as FakeLocal
            spotify.like(5)
            assertTrue(sync.pull())
            local.like(1)
            spotify.error = SpotifyError.Offline(IOException("timeout"))
            sync.record("yt1", liked = true)
            sync.flush()
            spotify.error = null
            spotify.liked.clear()

            // Signed in as someone else straight from an expired session: no sign-out in between.
            connection.state.value = Connected("Other", null, "user-2")
            assertNull(sync.flush())
            assertFalse(sync.pull())
            assertEquals(emptyList(), spotify.added)

            sync.adopt(connection.state.value)
            assertEquals(emptyList(), likes.outboxIds())
            assertEquals(emptyList(), likes.likedTracks())
            assertNull(sync.flush())
            assertTrue(sync.pull())
            assertEquals(emptyList(), spotify.added)
            // The first account's likes stay liked in the app, although the second one's library is empty.
            assertEquals(setOf("yt5", "yt1"), songs.keys)

            spotify.error = SpotifyError.Offline(IOException("timeout"))
            sync.record("yt1", liked = true)
            sync.flush()
            connection.state.value = Disconnected
            sync.adopt(connection.state.value)
            assertEquals(emptyList(), likes.outboxIds())
            assertEquals("", settings.meldSpotifyLikesAccount)
            assertEquals(setOf("yt5", "yt1"), songs.keys)

            // Signed out: nothing is noted for an account that is not there.
            sync.record("yt5", liked = false)
            assertEquals(emptyList(), likes.outboxIds())
        }

    @Test
    fun reverseMatchLikesOnlyAConfidentTrackAndNeverReplacesAStoredMatch() =
        withApp { sync ->
            val local = local as FakeLocal
            local.like(7)
            sync.record("yt7", liked = true)
            assertNull(sync.flush())
            assertEquals(listOf(uri(7)), spotify.added)
            assertEquals("yt7", matches.get("sp7")?.youtubeId)
            assertEquals(MatchConfidence.HIGH, matches.get("sp7")?.confidence)

            // Spotify only has something else under that name: nothing is liked, and the user is told.
            spotify.found = { listOf(track(8).copy(name = "A Different Tune", durationMs = 95_000)) }
            local.like(8)
            sync.record("yt8", liked = true)
            assertNull(sync.flush())
            assertNull(sync.flush())
            assertEquals(listOf(uri(7)), spotify.added)
            assertNull(matches.get("sp8"))
            assertEquals(1, sync.status.value.notFound)
            assertEquals(0, sync.status.value.pending)
            assertEquals(2, spotify.searches)

            // The tracks are already matched to other videos: by playback, and by the user's own choice.
            spotify.found = { listOf(track(it.substringAfterLast(' ').toInt())) }
            matches.saveAutomatic(storedMatch(9, youtubeId = "video9"))
            matches.saveManualOverride(storedMatch(10, youtubeId = "chosen10"))
            local.like(9)
            local.like(10)
            sync.record("yt9", liked = true)
            sync.record("yt10", liked = true)
            assertNull(sync.flush())
            assertEquals(listOf(uri(7), uri(9), uri(10)), spotify.added)
            assertEquals("video9", matches.get("sp9")?.youtubeId)
            assertEquals("chosen10", matches.get("sp10")?.youtubeId)
            assertEquals(true, matches.get("sp10")?.isManualOverride)

            // The un-like still finds the track that was liked for the song.
            local.unlike(9)
            sync.record("yt9", liked = false)
            assertNull(sync.flush())
            assertEquals(listOf(uri(9)), spotify.removed)

            // A song played from Spotify is liked as that track, without a search.
            matches.saveAutomatic(storedMatch(3))
            val searches = spotify.searches
            local.like(3)
            sync.record("yt3", liked = true)
            assertNull(sync.flush())
            assertEquals(uri(3), spotify.added.last())
            assertEquals(searches, spotify.searches)
        }

    @Test
    fun pullLikesWhatIsNewOnSpotifyAndSendsNothingBack() =
        withApp { sync ->
            spotify.like(1, 2, 3, 4)
            youtube.missing += "Song 2"
            (local as FakeLocal).disliked += "yt4"
            assertTrue(sync.pull())
            assertEquals(listOf("yt3", "yt1"), songs.keys.toList())
            assertEquals(setOf("sp1", "sp2", "sp3", "sp4"), sync.likedIds.value)
            assertEquals(emptyList(), likes.outboxIds())
            assertEquals(emptyList(), spotify.added)
            assertTrue(sync.status.value.syncedAt > 0)
        }

    @Test
    fun pullUnlikesOnlyWhatWasRememberedAndIsGoneAfterACompleteRead() =
        withApp { sync ->
            val local = local as FakeLocal
            spotify.like(1..120)
            assertTrue(sync.pull())
            assertEquals(120, songs.size)
            local.like(500)
            spotify.unlike(5)

            spotify.failAtOffset = 50
            assertFalse(sync.pull(force = true))
            spotify.failAtOffset = null

            // Spotify counts more entries than it returned.
            spotify.reportedTotal = 500
            assertTrue(sync.pull(force = true))
            spotify.reportedTotal = null

            // An entry that cannot be told apart from a liked track.
            spotify.liked += SpotifyTrackItem(null, null, null, null)
            assertTrue(sync.pull(force = true))
            spotify.liked.removeAt(spotify.liked.lastIndex)

            // An empty library where 120 tracks were.
            val library = spotify.liked.toList()
            spotify.liked.clear()
            assertTrue(sync.pull(force = true))
            spotify.liked += library
            assertEquals(121, songs.size)

            // Removed on Spotify, but the user's own like of that song is still waiting to be sent.
            spotify.unlike(6)
            sync.record("yt6", liked = true)

            assertTrue(sync.pull())
            assertEquals((1..120).map { "yt$it" }.toSet() - "yt5" + "yt500", songs.keys)
            assertEquals(emptyList(), spotify.removed)
            assertNull(sync.flush())
            assertEquals(listOf(uri(6)), spotify.added)
        }

    @Test
    fun songSharedByTwoLikedTracksStaysLikedUntilBothAreGone() =
        withApp { sync ->
            youtube.results["Song 11"] = listOf(ytSong("yt10", "Song 11"))
            spotify.like(1, 10, 11)
            assertTrue(sync.pull())
            assertEquals(setOf("yt1", "yt10"), songs.keys)

            spotify.unlike(11)
            assertTrue(sync.pull())
            assertEquals(setOf("yt1", "yt10"), songs.keys)

            spotify.unlike(10)
            assertTrue(sync.pull())
            assertEquals(setOf("yt1"), songs.keys)
        }

    @Test
    fun fiveThousandLikesAreImportedAndMoreThanASqliteStatementHoldsAreRemoved() =
        withApp(inMemoryMatches = true) { sync ->
            spotify.like(1..5_000)
            assertTrue(sync.pull())
            assertEquals(5_000, songs.size)
            assertEquals(100, spotify.pageRequests)
            assertEquals("yt5000", songs.keys.first())

            spotify.unlike(1..1_200)
            assertTrue(sync.pull())
            assertEquals((1_201..5_000).map { "yt$it" }.toSet(), songs.keys)
            assertEquals(3_800, sync.likedIds.value.size)
        }

    @Test
    fun unchangedLibraryIsNotReadAgainAndAnUnfinishedImportIs() =
        withApp { sync ->
            spotify.like(1..60)
            assertTrue(sync.pull())
            assertEquals(2, spotify.pageRequests)
            val searches = youtube.calls.size

            assertTrue(sync.pull())
            assertEquals(3, spotify.pageRequests)
            assertEquals(searches, youtube.calls.size)

            spotify.like(61)
            assertTrue(sync.pull())
            assertEquals(5, spotify.pageRequests)
            assertTrue("yt61" in songs)

            assertTrue(sync.pull(force = true))
            assertEquals(7, spotify.pageRequests)

            // YouTube is unreachable: the track stays to be looked up, so the next run reads the list again.
            youtube.failing += "Song 62"
            spotify.like(62)
            assertTrue(sync.pull())
            assertFalse("yt62" in songs)
            youtube.failing.clear()
            val pages = spotify.pageRequests
            assertTrue(sync.pull())
            assertEquals(pages + 2, spotify.pageRequests)
            assertTrue("yt62" in songs)
        }

    @Test
    fun cachedLikedSongsFollowALikeAndUnlikeWithoutBeingReadAgain() =
        withApp { sync ->
            val local = local as FakeLocal
            spotify.like(1, 2, 3)
            val pager = store.collection(SpotifyCollectionType.LIKED, "").tracks
            pager.loadAll()

            fun shown() =
                pager.state.value.items
                    .map { it.track?.id }
            val pages = spotify.pageRequests

            local.like(7)
            sync.record("yt7", liked = true)
            assertNull(sync.flush())
            assertEquals(listOf("sp7", "sp3", "sp2", "sp1"), shown())
            assertEquals(4, pager.state.value.total)
            assertEquals(setOf("sp7"), sync.likedIds.value)

            local.unlike(7)
            sync.record("yt7", liked = false)
            assertNull(sync.flush())
            assertEquals(listOf("sp3", "sp2", "sp1"), shown())
            assertEquals(3, pager.state.value.total)
            assertEquals(pages, spotify.pageRequests)
        }

    @Test
    fun switchesDecideTheDirection() =
        withApp { sync ->
            val local = local as FakeLocal
            settings.meldSpotifyLikesPull = false
            spotify.like(1)
            assertFalse(sync.pull())
            assertEquals(emptySet(), songs.keys)

            settings.meldSpotifyLikesPush = false
            local.like(2)
            sync.record("yt2", liked = true)
            assertNull(sync.flush())
            assertEquals(emptyList(), likes.outboxIds())
            assertEquals(emptyList(), spotify.added)
        }

    /** The real like state of the app, with upstream's YouTube refresh played by its own `replaceLikesFromRemote`. */
    @Test
    fun wiredToTheAppItCoexistsWithTheYouTubeLikeSync() =
        withFile { file ->
            val previous = Settings.sharedOverrideForTest()
            val app = App(file, this, local = SongLikeStateLikes)
            Settings.replaceSharedForTest(app.settings)
            try {
                SongLikeState.awaitPersistence()
                SongLikeState.refreshFromSettings()
                SongLikeState.replaceLikesFromRemote(emptyList())
                val spotify = app.spotify
                val sync = app.sync(app.background)

                fun votes(): Map<String, SongVote> = Json.decodeFromString(app.settings.pendingSongVotesJson)

                suspend fun syncNow() {
                    val pages = spotify.pageRequests
                    sync.syncNow()
                    awaitUntil { spotify.pageRequests > pages && !sync.status.value.syncing }
                }

                spotify.like(1, 2)
                sync.start()
                awaitUntil { SongLikeState.isLiked("yt1") && SongLikeState.isLiked("yt2") && !sync.status.value.syncing }
                // Liked because Spotify said so: a vote for YouTube is queued, and that is what upstream's refresh respects.
                assertEquals(SongVote.LIKE, votes()["yt1"])
                SongLikeState.replaceLikesFromRemote(emptyList())
                assertTrue(SongLikeState.isLiked("yt1"))
                assertTrue(SongLikeState.isLiked("yt2"))

                // YouTube has caught up and brings a like of its own, which Spotify has never heard of.
                SongLikeState.replaceLikesFromRemote(listOf(ytSong("yt1", "Song 1"), ytSong("yt9", "Song 9")))
                syncNow()
                assertTrue(SongLikeState.isLiked("yt1"))
                assertTrue(SongLikeState.isLiked("yt9"))
                assertEquals(emptyList(), spotify.added)

                // The heart in the app.
                SongLikeState.toggleLike(ytSong("yt7", "Song 7"))
                awaitUntil { spotify.added == listOf(uri(7)) }
                SongLikeState.toggleLike(ytSong("yt7", "Song 7"))
                awaitUntil { spotify.removed == listOf(uri(7)) }

                // Removed on Spotify: un-liked here, and that un-like is not taken for the user's.
                spotify.unlike(1)
                syncNow()
                assertFalse(SongLikeState.isLiked("yt1"))
                assertTrue(SongLikeState.isLiked("yt9"))
                delay(200)
                assertEquals(emptyList(), app.likes.outboxIds())
                assertEquals(listOf(uri(7)), spotify.removed)

                // To Spotify only.
                app.settings.meldSpotifyLikesPull = false
                spotify.like(20)
                sync.syncNow()
                SongLikeState.toggleLike(ytSong("yt8", "Song 8"))
                awaitUntil { spotify.added == listOf(uri(7), uri(8)) }
                assertFalse(SongLikeState.isLiked("yt20"))

                // From Spotify only.
                app.settings.meldSpotifyLikesPull = true
                app.settings.meldSpotifyLikesPush = false
                SongLikeState.toggleLike(ytSong("yt30", "Song 30"))
                syncNow()
                assertTrue(SongLikeState.isLiked("yt20"))
                assertTrue(SongLikeState.isLiked("yt30"))
                assertEquals(listOf(uri(7), uri(8)), spotify.added)
                assertEquals(emptyList(), app.likes.outboxIds())
            } finally {
                LocalVotes.listener = null
                app.close()
                SongLikeState.awaitPersistence()
                Settings.replaceSharedForTest(previous)
                if (previous != null) SongLikeState.refreshFromSettings()
            }
        }
}
