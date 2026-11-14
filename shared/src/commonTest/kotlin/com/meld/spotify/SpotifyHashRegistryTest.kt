package com.meld.spotify

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal const val PROFILE_HASH = "08ffb4730af3746e04a8301396f20875dbbce10c75243803091a9274eacc8ac0"
internal const val PROFILE_PREVIOUS_HASH = "b197b5adb4b761690f76ad9d9fb278c14c14e7331f357c04a56e7001af7106e0"

internal fun hashOf(character: Char): String = character.toString().repeat(64)

internal fun registryJson(
    hash: String,
    previous: String? = null,
    updated: String = "2027-01-01T00:00:00Z",
    schema: Int = 1,
): String {
    val previousJson = previous?.let { "\"$it\"" } ?: "null"
    return """
        {"schema_version":$schema,"last_updated":"$updated","bundle_id":"web-player.test.js","operations":{
          "profileAttributes":{"hash":"$hash","previous_hash":$previousJson,"type":"query","status":"verified"},
          "notUsedByTheApp":{"hash":"${hashOf('9')}","previous_hash":null}
        }}
        """.trimIndent()
}

class SpotifyHashRegistryTest {
    @Test
    fun bundledDefaultsCoverEveryOperationWithWellFormedHashes() {
        val registry = SpotifyHashRegistry()
        val operations =
            listOf(
                "profileAttributes",
                "libraryV3",
                "fetchPlaylist",
                "fetchLibraryTracks",
                "searchDesktop",
                "queryArtistOverview",
                "getAlbum",
                "queryWhatsNewFeed",
                "addToPlaylist",
                "removeFromPlaylist",
                "moveItemsInPlaylist",
                "editPlaylistAttributes",
                "addToLibrary",
                "removeFromLibrary",
                "home",
            )
        for (operation in operations) {
            val candidates = registry.candidates(operation)
            assertTrue(candidates.isNotEmpty(), operation)
            assertTrue(candidates.all { Regex("[0-9a-f]{64}").matches(it) }, operation)
        }
        assertEquals(listOf(PROFILE_HASH, PROFILE_PREVIOUS_HASH), registry.candidates("profileAttributes"))
        assertTrue(registry.candidates("unknownOperation").isEmpty())
    }

    @Test
    fun candidatesGoFromAcceptedToRemoteToBundled() {
        val registry = SpotifyHashRegistry(load = { registryJson(hashOf('a'), hashOf('b')) })
        assertEquals(
            listOf(hashOf('a'), hashOf('b'), PROFILE_HASH, PROFILE_PREVIOUS_HASH),
            registry.candidates("profileAttributes"),
        )
        registry.markAccepted("profileAttributes", PROFILE_PREVIOUS_HASH)
        assertEquals(
            listOf(PROFILE_PREVIOUS_HASH, hashOf('a'), hashOf('b'), PROFILE_HASH),
            registry.candidates("profileAttributes"),
        )
        assertTrue(registry.candidates("notUsedByTheApp").isEmpty())
    }

    @Test
    fun cachedCopyOlderThanTheBundleIsIgnored() {
        val registry = SpotifyHashRegistry(load = { registryJson(hashOf('a'), updated = "2026-01-01T00:00:00Z") })
        assertEquals(listOf(PROFILE_HASH, PROFILE_PREVIOUS_HASH), registry.candidates("profileAttributes"))
    }

    @Test
    fun malformedRegistriesAreRejected() =
        runTest {
            val invalid =
                listOf(
                    "not json",
                    "[]",
                    """{"schema_version":1,"last_updated":"2027-01-01T00:00:00Z"}""",
                    registryJson(hashOf('a'), schema = 2),
                    registryJson(hashOf('a'), updated = "yesterday"),
                    registryJson(hashOf('A')),
                    registryJson("abc123"),
                    registryJson(hashOf('a') + "0"),
                )
            for (text in invalid) {
                var saved: String? = null
                val registry = SpotifyHashRegistry(fetch = { text }, save = { saved = it })
                assertFalse(registry.refresh(), text)
                assertNull(saved, text)
                assertEquals(listOf(PROFILE_HASH, PROFILE_PREVIOUS_HASH), registry.candidates("profileAttributes"), text)
                assertEquals(
                    listOf(PROFILE_HASH, PROFILE_PREVIOUS_HASH),
                    SpotifyHashRegistry(load = { text }).candidates("profileAttributes"),
                )
            }
        }

    @Test
    fun malformedPreviousHashIsDroppedButTheEntryIsKept() {
        val registry = SpotifyHashRegistry(load = { registryJson(hashOf('a'), previous = "XYZ") })
        assertEquals(listOf(hashOf('a'), PROFILE_HASH, PROFILE_PREVIOUS_HASH), registry.candidates("profileAttributes"))
    }

    @Test
    fun refreshPersistsTheDownloadForgetsAcceptedHashesAndIsDebounced() =
        runTest {
            var now = 0L
            var fetches = 0
            var remote = registryJson(hashOf('a'))
            var saved: String? = null
            val registry = SpotifyHashRegistry(fetch = { remote.also { fetches++ } }, save = { saved = it }, clock = { now })
            registry.markAccepted("profileAttributes", PROFILE_PREVIOUS_HASH)

            assertTrue(registry.refresh())
            assertEquals(remote, saved)
            assertEquals(listOf(hashOf('a'), PROFILE_HASH, PROFILE_PREVIOUS_HASH), registry.candidates("profileAttributes"))

            remote = registryJson(hashOf('c'), hashOf('a'), updated = "2027-02-01T00:00:00Z")
            now += 60_000
            assertFalse(registry.refresh())
            assertEquals(1, fetches)

            now += 10 * 60_000
            assertTrue(registry.refresh())
            assertEquals(2, fetches)
            assertEquals(hashOf('c'), registry.candidates("profileAttributes").first())

            remote = registryJson(hashOf('d'), updated = "2027-01-15T00:00:00Z")
            now += 10 * 60_000
            assertFalse(registry.refresh(), "a registry older than the known one must not replace it")
            assertEquals(hashOf('c'), registry.candidates("profileAttributes").first())
        }

    @Test
    fun failedDownloadKeepsTheKnownHashes() =
        runTest {
            val registry = SpotifyHashRegistry(fetch = { error("network down") }, load = { registryJson(hashOf('a')) })
            assertFalse(registry.refresh())
            assertEquals(hashOf('a'), registry.candidates("profileAttributes").first())
            assertFalse(SpotifyHashRegistry(fetch = { null }).refresh())
        }
}
