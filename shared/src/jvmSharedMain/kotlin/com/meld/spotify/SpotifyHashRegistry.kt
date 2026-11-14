package com.meld.spotify

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Persisted-query hashes per GraphQL operation. Candidates are tried in the order: last hash
 * Spotify accepted, remote (or cached) current and previous, bundled current and previous.
 */
internal class SpotifyHashRegistry(
    private val fetch: (suspend () -> String?)? = null,
    load: () -> String? = { null },
    private val save: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    private data class Entry(
        val hash: String,
        val previous: String?,
    )

    private class Registry(
        val updated: Instant,
        val operations: Map<String, Entry>,
    )

    private val mutex = Mutex()
    private val accepted = ConcurrentHashMap<String, String>()
    private var lastRefreshAt: Long? = null

    @Volatile
    private var remote: Registry? = load()?.let(::parse)?.takeUnless { it.updated < BUNDLED_UPDATED }

    fun candidates(operation: String): List<String> {
        val remoteEntry = remote?.operations?.get(operation)
        val bundled = BUNDLED[operation]
        return listOfNotNull(
            accepted[operation],
            remoteEntry?.hash,
            remoteEntry?.previous,
            bundled?.hash,
            bundled?.previous,
        ).distinct()
    }

    fun markAccepted(
        operation: String,
        hash: String,
    ) {
        accepted[operation] = hash
    }

    /** Returns true when a newer valid registry was downloaded. At most one attempt per [REFRESH_INTERVAL_MS]. */
    suspend fun refresh(): Boolean =
        mutex.withLock {
            val fetch = fetch ?: return false
            val now = clock()
            lastRefreshAt?.let { if (now - it < REFRESH_INTERVAL_MS) return false }
            lastRefreshAt = now
            val text =
                try {
                    fetch()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    log("Hash registry download failed: ${error::class.simpleName}")
                    null
                } ?: return false
            val downloaded = parse(text)
            val current = remote
            if (downloaded == null || downloaded.updated < (current?.updated ?: BUNDLED_UPDATED)) {
                log("Hash registry download rejected (invalid or older than the known one)")
                return false
            }
            if (downloaded.operations == current?.operations) return false
            remote = downloaded
            accepted.clear()
            save(text)
            log("Hash registry updated: ${downloaded.operations.size} operations")
            true
        }

    private fun parse(text: String): Registry? =
        runCatching {
            val root = lenientJson.parseToJsonElement(text) as JsonObject
            require(root.int("schema_version") == 1)
            val operations =
                requireNotNull(root.obj("operations"))
                    .mapNotNull { (name, value) ->
                        val entry = value as? JsonObject ?: return@mapNotNull null
                        val hash = entry.str("hash")?.takeIf(HASH::matches)
                        if (hash == null || name !in BUNDLED) return@mapNotNull null
                        name to Entry(hash, entry.str("previous_hash")?.takeIf(HASH::matches))
                    }.toMap()
            require(operations.isNotEmpty())
            Registry(Instant.parse(root.str("last_updated")), operations)
        }.getOrNull()

    companion object {
        const val DEFAULT_URL = "https://francescograzioso.github.io/Meld/spotify-gql-hashes.json"
        private const val REFRESH_INTERVAL_MS = 10 * 60_000L
        private val HASH = Regex("[0-9a-f]{64}")
        private val lenientJson = Json { ignoreUnknownKeys = true }

        // Copied from docs/spotify-gql-hashes.json; regenerate both values together at every release.
        private val BUNDLED_UPDATED = Instant.parse("2026-11-13T13:47:58Z")
        private val BUNDLED =
            mapOf(
                "profileAttributes" to
                    Entry(
                        "08ffb4730af3746e04a8301396f20875dbbce10c75243803091a9274eacc8ac0",
                        "b197b5adb4b761690f76ad9d9fb278c14c14e7331f357c04a56e7001af7106e0",
                    ),
                "libraryV3" to
                    Entry(
                        "390c78e5b951029bad359785e69b07b536a509c581cbcd0aded5e5067f187455",
                        "973e511ca44261fda7eebac8b653155e7caee3675abb4fb110cc1b8c78b091c3",
                    ),
                "fetchPlaylist" to
                    Entry(
                        "21ea11a6ac75a5f3e01fd365d29c18e0a4a8b7dc09f89bbb5d73910190c6acaf",
                        "8964e8eafb21aa992a7d951d256d83285c04be2105d209262901de70cb97584a",
                    ),
                "fetchLibraryTracks" to
                    Entry("087278b20b743578a6262c2b0b4bcd20d879c503cc359a2285baf083ef944240", null),
                "searchDesktop" to
                    Entry(
                        "db61238974d27839a136c9dc02bfdbe3fab7635f21cf85976ebff9a1ee281345",
                        "4801118d4a100f756e833d33984436a3899cff359c532f8fd3aaf174b60b3b49",
                    ),
                "queryArtistOverview" to
                    Entry(
                        "eafb1620d783ece118e72fbfede2b0064483e029d3c1aafafa1c2de0e25a89e7",
                        "9f8134ef565e78621f1e1793555bd6633c5ac144ae0f89604ed3ae3f80b3c8e6",
                    ),
                "getAlbum" to
                    Entry(
                        "0b6ce3511e1b02e4a40fa95440f992acbe85080eb308af9194322d708fc1914f",
                        "11c9cad9cb24c4f9e25ef9456f68621cfd6006a90f679e729c1d2f2fd70dfe16",
                    ),
                "queryWhatsNewFeed" to
                    Entry(
                        "d889c8c936ab192af8ced595427f5ba2acdf63478fdc0a181c8d477f8322630e",
                        "3b53dede3c6054e8b7c962dd280eb6761c5d1c82b06b039f4110d76a62b4966b",
                    ),
                "addToPlaylist" to
                    Entry(
                        "b907ad6b088d5bf7680fa81198996d58398c2d55dfcdf59fcc959e0be98a418a",
                        "47b2a1234b17748d332dd0431534f22450e9ecbb3d5ddcdacbd83368636a0990",
                    ),
                "removeFromPlaylist" to
                    Entry(
                        "b907ad6b088d5bf7680fa81198996d58398c2d55dfcdf59fcc959e0be98a418a",
                        "47b2a1234b17748d332dd0431534f22450e9ecbb3d5ddcdacbd83368636a0990",
                    ),
                "moveItemsInPlaylist" to
                    Entry(
                        "b907ad6b088d5bf7680fa81198996d58398c2d55dfcdf59fcc959e0be98a418a",
                        "47b2a1234b17748d332dd0431534f22450e9ecbb3d5ddcdacbd83368636a0990",
                    ),
                "editPlaylistAttributes" to
                    Entry("35a1a9ce3a2f4f8c32ee0e24c63c2069c6613c0a0b7e56d0e40dabe69a0b4f80", null),
                "addToLibrary" to
                    Entry(
                        "896ebcb47815681340860d121cb5d494e157e2a78d3950385cd54e0393c67148",
                        "1ad0d40b3c09660d818b9e770eb1e84745dfbe941df159a64f8772b6fa2bfc3a",
                    ),
                "removeFromLibrary" to
                    Entry(
                        "896ebcb47815681340860d121cb5d494e157e2a78d3950385cd54e0393c67148",
                        "1ad0d40b3c09660d818b9e770eb1e84745dfbe941df159a64f8772b6fa2bfc3a",
                    ),
                "home" to
                    Entry(
                        "5fb7da7a03ee2776c0670856609cb85a189a3cecc36cc160b91cde79063b9cf1",
                        "76243c78b0e20ecdbe41b794dec8cbe73f75e585b0a7201b8d2e84578412847a",
                    ),
            )
    }
}
