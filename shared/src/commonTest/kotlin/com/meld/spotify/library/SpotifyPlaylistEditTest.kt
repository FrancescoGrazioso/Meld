package com.meld.spotify.library

import com.meld.spotify.SpotifyAlbum
import com.meld.spotify.SpotifyArtist
import com.meld.spotify.SpotifyArtistOverview
import com.meld.spotify.SpotifyConnection
import com.meld.spotify.SpotifyConnectionState
import com.meld.spotify.SpotifyConnectionState.Connected
import com.meld.spotify.SpotifyConnectionState.Disconnected
import com.meld.spotify.SpotifyError
import com.meld.spotify.SpotifyFolder
import com.meld.spotify.SpotifyLibraryItem
import com.meld.spotify.SpotifyPage
import com.meld.spotify.SpotifyPlaylist
import com.meld.spotify.SpotifyPlaylistPage
import com.meld.spotify.SpotifyTrack
import com.meld.spotify.SpotifyTrackItem
import com.meld.spotify.playback.SpotifyPlayback
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ME = "me"

private class Account(
    initial: SpotifyConnectionState = Connected("Tester", null, ME),
) : SpotifyConnection {
    override val state = MutableStateFlow(initial)

    override fun login(spDc: String) = Unit

    override fun logout() = Unit

    override fun refresh() = Unit
}

private fun song(number: Int) = SpotifyTrack("t$number", "spotify:track:t$number", "Track $number", emptyList(), null, 1000, false)

private fun entry(
    uid: String,
    number: Int,
) = SpotifyTrackItem(uid, "spotify:track:t$number", null, song(number))

private fun playlist(
    id: String,
    name: String,
    owner: String,
) = SpotifyPlaylist(id, "spotify:playlist:$id", name, ownerId = owner, trackCount = 5)

/**
 * Spotify's side of the playlist p1, in which track 0 is twice. An edit changes [entries] unless [error] is set;
 * [gate] keeps the request in flight until it is completed.
 */
private class Server(
    private val pageSize: Int = 3,
) : SpotifyLibrary,
    SpotifyPlaylistRemote {
    var entries = listOf(entry("u0", 0), entry("u1", 1), entry("u2", 0), entry("u3", 3), entry("u4", 4))
    var name = "Mix"
    var description: String? = "Old words"
    var error: SpotifyError? = null
    var appliesBeforeFailing = false
    var gate: CompletableDeferred<Unit>? = null

    /** What Spotify lets the user do with p3, the playlist of someone else: its entries, its name. */
    var theirs: Pair<Boolean, Boolean>? = null
    var editableError: SpotifyError? = null
    val requests = mutableListOf<String>()
    var pageCalls = 0
    private var added = 0

    private suspend fun request(
        label: String,
        apply: () -> Unit,
    ) {
        requests += label
        gate?.await()
        if (error == null || appliesBeforeFailing) apply()
        error?.let { throw it }
    }

    override suspend fun add(
        playlistId: String,
        trackUri: String,
    ) = request("add:$playlistId:$trackUri") {
        if (playlistId == "p1") entries = entries + entry("new${added++}", trackUri.substringAfterLast('t').toInt())
    }

    override suspend fun remove(
        playlistId: String,
        uid: String,
    ) = request("remove:$uid") { entries = entries.filterNot { it.uid == uid } }

    override suspend fun move(
        playlistId: String,
        uid: String,
        beforeUid: String?,
    ) = request("move:$uid>$beforeUid") {
        val moving = entries.first { it.uid == uid }
        val rest = entries - moving
        val position = if (beforeUid == null) rest.size else rest.indexOfFirst { it.uid == beforeUid }
        entries = rest.subList(0, position) + moving + rest.subList(position, rest.size)
    }

    override suspend fun details(
        playlistId: String,
        name: String?,
        description: String?,
    ) = request("details:$name|$description") {
        name?.let { this.name = it }
        description?.let { this.description = it }
    }

    override suspend fun create(name: String): String {
        request("create:$name") {}
        return "new1"
    }

    override suspend fun delete(playlistId: String) = request("delete:$playlistId") {}

    override suspend fun follow(playlistId: String) = request("follow:$playlistId") {}

    override suspend fun setSaved(
        uri: String,
        saved: Boolean,
    ) = request("saved:$uri:$saved") {}

    override suspend fun editable(
        itemUri: String,
        folderUri: String?,
        offset: Int,
    ): SpotifyPage<SpotifyLibraryItem> {
        editableError?.let { throw it }
        val own = if (folderUri == null) listOf("p1", "p2") else listOf("p4")
        val all = if (folderUri == null && theirs?.first == true) own + "p3" else own
        val folder = SpotifyLibraryItem.Folder(SpotifyFolder("spotify:folder:f1", "Folder", 1)).takeIf { folderUri == null }
        return page(all.map { SpotifyLibraryItem.Playlist(playlist(it, it, "unused")) } + listOfNotNull(folder), offset)
    }

    private fun <T> page(
        all: List<T>,
        offset: Int,
    ): SpotifyPage<T> {
        val end = minOf(offset + pageSize, all.size)
        return SpotifyPage(all.subList(offset, end), all.size, end.takeIf { it < all.size })
    }

    override suspend fun playlist(
        id: String,
        offset: Int,
    ): SpotifyPlaylistPage {
        pageCalls++
        val owner = if (id == "p3") "someone" else ME
        val allowed = theirs.takeIf { id == "p3" }
        return SpotifyPlaylistPage(
            SpotifyPlaylist(
                id,
                "spotify:playlist:$id",
                name,
                description,
                ownerId = owner,
                trackCount = entries.size,
                canEditItems = allowed?.first,
                canEditDetails = allowed?.second,
            ),
            page(entries, offset),
        )
    }

    override suspend fun playlists(
        folderUri: String?,
        offset: Int,
    ): SpotifyPage<SpotifyLibraryItem> =
        page(
            if (folderUri == null) {
                listOf(
                    SpotifyLibraryItem.Playlist(playlist("p1", name, ME)),
                    SpotifyLibraryItem.Playlist(playlist("p2", "Evening", ME)),
                    SpotifyLibraryItem.Playlist(playlist("p3", "Theirs", "someone")),
                    SpotifyLibraryItem.Folder(SpotifyFolder("spotify:folder:f1", "Folder", 1)),
                )
            } else {
                listOf(SpotifyLibraryItem.Playlist(playlist("p4", "Nested road", ME)))
            },
            offset,
        )

    override suspend fun albums(offset: Int): SpotifyPage<SpotifyAlbum> = error("unused")

    override suspend fun artists(offset: Int): SpotifyPage<SpotifyArtist> = error("unused")

    override suspend fun likedSongs(offset: Int) = page(entries, offset)

    override suspend fun album(id: String): SpotifyAlbum = error("unused")

    override suspend fun artist(id: String): SpotifyArtistOverview = error("unused")
}

private class Feed : SpotifyPlayback {
    var more: (suspend () -> List<SpotifyTrack>?)? = null

    override fun play(
        tracks: List<SpotifyTrack>,
        startIndex: Int,
        title: String?,
        shuffle: Boolean,
        more: (suspend () -> List<SpotifyTrack>?)?,
    ) {
        this.more = more
    }

    override fun playNext(tracks: List<SpotifyTrack>) = Unit

    override fun addToQueue(tracks: List<SpotifyTrack>) = Unit
}

@OptIn(ExperimentalCoroutinesApi::class)
private class Edits(
    test: TestScope,
    val server: Server = Server(),
    val account: Account = Account(),
) {
    val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(test.testScheduler))
    val store = SpotifyLibraryStore(server, account, scope)
    val editor = SpotifyPlaylistEditor(server, server, store, scope)
    val feed = Feed()
    var lookups = 0
    var onSpotify: suspend (SongItem) -> SpotifyTrack? = { song(9) }
    val picker =
        SpotifyPlaylistPicker(
            store,
            editor,
            server,
            account,
            {
                lookups++
                onSpotify(it)
            },
            scope,
        )

    fun screen(
        id: String = "p1",
        type: SpotifyCollectionType = SpotifyCollectionType.PLAYLIST,
        scope: CoroutineScope = this.scope,
    ) = SpotifyTracksViewModel(scope, store, account, feed, type, id, editor)

    /** A screen with every page of the playlist loaded. */
    fun loaded(): SpotifyTracksViewModel = screen().also { it.loadAll() }

    val picked get() = picker.state.value
    val SpotifyTracksViewModel.info get() = state.value.info
    val SpotifyTracksViewModel.tracks get() = state.value.tracks
    val SpotifyTracksViewModel.items get() = tracks.items
    val SpotifyTracksViewModel.failure get() = state.value.editFailure
    val SpotifyTracksViewModel.uids get() = items.map { it.uid }

    /** Lets the request that is waiting at the gate, and the edits queued behind it, run to their end. */
    suspend fun release() {
        server.gate?.complete(Unit)
        repeat(20) { yield() }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SpotifyPlaylistEditTest {
    private fun test(
        server: Server = Server(),
        body: suspend Edits.() -> Unit,
    ) = runTest(UnconfinedTestDispatcher()) {
        val edits = Edits(this, server)
        edits.body()
        edits.scope.cancel()
    }

    @Test
    fun onlyTheOwnerMayEdit() =
        test {
            val me = Connected("Tester", null, ME)
            assertTrue(canEditPlaylist(ME, me))
            assertFalse(canEditPlaylist("someone", me))
            assertFalse(canEditPlaylist(null, me))
            // The profile has not loaded yet, so nobody knows whose playlists these are.
            assertFalse(canEditPlaylist(ME, Connected("Tester", null)))
            assertFalse(canEditPlaylist(ME, Disconnected))

            assertTrue(loaded().state.value.canEdit)
            assertFalse(screen("p3").state.value.canEdit)
            assertFalse(screen("", SpotifyCollectionType.LIKED).state.value.canEdit)
            // Without an editor there is nothing to offer.
            assertFalse(SpotifyTracksViewModel(scope, store, account, feed, SpotifyCollectionType.PLAYLIST, "p1").state.value.canEdit)
        }

    @Test
    fun aPlaylistOfSomeoneElseIsEditedAsFarAsSpotifyAllows() =
        test(Server().apply { theirs = true to false }) {
            val contributed = screen("p3").state.value
            assertTrue(contributed.canEdit)
            assertFalse(contributed.canEditDetails)
            // Where Spotify says nothing, the owner may do both.
            val own = screen("p1").state.value
            assertTrue(own.canEdit)
            assertTrue(own.canEditDetails)

            picker.open(song(3))
            assertEquals(listOf("p1", "p2", "p3", "p4"), picked?.playlists?.map { it.id })
            // Spotify not saying which playlists take the song leaves the user's own.
            server.editableError = SpotifyError.Changed("editablePlaylists: HTTP 400")
            picker.open(song(3))
            assertEquals(listOf("p1", "p2", "p4"), picked?.playlists?.map { it.id })
            assertNull(picked?.error)
        }

    @Test
    fun aNewPlaylistIsNamedAfterTheSongAndGetsIt() =
        test {
            picker.open(song(3))
            picker.addToNew()
            release()
            assertEquals(listOf("create:Track 3", "add:new1:spotify:track:t3"), server.requests)
            val created = SpotifyPlaylist("new1", "spotify:playlist:new1", "Track 3", ownerId = ME, trackCount = 0)
            assertEquals(created, picked?.playlists?.first())
            assertEquals(setOf("new1"), picked?.added)
            assertNull(picked?.adding)

            // A playlist Spotify did not make gets nothing added to it.
            server.error = SpotifyError.Forbidden("createPlaylist")
            picker.addToNew()
            release()
            assertIs<SpotifyError.Forbidden>(picked?.error)
            assertEquals(3, server.requests.size)
            assertEquals(setOf("new1"), picked?.added)
        }

    @Test
    fun removingOneCopyOfATrackShowsAtOnceAndOutlivesTheScreen() =
        test {
            val shown = CoroutineScope(scope.coroutineContext + SupervisorJob())
            val screen = screen(scope = shown).also { it.loadAll() }
            val pages = server.pageCalls
            server.gate = CompletableDeferred()

            // The second copy of track 0, not the first.
            screen.remove(screen.items[2])
            assertEquals(listOf("u0", "u1", "u3", "u4"), screen.uids)
            assertEquals(4, screen.info?.trackCount)
            assertEquals(4, screen.tracks.total)
            assertEquals(5, server.entries.size)

            shown.cancel()
            release()
            assertEquals(listOf("remove:u2"), server.requests)
            assertEquals(listOf("u0", "u1", "u3", "u4"), server.entries.map { it.uid })

            // Reopened: the edit is there and Spotify was not asked again.
            val back = screen()
            assertEquals(listOf("u0", "u1", "u3", "u4"), back.uids)
            assertNull(back.failure)
            assertEquals(pages, server.pageCalls)
        }

    @Test
    fun anEditSpotifyRefusesIsUndoneReportedAndCanBeRetried() =
        test {
            val screen = loaded()
            server.error = SpotifyError.Forbidden("removeFromPlaylist")
            screen.remove(screen.items[1])
            release()
            assertEquals(listOf("u0", "u1", "u2", "u3", "u4"), screen.uids)
            assertEquals(5, screen.info?.trackCount)
            assertIs<SpotifyError.Forbidden>(screen.failure)

            server.error = null
            screen.retryEdit()
            release()
            assertNull(screen.failure)
            assertEquals(listOf("u0", "u2", "u3", "u4"), screen.uids)
            assertEquals(screen.uids, server.entries.map { it.uid })
        }

    @Test
    fun offlineEditsFailVisiblyAndNothingIsKeptForLater() =
        test {
            val screen = loaded()
            server.gate = CompletableDeferred()
            server.error = SpotifyError.Offline(RuntimeException("no route to host"))
            screen.remove(screen.items[0])
            screen.editDetails("Renamed", "Old words")
            release()
            assertEquals(listOf("u0", "u1", "u2", "u3", "u4"), screen.uids)
            assertEquals("Mix", screen.info?.title)
            assertIs<SpotifyError.Offline>(screen.failure)
            // The rename was made on top of the removal Spotify never took, so it was not sent.
            assertEquals(listOf("remove:u0"), server.requests)

            // Back online, nothing goes out by itself.
            server.error = null
            release()
            assertEquals(listOf("remove:u0"), server.requests)
            screen.dismissEditFailure()
            assertNull(screen.failure)
        }

    @Test
    fun anEditThatMayHaveReachedSpotifyIsCheckedAgainstIt() =
        test {
            val screen = loaded()
            // The connection drops after Spotify has applied the removal.
            server.error = SpotifyError.Offline(RuntimeException("connection reset"))
            server.appliesBeforeFailing = true
            screen.remove(screen.items[4])
            release()
            assertIs<SpotifyError.Offline>(screen.failure)
            assertEquals(server.entries.map { it.uid }, screen.uids)
            assertEquals(listOf("u0", "u1", "u2", "u3"), screen.uids)
        }

    @Test
    fun movingTranslatesTheDropIntoTheEntryItLandsBefore() =
        test {
            val screen = loaded()
            assertTrue(screen.move("u4", "u1"))
            assertEquals(listOf("u0", "u4", "u1", "u2", "u3"), screen.uids)
            assertTrue(screen.move("u0", null))
            assertEquals(listOf("u4", "u1", "u2", "u3", "u0"), screen.uids)
            // The first copy of track 0 is now last, the second has not moved.
            assertTrue(screen.move("u2", "u4"))
            release()
            assertEquals(listOf("move:u4>u1", "move:u0>null", "move:u2>u4"), server.requests)
            assertEquals(listOf("u2", "u4", "u1", "u3", "u0"), screen.uids)
            assertEquals(screen.uids, server.entries.map { it.uid })
            assertEquals(5, screen.tracks.total)
        }

    @Test
    fun aPartlyLoadedPlaylistIsNotReorderedUntilItIsWhole() =
        test {
            val screen = screen()
            assertEquals(listOf("u0", "u1", "u2"), screen.uids)
            assertFalse(screen.move("u2", "u0"))
            assertEquals(listOf("u0", "u1", "u2"), screen.uids)
            assertTrue(server.requests.isEmpty())

            screen.loadAll()
            assertTrue(screen.tracks.complete)
            assertTrue(screen.move("u4", "u0"))
            release()
            assertEquals(listOf("u4", "u0", "u1", "u2", "u3"), server.entries.map { it.uid })
        }

    @Test
    fun renamingSendsOnlyWhatChangedAndShowsInTheLibraryToo() =
        test {
            val screen = loaded()
            val library = store.playlists(null).also { it.loadAll() }

            fun libraryName() =
                library.state.value.items
                    .filterIsInstance<SpotifyLibraryItem.Playlist>()
                    .first { it.playlist.id == "p1" }
                    .playlist.name

            screen.editDetails("  Road trip ", "Old words")
            release()
            assertEquals(listOf("details:Road trip|null"), server.requests)
            assertEquals("Road trip", screen.info?.title)
            assertEquals("Road trip", libraryName())

            screen.editDetails("Road trip", "")
            screen.editDetails(" ", "")
            screen.editDetails("Road trip", "")
            release()
            // An empty description is a change; a blank name and an unchanged form are not.
            assertEquals(listOf("details:Road trip|null", "details:null|"), server.requests)
            assertEquals("", screen.info?.description)

            server.error = SpotifyError.Unavailable(503)
            screen.editDetails("Broken", "")
            release()
            assertEquals("Road trip", screen.info?.title)
            assertEquals("Road trip", libraryName())
            assertIs<SpotifyError.Unavailable>(screen.failure)
        }

    @Test
    fun editsMadeWhileOneIsInFlightGoOutInOrderOnTopOfIt() =
        test {
            val screen = loaded()
            server.gate = CompletableDeferred()
            screen.remove(screen.items[1])
            assertTrue(screen.move("u4", "u0"))
            screen.editDetails("Later", "Old words")
            // All three show at once, each on top of the one before.
            assertEquals(listOf("u4", "u0", "u2", "u3"), screen.uids)
            assertEquals("Later", screen.info?.title)
            yield()
            assertEquals(listOf("remove:u1"), server.requests)

            release()
            assertEquals(listOf("remove:u1", "move:u4>u0", "details:Later|null"), server.requests)
            assertEquals(screen.uids, server.entries.map { it.uid })
            assertEquals("Later", server.name)
            assertNull(screen.failure)
        }

    @Test
    fun whenAnEditFailsTheOnesMadeOnTopOfItAreUndoneWithItAndNotSent() =
        test {
            val screen = loaded()
            server.gate = CompletableDeferred()
            screen.remove(screen.items[1])
            screen.remove(screen.items[2])
            assertEquals(listOf("u0", "u2", "u4"), screen.uids)
            server.error = SpotifyError.RateLimited(30)
            release()
            assertEquals(listOf("remove:u1"), server.requests)
            assertEquals(listOf("u0", "u1", "u2", "u3", "u4"), screen.uids)
            assertEquals(5, screen.info?.trackCount)
            assertIs<SpotifyError.RateLimited>(screen.failure)

            // Retry makes both again, in their order.
            server.error = null
            screen.retryEdit()
            release()
            assertEquals(listOf("remove:u1", "remove:u1", "remove:u3"), server.requests)
            assertEquals(listOf("u0", "u2", "u4"), screen.uids)
            assertEquals(screen.uids, server.entries.map { it.uid })
        }

    @Test
    fun noPageIsReadWhileAnEditIsOnItsWay() =
        test {
            val screen = screen()
            assertEquals(1, server.pageCalls)
            server.gate = CompletableDeferred()
            screen.remove(screen.items[1])
            screen.loadMore()
            yield()
            // Read now, the next page would repeat or skip an entry depending on whether Spotify has removed u1 yet.
            assertEquals(1, server.pageCalls)

            release()
            assertEquals(2, server.pageCalls)
            assertEquals(listOf("u0", "u2", "u3", "u4"), screen.uids)
            assertEquals(screen.uids, server.entries.map { it.uid })
            assertTrue(screen.tracks.complete)
        }

    @Test
    fun anAddedTrackShowsAtTheEndAndGetsItsUidFromSpotify() =
        test {
            val screen = loaded()
            val pages = server.pageCalls
            server.gate = CompletableDeferred()
            // Track 0 a third time: Spotify takes a track the playlist already has.
            val result = editor.add("p1", song(0))
            assertEquals(listOf("u0", "u1", "u2", "u3", "u4", null), screen.uids)
            assertEquals(6, screen.info?.trackCount)
            release()
            assertNull(result.await())
            // One read of the end of the list, for the uid that makes the new entry editable.
            assertEquals(pages + 1, server.pageCalls)
            assertEquals(listOf("u0", "u1", "u2", "u3", "u4", "new0"), screen.uids)

            screen.remove(screen.items.last())
            release()
            assertEquals(listOf("u0", "u1", "u2", "u3", "u4"), server.entries.map { it.uid })

            server.error = SpotifyError.Forbidden("addToPlaylist")
            assertIs<SpotifyError.Forbidden>(editor.add("p1", song(7)).await())
            assertEquals(listOf("u0", "u1", "u2", "u3", "u4"), screen.uids)
            assertEquals(5, screen.info?.trackCount)
        }

    @Test
    fun aTrackAddedBelowWhatIsLoadedArrivesWithItsPage() =
        test {
            val screen = screen()
            assertNull(editor.add("p1", song(8)).await())
            assertEquals(listOf("u0", "u1", "u2"), screen.uids)
            assertEquals(6, screen.tracks.total)
            screen.loadAll()
            assertEquals(listOf("u0", "u1", "u2", "u3", "u4", "new0"), screen.uids)

            // A playlist no screen has opened is only told to Spotify.
            assertNull(editor.add("p2", song(8)).await())
            assertNull(store.cachedPlaylist("p2"))
            assertEquals("add:p2:spotify:track:t8", server.requests.last())
        }

    @Test
    fun thePickerOffersEditablePlaylistsFromEveryFolderAndSearchesThem() =
        test {
            loaded()
            picker.open(song(3))
            val state = picked!!
            assertFalse(state.loading)
            assertEquals(listOf("p1", "p2", "p4"), state.playlists.map { it.id })
            // Only known of the playlist that has been opened.
            assertEquals(setOf("p1"), state.containing)
            assertEquals(0, lookups)

            picker.setQuery(" nest ")
            assertEquals(listOf("p4"), picked?.shown?.map { it.id })
            picker.setQuery("")

            picker.add(state.playlists[1])
            release()
            assertEquals(setOf("p2"), picked?.added)
            assertEquals(listOf("add:p2:spotify:track:t3"), server.requests)

            server.error = SpotifyError.Offline(RuntimeException("no route to host"))
            picker.add(state.playlists[0])
            release()
            assertIs<SpotifyError.Offline>(picked?.error)
            assertEquals(setOf("p2"), picked?.added)
            // Reported in the dialog, so not again on the playlist's screen.
            assertTrue(editor.failures.value.isEmpty())

            picker.close()
            assertNull(picked)
        }

    @Test
    fun aYouTubeSongIsAddedAsTheTrackTheLookupFoundOrNotAtAll() =
        test {
            val youtube = SongItem("yt9", "Track 9", null, emptyList(), null, 1)
            picker.open(youtube)
            assertEquals(song(9), picked?.track)
            assertEquals(1, lookups)
            picker.add(picked!!.playlists.first())
            release()
            assertEquals(listOf("add:p1:spotify:track:t9"), server.requests)

            // No confident match: the user is told, and there is nothing to choose.
            onSpotify = { null }
            picker.open(youtube)
            assertTrue(picked!!.notFound)
            assertTrue(picked!!.playlists.isEmpty())

            // A file on the device is not on Spotify; it is not even looked up.
            picker.open(youtube.copy(isLocal = true))
            assertTrue(picked!!.notFound)
            assertEquals(2, lookups)

            onSpotify = { throw SpotifyError.Offline(RuntimeException("no route to host")) }
            picker.open(youtube)
            assertIs<SpotifyError.Offline>(picked?.error)
            onSpotify = { song(9) }
            picker.retry()
            assertNull(picked?.error)
            assertEquals(song(9), picked?.track)
            assertEquals(1, server.requests.size)
        }

    @Test
    fun theQueueKeepsBeingFedInOrderWhenItsPlaylistIsEdited() =
        test {
            val screen = screen()
            screen.play(0)
            val more = feed.more!!
            // Two entries that are already queued are removed: the feed goes on after the last one it handed over.
            screen.remove(screen.items[0])
            screen.remove(screen.items[0])
            release()
            assertEquals(listOf("t3", "t4"), more()?.map { it.id })
            assertNull(more())
        }

    @Test
    fun aWholeListIsAddedInItsOrderAndTheOpenPlaylistReadsItAgain() =
        test {
            val screen = loaded()
            picker.openAll("Road trip") { listOf(song(7), song(8)) }
            assertEquals(2, picked?.tracks?.size)
            // Which playlists have a whole list already is not said.
            assertEquals(emptySet(), picked?.containing)
            picker.add(picked!!.playlists.first { it.id == "p1" })
            release()
            assertEquals(listOf("add:p1:spotify:track:t7", "add:p1:spotify:track:t8"), server.requests)
            assertEquals(setOf("p1"), picked?.added)
            with(screen) { assertEquals(listOf("t7", "t8"), items.takeLast(2).map { it.track?.id }) }

            // A new playlist for a list takes the name of the list.
            server.requests.clear()
            picker.addToNew()
            release()
            assertEquals("create:Road trip", server.requests.first())
            assertEquals(3, server.requests.size)
        }

    @Test
    fun aListThatCannotBeLoadedIsReportedInThePickerAndRetried() =
        test {
            var fail = true
            picker.openAll("Road trip") { if (fail) throw SpotifyError.Offline(RuntimeException("no route to host")) else listOf(song(7), song(8)) }
            assertIs<SpotifyError.Offline>(picked?.error)
            fail = false
            picker.retry()
            assertNull(picked?.error)
            assertEquals(2, picked?.tracks?.size)
        }
}
