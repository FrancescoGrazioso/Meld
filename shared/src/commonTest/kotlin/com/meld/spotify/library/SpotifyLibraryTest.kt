package com.meld.spotify.library

import com.meld.spotify.SpotifyAlbum
import com.meld.spotify.SpotifyArtist
import com.meld.spotify.SpotifyArtistOverview
import com.meld.spotify.SpotifyConnection
import com.meld.spotify.SpotifyConnectionState
import com.meld.spotify.SpotifyConnectionState.Connected
import com.meld.spotify.SpotifyConnectionState.Disconnected
import com.meld.spotify.SpotifyConnectionState.Expired
import com.meld.spotify.SpotifyError
import com.meld.spotify.SpotifyFolder
import com.meld.spotify.SpotifyLibraryItem
import com.meld.spotify.SpotifyPage
import com.meld.spotify.SpotifyPlaylist
import com.meld.spotify.SpotifyPlaylistPage
import com.meld.spotify.SpotifyTrack
import com.meld.spotify.SpotifyTrackItem
import com.meld.spotify.playback.SpotifyPlayback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeConnection(
    initial: SpotifyConnectionState = Connected("Tester", null),
) : SpotifyConnection {
    override val state = MutableStateFlow(initial)

    override fun login(spDc: String) = Unit

    override fun logout() = Unit

    override fun refresh() = Unit
}

private fun track(
    number: Int,
    playable: Boolean? = null,
) = SpotifyTrack("t$number", "spotify:track:t$number", "Track $number", emptyList(), null, 1000, false, playable = playable)

private fun entry(track: SpotifyTrack?) = SpotifyTrackItem(null, track?.uri, null, track)

private class FakeLibrary(
    private val pageSize: Int = 2,
) : SpotifyLibrary {
    var calls = 0
    var failAt: Int? = null
    var names = listOf("Delta", "alpha", "Charlie", "bravo", "Echo")
    var tracks = List(5) { entry(track(it)) }

    private fun <T> page(
        all: List<T>,
        offset: Int,
    ): SpotifyPage<T> {
        calls++
        if (offset == failAt) throw SpotifyError.Offline(RuntimeException("socket closed"))
        val end = minOf(offset + pageSize, all.size)
        return SpotifyPage(all.subList(offset, end), all.size, end.takeIf { it < all.size })
    }

    override suspend fun playlists(
        folderUri: String?,
        offset: Int,
    ): SpotifyPage<SpotifyLibraryItem> {
        val items =
            names.map { SpotifyLibraryItem.Playlist(SpotifyPlaylist(it, "spotify:playlist:$it", it, ownerName = "Owner of $it")) } +
                SpotifyLibraryItem.Folder(SpotifyFolder("spotify:folder:f1", "Folder", 3))
        return page(if (folderUri == null) items else items.take(1), offset)
    }

    override suspend fun albums(offset: Int) = page(listOf(SpotifyAlbum("al1", "spotify:album:al1", "Album")), offset)

    override suspend fun artists(offset: Int) = page(listOf(SpotifyArtist("a1", "spotify:artist:a1", "Artist")), offset)

    override suspend fun likedSongs(offset: Int) = page(tracks, offset)

    override suspend fun playlist(
        id: String,
        offset: Int,
    ) = SpotifyPlaylistPage(SpotifyPlaylist(id, "spotify:playlist:$id", "Mix", ownerName = "Tester", trackCount = tracks.size), page(tracks, offset))

    override suspend fun album(id: String): SpotifyAlbum {
        calls++
        return SpotifyAlbum(id, "spotify:album:$id", "Album", tracks = tracks.mapNotNull { it.track })
    }

    override suspend fun artist(id: String): SpotifyArtistOverview {
        calls++
        return SpotifyArtistOverview(SpotifyArtist(id, "spotify:artist:$id", "Artist"), tracks.mapNotNull { it.track }, emptyList())
    }
}

private class FakePlayback : SpotifyPlayback {
    var tracks = emptyList<SpotifyTrack>()
    var startIndex = -1
    var shuffle = false
    var more: (suspend () -> List<SpotifyTrack>)? = null
    val queued = mutableListOf<String>()

    override fun play(
        tracks: List<SpotifyTrack>,
        startIndex: Int,
        title: String?,
        shuffle: Boolean,
        more: (suspend () -> List<SpotifyTrack>)?,
    ) {
        this.tracks = tracks
        this.startIndex = startIndex
        this.shuffle = shuffle
        this.more = more
    }

    override fun playNext(tracks: List<SpotifyTrack>) {
        queued += tracks.map { "next:${it.id}" }
    }

    override fun addToQueue(tracks: List<SpotifyTrack>) {
        queued += tracks.map { "queue:${it.id}" }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class Fixture(
    test: TestScope,
    val library: FakeLibrary = FakeLibrary(),
    val connection: FakeConnection = FakeConnection(),
) {
    val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(test.testScheduler))
    val store = SpotifyLibraryStore(library, connection, scope)
    val playback = FakePlayback()

    fun libraryScreen(folderUri: String? = null) = SpotifyLibraryViewModel(scope, store, connection, folderUri)

    fun tracksScreen(
        type: SpotifyCollectionType = SpotifyCollectionType.PLAYLIST,
        playback: SpotifyPlayback? = this.playback,
    ) = SpotifyTracksViewModel(scope, store, connection, playback, type, "p1")
}

@OptIn(ExperimentalCoroutinesApi::class)
class SpotifyLibraryTest {
    private fun test(body: suspend Fixture.() -> Unit) =
        runTest(UnconfinedTestDispatcher()) {
            val fixture = Fixture(this)
            fixture.body()
            fixture.scope.cancel()
        }

    private val List<SpotifyTrack>.ids get() = map { it.id }

    @Test
    fun libraryIsReadToTheLastPage() =
        test {
            val state = libraryScreen().state.value
            assertEquals(6, state.entries.size)
            assertTrue(state.list.complete)
            assertEquals(3, library.calls)
            assertEquals(SpotifyLibraryEntryKind.FOLDER, state.entries.last().kind)
        }

    @Test
    fun failedPageKeepsWhatWasLoadedAndRetryContinues() =
        test {
            library.failAt = 2
            val screen = libraryScreen()
            assertEquals(2, screen.state.value.entries.size)
            assertIs<SpotifyError.Offline>(screen.state.value.list.error)

            library.failAt = null
            screen.retry()
            assertEquals(6, screen.state.value.entries.size)
            assertNull(screen.state.value.list.error)
        }

    @Test
    fun searchAndSortWorkOnTheLoadedItems() =
        test {
            val screen = libraryScreen()
            assertEquals("Delta", screen.state.value.entries.first().name)

            screen.setSort(byName = true, descending = false)
            assertEquals(listOf("alpha", "bravo", "Charlie", "Delta", "Echo", "Folder"), screen.state.value.entries.map { it.name })
            screen.setSort(byName = true, descending = true)
            assertEquals("Folder", screen.state.value.entries.first().name)

            screen.setQuery(" CHAR ")
            assertEquals(listOf("Charlie"), screen.state.value.entries.map { it.name })
            screen.setQuery("owner of e")
            assertEquals(listOf("Echo"), screen.state.value.entries.map { it.name })
            assertEquals(6, screen.state.value.list.items.size)
        }

    @Test
    fun tabsAndFoldersHaveTheirOwnLists() =
        test {
            val screen = libraryScreen()
            screen.setTab(SpotifyLibraryTab.ALBUMS)
            assertEquals(listOf(SpotifyLibraryEntryKind.ALBUM), screen.state.value.entries.map { it.kind })
            screen.setTab(SpotifyLibraryTab.ARTISTS)
            assertEquals(listOf("Artist"), screen.state.value.entries.map { it.name })
            assertEquals(listOf("Delta"), libraryScreen("spotify:folder:f1").state.value.entries.map { it.name })
        }

    @Test
    fun expiredSessionAsksNothingAndReconnectingLoads() =
        runTest(UnconfinedTestDispatcher()) {
            val fixture = Fixture(this, connection = FakeConnection(Expired("Tester")))
            val library = fixture.libraryScreen()
            val tracks = fixture.tracksScreen()
            assertIs<Expired>(library.state.value.session)
            assertIs<Expired>(tracks.state.value.session)
            assertEquals(0, fixture.library.calls)

            fixture.connection.state.value = Connected("Tester", null)
            assertIs<Connected>(library.state.value.session)
            assertEquals(6, library.state.value.entries.size)
            assertEquals(2, tracks.state.value.tracks.items.size)
            fixture.scope.cancel()
        }

    @Test
    fun reopeningReusesTheSessionCacheUntilTheAccountChanges() =
        test {
            libraryScreen()
            tracksScreen()
            val calls = library.calls
            assertEquals(6, libraryScreen().state.value.entries.size)
            assertEquals(2, tracksScreen().state.value.tracks.items.size)
            assertEquals(calls, library.calls)

            connection.state.value = Disconnected
            connection.state.value = Connected("Someone else", null)
            assertEquals(6, libraryScreen().state.value.entries.size)
            assertTrue(library.calls > calls)
        }

    @Test
    fun tracksLoadPageByPageAndAFailedPageIsRetried() =
        test {
            val screen = tracksScreen()
            assertEquals(2, screen.state.value.tracks.items.size)
            assertEquals("Mix", screen.state.value.info?.title)
            assertEquals(5, screen.state.value.info?.trackCount)

            library.failAt = 2
            screen.loadMore()
            assertEquals(2, screen.state.value.tracks.items.size)
            assertIs<SpotifyError.Offline>(screen.state.value.tracks.error)

            library.failAt = null
            screen.loadMore()
            screen.loadMore()
            assertEquals(5, screen.state.value.tracks.items.size)
            assertTrue(screen.state.value.tracks.complete)
            assertNull(screen.state.value.tracks.error)
        }

    @Test
    fun playStartsAtTheTappedTrackAndMoreFollowsTheSource() =
        runTest(UnconfinedTestDispatcher()) {
            val library = FakeLibrary(pageSize = 4)
            library.tracks =
                listOf(entry(track(0)), entry(null), entry(track(2, playable = false)), entry(track(3))) +
                listOf(entry(null), entry(null), entry(null), entry(null)) +
                listOf(entry(track(8)), entry(track(9)))
            val fixture = Fixture(this, library)
            val screen = fixture.tracksScreen()

            screen.play(itemIndex = 1)
            screen.play(itemIndex = 2)
            assertEquals(-1, fixture.playback.startIndex)

            screen.play(itemIndex = 3, title = "Mix")
            assertEquals(listOf("t0", "t3"), fixture.playback.tracks.ids)
            assertEquals(1, fixture.playback.startIndex)

            val more = fixture.playback.more!!
            library.failAt = 8
            assertEquals(emptyList(), more())
            library.failAt = null
            // The page of unplayable entries is skipped rather than taken for the end.
            assertEquals(listOf("t8", "t9"), more().ids)
            assertEquals(emptyList(), more())
            assertEquals(10, screen.state.value.tracks.items.size)
            fixture.scope.cancel()
        }

    @Test
    fun shuffleLoadsEverythingFirst() =
        test {
            val screen = tracksScreen()
            screen.shuffle()
            assertEquals(5, playback.tracks.size)
            assertTrue(playback.shuffle)
            assertEquals(false, screen.state.value.preparingShuffle)

            screen.playNext(track(1))
            screen.addToQueue(track(2))
            assertEquals(listOf("next:t1", "queue:t2"), playback.queued)
        }

    @Test
    fun albumsAndArtistsAreTrackListsTooAndWorkWithoutPlayback() =
        test {
            val album = tracksScreen(SpotifyCollectionType.ALBUM, playback = null)
            assertEquals(5, album.state.value.tracks.items.size)
            assertTrue(album.state.value.tracks.complete)
            assertEquals(false, album.canPlay)
            album.play(0)
            album.shuffle()

            val artist = tracksScreen(SpotifyCollectionType.ARTIST)
            assertEquals("Artist", artist.state.value.info?.title)
            artist.play()
            assertEquals(0, playback.startIndex)
            assertEquals(5, playback.tracks.size)
            assertEquals(emptyList(), playback.more!!())
        }
}
