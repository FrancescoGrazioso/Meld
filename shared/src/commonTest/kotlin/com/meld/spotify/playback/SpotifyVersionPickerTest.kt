package com.meld.spotify.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class SpotifyVersionPickerTest {
    private val dao = FakeMatchDao()
    private val youtube = FakeYouTube()

    private fun TestScope.resolver() = SpotifyTrackResolver(dao, youtube::search, youtube::songsById, backgroundScope, { 0 })

    @Test
    fun theUsersChoicePlaysFromThenOnAndCanBeTakenBack() =
        runTest(UnconfinedTestDispatcher()) {
            val resolver = resolver()
            val picker = SpotifyVersionPicker(resolver, backgroundScope)
            val live = ytSong("live7", "Song 7 (Live)")
            youtube.results["Song 7"] = listOf(ytSong("yt7", "Song 7"), live)

            picker.open(track(7))
            val shown = picker.state.value!!
            assertEquals(false, shown.loading)
            assertEquals("yt7", shown.currentId)
            assertEquals(false, shown.manual)
            assertEquals(listOf("yt7", "live7"), shown.candidates.map { it.id })

            picker.choose(live)
            assertEquals("live7", picker.state.value?.currentId)
            assertEquals(true, picker.state.value?.manual)
            assertEquals("live7", (resolver.resolve(track(7)) as SpotifyResolution.Matched).song.id)
            assertEquals(true, dao.get("sp7")?.isManualOverride)

            picker.useAutomatic()
            assertEquals("yt7", picker.state.value?.currentId)
            assertEquals(false, picker.state.value?.manual)
            assertEquals("yt7", (resolver.resolve(track(7)) as SpotifyResolution.Matched).song.id)

            picker.close()
            assertNull(picker.state.value)
        }

    @Test
    fun aTrackWithNoMatchStillOffersWhatYouTubeHas() =
        runTest(UnconfinedTestDispatcher()) {
            val resolver = resolver()
            val picker = SpotifyVersionPicker(resolver, backgroundScope)
            // Nothing the matcher accepts, but a cover the user may want.
            youtube.results["Song 8"] = listOf(ytSong("cover8", "Something Else Entirely"))
            picker.open(track(8))
            assertNull(picker.state.value?.currentId)
            assertEquals(listOf("cover8"), picker.state.value?.candidates?.map { it.id })
            picker.choose(picker.state.value!!.candidates.single())
            assertIs<SpotifyResolution.Matched>(resolver.resolve(track(8)))
        }

    @Test
    fun youTubeOutOfReachIsSaidAndCanBeRetried() =
        runTest(UnconfinedTestDispatcher()) {
            val picker = SpotifyVersionPicker(resolver(), backgroundScope)
            youtube.failing += "Song 9"
            picker.open(track(9))
            // A failed search is tried once more after a pause before it counts as out of reach.
            advanceTimeBy(5_000)
            assertEquals(true, picker.state.value?.unreachable)
            youtube.failing.clear()
            picker.retry()
            advanceTimeBy(5_000)
            assertEquals(false, picker.state.value?.unreachable)
            assertEquals("yt9", picker.state.value?.currentId)
        }

    @Test
    fun aVideoIdIsReadFromEveryUsualFormOfAYouTubeLinkAndFromNothingElse() {
        val id = "f2dP6C7JWhU"
        listOf(
            id,
            "https://www.youtube.com/watch?v=$id",
            "https://music.youtube.com/watch?v=$id&si=abc",
            "https://m.youtube.com/watch?feature=share&v=$id",
            "https://youtu.be/$id?t=12",
            "youtu.be/$id",
            "https://www.youtube.com/shorts/$id",
            "  https://www.youtube.com/embed/$id  ",
        ).forEach { assertEquals(id, youtubeVideoId(it), it) }
        listOf(
            "",
            "not a link",
            "https://open.spotify.com/track/$id",
            "https://example.com/watch?v=$id",
            "https://notyoutube.com/watch?v=$id",
            "https://www.youtube.com/playlist?list=PL123",
            "https://www.youtube.com/watch?v=short",
        ).forEach { assertNull(youtubeVideoId(it), it) }
    }

    @Test
    fun aPastedLinkBecomesTheChoiceAndABadOneIsSaid() =
        runTest(UnconfinedTestDispatcher()) {
            val resolver = resolver()
            val picker = SpotifyVersionPicker(resolver, backgroundScope)
            picker.open(track(7))

            picker.chooseLink("https://open.spotify.com/track/abc")
            assertEquals(true, picker.state.value?.linkRejected)
            assertEquals("yt7", picker.state.value?.currentId)
            picker.linkEdited()
            assertEquals(false, picker.state.value?.linkRejected)

            // A YouTube link, but YouTube has no such video.
            youtube.unloadable += "aaaaaaaaaaa"
            picker.chooseLink("https://youtu.be/aaaaaaaaaaa")
            assertEquals(true, picker.state.value?.linkRejected)
            assertEquals(false, picker.state.value?.manual)

            picker.chooseLink("https://music.youtube.com/watch?v=bbbbbbbbbbb")
            assertEquals("bbbbbbbbbbb", picker.state.value?.currentId)
            assertEquals(true, picker.state.value?.manual)
            assertEquals("bbbbbbbbbbb", picker.state.value?.candidates?.first()?.id)
            assertEquals("bbbbbbbbbbb", (resolver.resolve(track(7)) as SpotifyResolution.Matched).song.id)
        }
}
