package com.meld.spotify.playback

import com.meld.spotify.download.SpotifyDownloadResult
import com.meld.spotify.download.SpotifyDownloads
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class SpotifyDownloadsTest {
    private val youtube = FakeYouTube()
    private val queued = mutableListOf<List<String>>()
    private val alreadyThere = mutableSetOf<String>()

    private fun TestScope.downloads() =
        SpotifyDownloads(SpotifyTrackResolver(FakeMatchDao(), youtube::search, youtube::songsById, backgroundScope, { 0 })) { songs: List<SongItem> ->
            val fresh = songs.map { it.id }.filterNot { it in alreadyThere }
            queued += songs.map { it.id }
            fresh.size
        }

    @Test
    fun theSongsThatWouldPlayAreQueuedOnceAndInTheOrderOfTheList() =
        runTest {
            val progress = mutableListOf<Pair<Int, Int>>()
            // 25 tracks, one of them twice: three lookups of ten at most.
            val result = downloads().download(tracks(1..25) + track(3)) { done, total -> progress += done to total }
            assertEquals(SpotifyDownloadResult(queued = 25, found = 25, notFound = 0, unreachable = 0), result)
            assertEquals(listOf((1..25).map { "yt$it" }), queued)
            assertEquals(listOf(0 to 25, 10 to 25, 20 to 25, 25 to 25), progress)
        }

    @Test
    fun whatIsNotOnYouTubeOrCouldNotBeAskedIsCountedNotQueued() =
        runTest {
            youtube.missing += "Song 2"
            youtube.failing += "Song 3"
            alreadyThere += "yt1"
            val result = downloads().download(tracks(1..4))
            assertEquals(SpotifyDownloadResult(queued = 1, found = 2, notFound = 1, unreachable = 1), result)
            assertEquals(listOf(listOf("yt1", "yt4")), queued)
        }

    @Test
    fun nothingFoundQueuesNothing() =
        runTest {
            youtube.missing += "Song 1"
            assertEquals(SpotifyDownloadResult(queued = 0, found = 0, notFound = 1, unreachable = 0), downloads().download(tracks(1..1)))
            assertEquals(emptyList(), queued)
        }
}
