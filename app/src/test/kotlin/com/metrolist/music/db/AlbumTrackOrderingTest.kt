package com.metrolist.music.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.metrolist.innertube.models.Album
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.pages.AlbumPage
import com.metrolist.music.db.entities.AlbumEntity
import com.metrolist.music.db.entities.AlbumSong
import com.metrolist.music.db.entities.AlbumWithSongs
import com.metrolist.music.db.entities.Event
import com.metrolist.music.db.entities.PlayCountEntity
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.PlaylistSongMap
import com.metrolist.music.db.entities.SongAlbumMap
import com.metrolist.music.db.entities.SongEntity
import java.time.LocalDateTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AlbumTrackOrderingTest {
    private lateinit var database: InternalDatabase
    private val albumId = "nine"
    private val officialOrder = listOf(
        "borehole", "empath", "eluvial", "e2", "meridian", "arletta", "seventh-seal",
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `prerelease singles move to correct positions after album release`() = runBlocking {
        database.dao.insert(page(listOf("empath", "eluvial", "seventh-seal")))
        refresh(page(officialOrder))

        assertTrackOrder(officialOrder)
    }

    @Test
    fun `album refresh removes outdated song relationships when track ids change`() = runBlocking {
        val singles = listOf("single-empath", "single-eluvial", "single-seventh")
        database.dao.insert(page(singles))
        refresh(page(officialOrder))

        assertTrackOrder(officialOrder)
        singles.forEach { assertEquals(it, database.dao.song(it).first()!!.id) }
    }

    @Test
    fun `album tracks follow track indices instead of database insertion order`() = runBlocking {
        database.dao.insert(AlbumEntity(id = albumId, title = "Nine", songCount = 7, duration = 0))
        listOf("seventh-seal", "empath", "arletta", "eluvial", "borehole", "meridian", "e2")
            .forEach { id ->
                database.dao.insert(SongEntity(id = id, title = id))
                database.dao.insert(SongAlbumMap(id, albumId, officialOrder.indexOf(id)))
            }

        assertTrackOrder(officialOrder)
    }

    @Test
    fun `album relation sorts entries even when SQLite returns insertion order`() = runBlocking {
        database.dao.insert(page(officialOrder))
        val stored = database.dao.albumWithSongs(albumId).first()!!
        val insertionOrder = listOf("empath", "eluvial", "seventh-seal", "borehole", "e2", "meridian", "arletta")
        val album = AlbumWithSongs(
            album = stored.album,
            artists = stored.artists,
            songEntries = insertionOrder.map { id ->
                AlbumSong(
                    map = SongAlbumMap(id, albumId, officialOrder.indexOf(id)),
                    song = stored.songs.single { it.id == id },
                )
            },
        )

        assertEquals(officialOrder, album.songs.map { it.id })
    }

    @Test
    fun `incomplete album refresh must not discard existing tracks`() = runBlocking {
        database.dao.insert(page(officialOrder))
        val before = database.dao.albumWithSongs(albumId).first()!!

        refresh(page(officialOrder.take(3), complete = false))

        assertEquals(before, database.dao.albumWithSongs(albumId).first())
        assertTrackOrder(officialOrder)
    }

    @Test
    fun `partial response must not overwrite existing track indices`() = runBlocking {
        database.dao.insert(page(officialOrder))
        refresh(page(listOf("eluvial", "empath", "new-track"), complete = false))

        assertTrackOrder(officialOrder)
    }

    @Test
    fun `empty refresh leaves cached album intact`() = runBlocking {
        database.dao.insert(page(officialOrder))
        val before = database.dao.albumWithSongs(albumId).first()!!

        refresh(page(emptyList()))

        assertEquals(before, database.dao.albumWithSongs(albumId).first())
    }

    @Test
    fun `unknown completeness does not authorize cleanup`() = runBlocking {
        database.dao.insert(page(officialOrder))
        val unknown = AlbumPage(
            album = page(emptyList()).album,
            songs = page(officialOrder.take(3)).songs,
            otherVersions = emptyList(),
        )
        refresh(unknown)

        assertTrackOrder(officialOrder)
    }

    @Test
    fun `complete shorter album can replace previous track list`() = runBlocking {
        database.dao.insert(page(officialOrder))
        val shortened = listOf("empath", "eluvial", "seventh-seal")
        refresh(page(shortened))

        assertTrackOrder(shortened)
        assertEquals(3, database.dao.albumWithSongs(albumId).first()!!.album.songCount)
    }

    @Test
    fun `mapping cleanup preserves song state playlists history and other albums`() = runBlocking {
        val oldId = "single-empath"
        database.dao.insert(page(listOf(oldId)))
        val now = LocalDateTime.of(2026, 1, 1, 0, 0)
        val originalSong = database.dao.song(oldId).first()!!.song.copy(
            title = "My edited title",
            liked = true,
            likedDate = now,
            inLibrary = now,
            totalPlayTime = 123456L,
            isDownloaded = true,
            dateDownload = now,
            isLocal = true,
            localPath = "/music/empath.flac",
        )
        database.dao.update(originalSong)
        database.dao.insert(PlaylistEntity(id = "playlist", name = "My playlist"))
        database.dao.insert(PlaylistSongMap(playlistId = "playlist", songId = oldId))
        database.dao.insert(Event(songId = oldId, timestamp = now, playTime = 5000L))
        database.dao.insert(PlayCountEntity(song = oldId, year = 2026, month = 1, count = 5))
        database.dao.insert(AlbumEntity(id = "other-album", title = "Other", songCount = 1, duration = 200))
        database.dao.insert(SongAlbumMap(oldId, "other-album", 0))

        refresh(page(officialOrder))

        assertTrackOrder(officialOrder)
        assertEquals(originalSong, database.dao.song(oldId).first()!!.song)
        assertEquals(listOf(oldId), database.dao.playlistSongs("playlist").first().map { it.song.id })
        assertEquals(listOf(oldId), database.dao.albumSongs("other-album").first().map { it.id })
        database.openHelper.readableDatabase.query("SELECT playTime FROM event WHERE songId = '$oldId'").use {
            check(it.moveToFirst())
            assertEquals(5000L, it.getLong(0))
        }
        database.openHelper.readableDatabase.query("SELECT count FROM playCount WHERE song = '$oldId'").use {
            check(it.moveToFirst())
            assertEquals(5, it.getInt(0))
        }
    }

    private suspend fun refresh(page: AlbumPage) {
        val existing = database.dao.albumWithSongs(albumId).first()!!
        database.dao.update(existing.album, page, existing.artists)
    }

    private suspend fun assertTrackOrder(expected: List<String>) {
        assertEquals(expected, database.dao.albumWithSongs(albumId).first()!!.songs.map { it.id })
        assertEquals(expected, database.dao.albumSongs(albumId).first().map { it.id })
        assertEquals(expected, database.dao.albumSongs(albumId, 100, 0).map { it.id })
    }

    private fun page(ids: List<String>, complete: Boolean = true): AlbumPage {
        val album = AlbumItem(
            browseId = albumId,
            playlistId = "playlist-nine",
            title = "Nine",
            artists = emptyList(),
            thumbnail = "https://example.com/cover.jpg",
        )
        return AlbumPage(
            album = album,
            songs = ids.map { id ->
                SongItem(
                    id = id,
                    title = id,
                    artists = emptyList(),
                    album = Album(album.title, albumId),
                    duration = 200,
                    thumbnail = album.thumbnail,
                )
            },
            otherVersions = emptyList(),
            isSongListComplete = complete,
        )
    }
}
