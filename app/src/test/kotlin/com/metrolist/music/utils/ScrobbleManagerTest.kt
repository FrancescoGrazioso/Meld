package com.metrolist.music.utils

import com.metrolist.music.models.MediaMetadata
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ScrobbleManagerTest {

    @Test
    fun `transition followed by playing state change scrobbles each song once`() = runBlocking {
        val client = FakeLastFmScrobbleClient()
        val manager = ScrobbleManager(
            scope = this,
            minSongDuration = 1,
            scrobbleDelayPercent = 0.02f,
            scrobbleDelaySeconds = 10,
            client = client,
        )
        val firstSong = mediaMetadata(id = "first", title = "First")
        val secondSong = mediaMetadata(id = "second", title = "Second")

        manager.onSongStart(firstSong, duration = 10_000L)
        manager.onSongStop(finalize = true)
        manager.onSongStart(secondSong, duration = 10_000L)
        manager.onPlayerStateChanged(isPlaying = true, metadata = secondSong, duration = 10_000L)
        delay(300)

        assertEquals(
            listOf(
                FakeScrobble(track = "First", duration = 10),
                FakeScrobble(track = "Second", duration = 10),
            ),
            client.scrobbles,
        )

        manager.destroy()
    }

    @Test
    fun `playing metadata change restarts scrobble timer for the new song`() = runBlocking {
        val client = FakeLastFmScrobbleClient()
        val manager = ScrobbleManager(
            scope = this,
            minSongDuration = 1,
            scrobbleDelayPercent = 0.02f,
            scrobbleDelaySeconds = 10,
            client = client,
        )

        manager.onPlayerStateChanged(
            isPlaying = true,
            metadata = mediaMetadata(id = "first", title = "First"),
            duration = 10_000L,
        )
        delay(50)

        manager.onPlayerStateChanged(
            isPlaying = true,
            metadata = mediaMetadata(id = "second", title = "Second", duration = -1),
            duration = 10_000L,
        )
        delay(300)

        assertEquals(listOf(FakeScrobble(track = "Second", duration = 10)), client.scrobbles)

        manager.destroy()
    }

    private fun mediaMetadata(
        id: String,
        title: String,
        duration: Int = 10,
    ) = MediaMetadata(
        id = id,
        title = title,
        artists = listOf(MediaMetadata.Artist(id = null, name = "Artist")),
        duration = duration,
    )

    private class FakeLastFmScrobbleClient : LastFmScrobbleClient {
        val scrobbles = mutableListOf<FakeScrobble>()

        override suspend fun updateNowPlaying(
            artist: String,
            track: String,
            album: String?,
            duration: Int?,
        ): Result<Unit> = Result.success(Unit)

        override suspend fun scrobble(
            artist: String,
            track: String,
            timestamp: Long,
            album: String?,
            duration: Int?,
        ): Result<Unit> {
            scrobbles += FakeScrobble(track = track, duration = duration)
            return Result.success(Unit)
        }
    }

    private data class FakeScrobble(
        val track: String,
        val duration: Int?,
    )
}
