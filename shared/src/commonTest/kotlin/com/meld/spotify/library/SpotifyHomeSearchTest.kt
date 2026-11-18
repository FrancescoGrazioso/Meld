package com.meld.spotify.library

import com.meld.spotify.SpotifyAlbum
import com.meld.spotify.SpotifyArtist
import com.meld.spotify.SpotifyConnection
import com.meld.spotify.SpotifyConnectionState
import com.meld.spotify.SpotifyConnectionState.Connected
import com.meld.spotify.SpotifyConnectionState.Disconnected
import com.meld.spotify.SpotifyConnectionState.Expired
import com.meld.spotify.SpotifyError
import com.meld.spotify.SpotifyHomeFeed
import com.meld.spotify.SpotifyHomeItem
import com.meld.spotify.SpotifyHomeSection
import com.meld.spotify.SpotifyPage
import com.meld.spotify.SpotifyPlaylist
import com.meld.spotify.SpotifySearchResult
import com.meld.spotify.SpotifyTrack
import com.meld.spotify.playback.SpotifyPlayback
import kotlinx.coroutines.CompletableDeferred
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

private class Session(
    initial: SpotifyConnectionState = Connected("Tester", null),
) : SpotifyConnection {
    override val state = MutableStateFlow(initial)

    override fun login(spDc: String) = Unit

    override fun logout() = Unit

    override fun refresh() = Unit
}

private fun album(id: String) =
    SpotifyAlbum(id, "spotify:album:$id", "Album $id", artists = listOf(SpotifyArtist("ar", "spotify:artist:ar", "Band")))

private fun playlistItem(id: String) =
    SpotifyHomeItem.Playlist(SpotifyPlaylist(id, "spotify:playlist:$id", "Playlist $id"), null, null, null)

private fun section(
    title: String?,
    vararg items: SpotifyHomeItem,
) = SpotifyHomeSection("spotify:section:$title", title, "HomeGenericSectionData", items.size, items.toList())

@OptIn(ExperimentalCoroutinesApi::class)
private class Home(
    test: TestScope,
    val session: Session,
    switchedOn: Boolean,
) {
    val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(test.testScheduler))
    val enabled = MutableStateFlow(switchedOn)
    var feed = SpotifyHomeFeed("Good afternoon", listOf(section("Mixes", playlistItem("p1"))))
    var failure: Exception? = null
    var releases: () -> List<SpotifyAlbum> = { emptyList() }
    var asked = 0
    val home =
        SpotifyInHome(
            scope = scope,
            connection = session,
            enabled = enabled,
            home = {
                asked++
                failure?.let { throw it }
                feed
            },
            newReleases = { releases() },
        )
    val state get() = home.state.value
    val titles get() = state.shelves.map { it.title }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SpotifyInHomeTest {
    private fun test(
        session: Session = Session(),
        enabled: Boolean = true,
        body: suspend Home.() -> Unit,
    ) = runTest(UnconfinedTestDispatcher()) {
        val fixture = Home(this, session, enabled)
        fixture.body()
        fixture.scope.cancel()
    }

    @Test
    fun theFeedIsLoadedOnceAndKeptForTheSession() =
        test {
            assertEquals(listOf<String?>("Mixes"), titles)
            assertEquals(
                listOf("PLAYLIST:p1"),
                state.shelves
                    .single()
                    .entries
                    .map { it.key },
            )
            assertNull(state.error)

            // Switching it off hides the rows; switching it back on shows what was loaded, without asking again.
            enabled.value = false
            assertTrue(state.shelves.isEmpty())
            enabled.value = true
            assertEquals(listOf<String?>("Mixes"), titles)
            assertEquals(1, asked)
        }

    @Test
    fun switchedOffNothingIsAskedUntilItIsSwitchedOn() =
        test(enabled = false) {
            assertEquals(0, asked)
            assertTrue(state.shelves.isEmpty())
            enabled.value = true
            assertEquals(listOf<String?>("Mixes"), titles)
        }

    @Test
    fun aSectionWithoutATitleTakesTheGreetingAndARepeatedItemIsShownOnce() =
        test {
            feed = SpotifyHomeFeed("Good evening", listOf(section(null, playlistItem("p1"), playlistItem("p1"), playlistItem("p2"))))
            home.refresh()
            assertEquals(listOf<String?>("Good evening"), titles)
            assertEquals(
                listOf("PLAYLIST:p1", "PLAYLIST:p2"),
                state.shelves
                    .single()
                    .entries
                    .map { it.key },
            )
        }

    @Test
    fun refreshAsksAgainAndAFailureKeepsWhatWasShown() =
        test {
            feed = SpotifyHomeFeed(null, listOf(section("Fresh", playlistItem("p2"))))
            home.refresh()
            assertEquals(listOf<String?>("Fresh"), titles)
            assertEquals(2, asked)

            failure = SpotifyError.Offline(RuntimeException())
            home.refresh()
            assertEquals(listOf<String?>("Fresh"), titles)
            assertIs<SpotifyError.Offline>(state.error)

            failure = null
            home.refresh()
            assertNull(state.error)
            assertEquals(4, asked)
        }

    @Test
    fun aFailedFirstLoadLeavesOnlyTheErrorAndCanBeRetried() =
        test(enabled = false) {
            failure = IllegalStateException("not a Spotify error")
            enabled.value = true
            assertTrue(state.shelves.isEmpty())
            assertIs<SpotifyError.Changed>(state.error)

            failure = null
            home.refresh()
            assertEquals(listOf<String?>("Mixes"), titles)
            assertNull(state.error)
        }

    @Test
    fun anExpiredSessionDropsTheFeedAndASignInLoadsItAgain() =
        test {
            session.state.value = Expired("Tester")
            assertTrue(state.expired)
            assertTrue(state.shelves.isEmpty())

            session.state.value = Connected("Tester", null)
            assertEquals(false, state.expired)
            assertEquals(listOf<String?>("Mixes"), titles)
            assertEquals(2, asked)
        }

    @Test
    fun anExpiredSessionIsNotMentionedWhenSpotifyIsSwitchedOff() =
        test(Session(Expired("Tester")), enabled = false) {
            assertEquals(false, state.expired)
            assertEquals(0, asked)
        }

    @Test
    fun withoutAnAccountNothingIsAskedAndConnectingLoadsTheFeed() =
        test(Session(Disconnected)) {
            assertEquals(0, asked)
            assertTrue(state.shelves.isEmpty())
            assertEquals(false, state.expired)
            session.state.value = Connected("Tester", null)
            assertEquals(listOf<String?>("Mixes"), titles)
        }

    @Test
    fun newReleasesAreARowOfTheirOwnAndTheFeedIsShownWithoutThemWhenTheyFail() =
        test {
            releases = { listOf(album("a1"), album("a1"), album("a2")) }
            home.refresh()
            val shelf = state.shelves.last()
            assertEquals(SpotifyHomeShelf.NEW_RELEASES, shelf.key)
            assertEquals(listOf("ALBUM:a1", "ALBUM:a2"), shelf.entries.map { it.key })

            releases = { throw SpotifyError.Unavailable(503) }
            home.refresh()
            assertEquals(listOf<String?>("Mixes"), titles)
            assertNull(state.error)
        }
}

private fun song(
    id: String,
    playable: Boolean? = null,
) = SpotifyTrack(id, "spotify:track:$id", "Track $id", emptyList(), null, 1000, false, playable = playable)

private class Player : SpotifyPlayback {
    val calls = mutableListOf<String>()

    override fun play(
        tracks: List<SpotifyTrack>,
        startIndex: Int,
        title: String?,
        shuffle: Boolean,
        more: (suspend () -> List<SpotifyTrack>?)?,
    ) {
        calls += "play:${tracks.joinToString(",") { it.id }}@$startIndex:$title"
    }

    override fun playNext(tracks: List<SpotifyTrack>) {
        calls += "next:${tracks.joinToString(",") { it.id }}"
    }

    override fun addToQueue(tracks: List<SpotifyTrack>) {
        calls += "queue:${tracks.joinToString(",") { it.id }}"
    }
}

private const val PAGE = 2
private const val TRACK_TOTAL = 5

@OptIn(ExperimentalCoroutinesApi::class)
private class Search(
    test: TestScope,
    val session: Session,
) {
    val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(test.testScheduler))
    val asked = mutableListOf<String>()
    val held = mutableMapOf<String, CompletableDeferred<Unit>>()
    var failure: Exception? = null
    val player = Player()

    /** Five tracks in pages of two; one result of every other kind, all on the first page. */
    val search =
        SpotifyInSearch(scope, session, { query, offset ->
            asked += "$query@$offset"
            held[query]?.await()
            failure?.let { throw it }
            val tracks = (offset until minOf(offset + PAGE, TRACK_TOTAL)).map { song("$query$it") }
            val first = offset == 0

            fun <T> one(item: T) = SpotifyPage(listOfNotNull(item.takeIf { first }), 1, null)
            SpotifySearchResult(
                tracks = SpotifyPage(tracks, TRACK_TOTAL, (offset + PAGE).takeIf { it < TRACK_TOTAL }),
                albums = one(album("al")),
                artists = one(SpotifyArtist("ar", "spotify:artist:ar", "Band")),
                playlists = one(SpotifyPlaylist("pl", "spotify:playlist:pl", "Mix", ownerName = "Owner", trackCount = 3)),
            )
        }, player)
    val state get() = search.state.value
    val trackIds get() = state.tracks.map { it.id }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SpotifyInSearchTest {
    private fun test(
        session: Session = Session(),
        body: suspend Search.() -> Unit,
    ) = runTest(UnconfinedTestDispatcher()) {
        val fixture = Search(this, session)
        fixture.body()
        fixture.scope.cancel()
    }

    @Test
    fun anEmptyQueryAsksNothingAndClearsTheResults() =
        test {
            search.search("")
            search.search("   ")
            assertTrue(asked.isEmpty())

            search.search("daft")
            search.search("")
            assertTrue(state.isEmpty)
            assertEquals("", state.query)
            assertEquals(listOf("daft@0"), asked)
        }

    @Test
    fun resultsAreMappedPerKind() =
        test {
            search.search(" daft ")
            assertEquals("daft", state.query)
            assertEquals(false, state.searching)
            assertEquals(listOf("daft0", "daft1"), trackIds)
            assertEquals(listOf(SpotifyLibraryEntry(SpotifyLibraryEntryKind.ARTIST, "ar", "Band")), state.artists)
            assertEquals(listOf(SpotifyLibraryEntry(SpotifyLibraryEntryKind.ALBUM, "al", "Album al", detail = "Band")), state.albums)
            assertEquals(
                listOf(SpotifyLibraryEntry(SpotifyLibraryEntryKind.PLAYLIST, "pl", "Mix", detail = "Owner", count = 3)),
                state.entries(SpotifySearchKind.PLAYLISTS),
            )
            assertEquals(mapOf(SpotifySearchKind.TRACKS to 2), state.next)
        }

    @Test
    fun theQueryAlreadyShownIsNotAskedAgain() =
        test {
            search.search("daft")
            search.search("daft")
            assertEquals(listOf("daft@0"), asked)
        }

    @Test
    fun aNewerQueryCancelsTheOneUnderWay() =
        test {
            val slow = CompletableDeferred<Unit>()
            held["slow"] = slow
            search.search("slow")
            assertTrue(state.searching)

            search.search("fast")
            slow.complete(Unit)
            assertEquals("fast", state.query)
            assertEquals(listOf("fast0", "fast1"), trackIds)
            assertEquals(listOf("slow@0", "fast@0"), asked)
        }

    @Test
    fun moreOfAKindIsLoadedPageByPageUntilSpotifyHasNoMore() =
        test {
            search.search("daft")
            search.loadMore(SpotifySearchKind.TRACKS)
            assertEquals(listOf("daft0", "daft1", "daft2", "daft3"), trackIds)
            search.loadMore(SpotifySearchKind.TRACKS)
            assertEquals(5, state.tracks.size)
            assertTrue(state.next.isEmpty())
            // The other kinds came with the first answer and are not doubled by a page of tracks.
            assertEquals(1, state.albums.size)

            search.loadMore(SpotifySearchKind.TRACKS)
            search.loadMore(SpotifySearchKind.ALBUMS)
            assertEquals(listOf("daft@0", "daft@2", "daft@4"), asked)
        }

    @Test
    fun aFailedSearchCarriesTheSpotifyErrorAndIsRetried() =
        test {
            failure = SpotifyError.RateLimited(30)
            search.search("daft")
            assertIs<SpotifyError.RateLimited>(state.error)
            assertEquals(false, state.searching)
            assertTrue(state.isEmpty)

            failure = IllegalStateException("not a Spotify error")
            search.retry(null)
            assertIs<SpotifyError.Changed>(state.error)

            failure = null
            search.retry(null)
            assertNull(state.error)
            assertEquals(listOf("daft0", "daft1"), trackIds)
        }

    @Test
    fun aFailedPageKeepsTheResultsAndIsRetried() =
        test {
            search.search("daft")
            failure = SpotifyError.Offline(RuntimeException())
            search.loadMore(SpotifySearchKind.TRACKS)
            assertIs<SpotifyError.Offline>(state.error)
            assertEquals(false, state.loadingMore)
            assertEquals(listOf("daft0", "daft1"), trackIds)
            // Nothing more is asked while the failure is shown.
            search.loadMore(SpotifySearchKind.TRACKS)
            assertEquals(listOf("daft@0", "daft@2"), asked)

            failure = null
            search.retry(SpotifySearchKind.TRACKS)
            assertNull(state.error)
            assertEquals(4, state.tracks.size)
        }

    @Test
    fun withoutAConnectedAccountNothingIsAsked() =
        test(Session(Expired("Tester"))) {
            search.search("daft")
            assertTrue(asked.isEmpty())
            assertTrue(state.isEmpty)
        }

    @Test
    fun aTrackPlaysFollowedByTheRestOfTheListedTracks() =
        test {
            search.search("daft")
            val listed = listOf(song("a"), song("gone", playable = false), song("b"), song("c"))
            search.play(listed, 2)
            search.play(listed, 1)
            search.playNext(listed[0])
            search.addToQueue(listed[3])
            assertEquals(listOf("play:a,b,c@1:daft", "next:a", "queue:c"), player.calls)
        }
}
