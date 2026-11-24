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
}
