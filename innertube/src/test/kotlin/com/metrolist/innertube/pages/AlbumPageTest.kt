package com.metrolist.innertube.pages

import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.response.BrowseResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AlbumPageTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val album = AlbumItem(
        browseId = "album",
        playlistId = "playlist",
        title = "Album",
        artists = emptyList(),
        thumbnail = "https://example.com/cover.jpg",
    )

    @Test
    fun `single page preserves server track order`() = runBlocking {
        val songs = AlbumPage.loadSongs(initial(listOf(song("b"), song("a"))), album) {
            error("Unexpected continuation")
        }
        assertEquals(listOf("b", "a"), songs.map { it.id })
    }

    @Test
    fun `append actions follow continuation tokens on every page`() = runBlocking {
        val requested = mutableListOf<String>()
        val songs = AlbumPage.loadSongs(initial(listOf(song("one"), token("next"))), album) {
            requested += it
            when (it) {
                "next" -> appended(listOf(song("two"), token("last")))
                "last" -> appended(listOf(song("three")))
                else -> error("Unexpected token")
            }
        }
        assertEquals(listOf("next", "last"), requested)
        assertEquals(listOf("one", "two", "three"), songs.map { it.id })
    }

    @Test
    fun `legacy shelf continuations read both tracks and tokens`() = runBlocking {
        listOf("musicPlaylistShelfContinuation", "musicShelfContinuation").forEach { kind ->
            val requested = mutableListOf<String>()
            val songs = AlbumPage.loadSongs(initial(listOf(song("one"), token("next"))), album) {
                requested += it
                when (it) {
                    "next" -> legacy(kind, listOf(song("two")), "last")
                    "last" -> legacy(kind, listOf(song("three")), null)
                    else -> error("Unexpected token")
                }
            }
            assertEquals(listOf("next", "last"), requested)
            assertEquals(listOf("one", "two", "three"), songs.map { it.id })
        }
    }

    @Test
    fun `initial shelf can carry legacy continuation metadata`() = runBlocking {
        listOf("musicPlaylistShelfRenderer", "musicShelfRenderer").forEach { kind ->
            val first = response(
                """"contents":{"twoColumnBrowseResultsRenderer":{"secondaryContents":{
                    "sectionListRenderer":{"contents":[{"$kind":{
                        "contents":[${song("one")}],
                        "continuations":[{"nextContinuationData":{"continuation":"next"}}]
                    }}]}
                }}}""",
            )
            val songs = AlbumPage.loadSongs(first, album) {
                assertEquals("next", it)
                appended(listOf(song("two")))
            }
            assertEquals(listOf("one", "two"), songs.map { it.id })
        }
    }

    @Test
    fun `repeated continuation fails instead of returning partial success`() {
        var requests = 0
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                AlbumPage.loadSongs(initial(listOf(song("one"), token("next"))), album) {
                    requests++
                    appended(listOf(song("two"), token("next")))
                }
            }
        }
        assertEquals(1, requests)
    }

    @Test
    fun `request limit fails instead of returning partial success`() {
        var requests = 0
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                AlbumPage.loadSongs(initial(listOf(token("0"))), album) {
                    requests++
                    appended(listOf(token(requests.toString())))
                }
            }
        }
        assertEquals(50, requests)
    }

    @Test
    fun `final page at request limit succeeds`() = runBlocking {
        var requests = 0
        val songs = AlbumPage.loadSongs(initial(listOf(token("0"))), album) {
            requests++
            if (requests == 50) appended(listOf(song("last")))
            else appended(listOf(token(requests.toString())))
        }
        assertEquals(50, requests)
        assertEquals(listOf("last"), songs.map { it.id })
    }

    @Test
    fun `missing continuation contents fails`() {
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                AlbumPage.loadSongs(initial(listOf(song("one"), token("next"))), album) {
                    response(""""onResponseReceivedActions":[]""")
                }
            }
        }
    }

    @Test
    fun `missing initial shelf fails`() {
        assertThrows(IllegalStateException::class.java) {
            runBlocking { AlbumPage.loadSongs(response(""""contents":{}"""), album) { error("Unexpected request") } }
        }
    }

    @Test
    fun `empty initial shelf cannot certify a complete album`() {
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                AlbumPage.loadSongs(initial(emptyList()), album) { error("Unexpected request") }
            }
        }
    }

    @Test
    fun `empty or omitted continuation contents cannot certify partial tracks`() {
        listOf(
            appended(emptyList()),
            legacy("musicPlaylistShelfContinuation", emptyList(), null),
            response(""""continuationContents":{"musicPlaylistShelfContinuation":{}}"""),
        ).forEach { lastPage ->
            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    AlbumPage.loadSongs(initial(listOf(song("one"), token("next"))), album) { lastPage }
                }
            }
        }
    }

    @Test
    fun `multiple append actions cannot silently drop a track list`() {
        val ambiguous = response(
            """"onResponseReceivedActions":[
                {"appendContinuationItemsAction":{"continuationItems":[${song("two")}]}},
                {"appendContinuationItemsAction":{"continuationItems":[${song("three")}]}}
            ]""",
        )
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                AlbumPage.loadSongs(initial(listOf(song("one"), token("next"))), album) { ambiguous }
            }
        }
    }

    @Test
    fun `unparseable and unrecognized rows cannot certify a complete album`() {
        listOf(
            song("one").replace("3:00", "unavailable"),
            """{"unknownRenderer":{}}""",
            """{"continuationItemRenderer":{}}""",
        ).forEach { invalid ->
            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    AlbumPage.loadSongs(initial(listOf(song("valid"), invalid)), album) { error("Unexpected request") }
                }
            }
        }
    }

    @Test
    fun `duplicate track ids cannot certify a complete album`() {
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                AlbumPage.loadSongs(initial(listOf(song("same"), song("same"))), album) { error("Unexpected request") }
            }
        }
    }

    @Test
    fun `network failure is propagated instead of returning partial tracks`() {
        val failure = java.io.IOException("offline")
        val thrown = assertThrows(java.io.IOException::class.java) {
            runBlocking {
                AlbumPage.loadSongs(initial(listOf(song("one"), token("next"))), album) { throw failure }
            }
        }
        assertEquals(failure, thrown)
    }

    private fun response(body: String): BrowseResponse =
        json.decodeFromString("""{"responseContext":{},$body}""")

    private fun initial(rows: List<String>): BrowseResponse = response(
        """"contents":{"twoColumnBrowseResultsRenderer":{"secondaryContents":{
            "sectionListRenderer":{"contents":[{"musicPlaylistShelfRenderer":{"contents":[${rows.joinToString()}]}}]}
        }}}""",
    )

    private fun appended(rows: List<String>): BrowseResponse = response(
        """"onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[${rows.joinToString()}]}}]""",
    )

    private fun legacy(kind: String, rows: List<String>, next: String?): BrowseResponse {
        val continuation = next?.let { """[{"nextContinuationData":{"continuation":"$it"}}]""" } ?: "[]"
        return response(
            """"continuationContents":{"$kind":{"contents":[${rows.joinToString()}],"continuations":$continuation}}""",
        )
    }

    private fun token(value: String): String =
        """{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"$value"}}}}"""

    private fun song(id: String): String = """
        {"musicResponsiveListItemRenderer":{
            "playlistItemData":{"videoId":"$id"},
            "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{
                "text":"$id",
                "navigationEndpoint":{"watchEndpoint":{"videoId":"$id",
                    "watchEndpointMusicSupportedConfigs":{"watchEndpointMusicConfig":{"musicVideoType":"MUSIC_VIDEO_TYPE_ATV"}}
                }}
            }]}}}],
            "fixedColumns":[{"musicResponsiveListItemFixedColumnRenderer":{"text":{"runs":[{"text":"3:00"}]}}}]
        }}
    """.trimIndent()
}
