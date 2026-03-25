package com.metrolist.music.utils

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.metrolist.spotify.SpotifyHashProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches and caches Spotify GQL hashes from the remote GitHub Pages registry.
 *
 * Sync strategy (no WorkManager dependency):
 * - At app boot: [loadCachedHashes] restores previously fetched hashes
 * - [syncIfStale] fetches from remote only if the cache is older than [STALE_THRESHOLD_MS]
 * - [forceRefresh] bypasses the staleness check (used on PersistedQueryNotFound)
 */
class SpotifyHashSync(private val context: Context) {

    companion object {
        private const val REMOTE_URL =
            "https://francescograzioso.github.io/Meld/spotify-gql-hashes.json"
        private const val STALE_THRESHOLD_MS = 24 * 60 * 60 * 1000L // 24 hours
        private const val FETCH_TIMEOUT_MS = 10_000

        private val CACHED_JSON_KEY = stringPreferencesKey("spotify_gql_hashes_json")
        private val LAST_FETCH_KEY = longPreferencesKey("spotify_gql_hashes_last_fetch")
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val fetchMutex = Mutex()

    /**
     * Loads previously cached hashes from DataStore into [SpotifyHashProvider].
     * Should be called early during app startup (before any Spotify API call).
     */
    suspend fun loadCachedHashes() {
        withContext(Dispatchers.IO) {
            try {
                val prefs = context.dataStore.data.first()
                val cachedJson = prefs[CACHED_JSON_KEY] ?: return@withContext
                val parsed = parseRemoteJson(cachedJson)
                if (parsed.isNotEmpty()) {
                    SpotifyHashProvider.updateHashes(parsed, SpotifyHashProvider.HashSource.CACHED)
                    Timber.tag("HashSync").d("Loaded %d cached hashes from DataStore", parsed.size)
                }
            } catch (e: Exception) {
                Timber.tag("HashSync").w(e, "Failed to load cached hashes")
            }
        }
    }

    /**
     * Fetches remote hashes only if the cache is older than 24 hours.
     * Safe to call on every app launch — no-ops if hashes are fresh.
     */
    suspend fun syncIfStale() {
        withContext(Dispatchers.IO) {
            try {
                val prefs = context.dataStore.data.first()
                val lastFetch = prefs[LAST_FETCH_KEY] ?: 0L
                val age = System.currentTimeMillis() - lastFetch
                if (age < STALE_THRESHOLD_MS) {
                    Timber.tag("HashSync").d(
                        "Hashes are fresh (age: %dh), skipping remote fetch",
                        age / (60 * 60 * 1000),
                    )
                    return@withContext
                }
                fetchAndUpdate()
            } catch (e: Exception) {
                Timber.tag("HashSync").w(e, "syncIfStale failed")
            }
        }
    }

    /**
     * Forces a remote fetch regardless of cache age.
     * Called when a GQL operation receives PersistedQueryNotFound.
     */
    suspend fun forceRefresh() {
        withContext(Dispatchers.IO) {
            try {
                Timber.tag("HashSync").w("Force-refreshing hashes (triggered by hash rejection)")
                fetchAndUpdate()
            } catch (e: Exception) {
                Timber.tag("HashSync").e(e, "forceRefresh failed")
            }
        }
    }

    private suspend fun fetchAndUpdate() {
        fetchMutex.withLock {
            val responseBody = fetchRemoteJson() ?: return
            val parsed = parseRemoteJson(responseBody)
            if (parsed.isEmpty()) {
                Timber.tag("HashSync").w("Remote JSON parsed to 0 operations, ignoring")
                return
            }

            SpotifyHashProvider.updateHashes(parsed, SpotifyHashProvider.HashSource.REMOTE)

            context.dataStore.edit { prefs ->
                prefs[CACHED_JSON_KEY] = responseBody
                prefs[LAST_FETCH_KEY] = System.currentTimeMillis()
            }

            Timber.tag("HashSync").i("Updated %d hashes from remote registry", parsed.size)
        }
    }

    private fun fetchRemoteJson(): String? =
        try {
            val connection = URL(REMOTE_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = FETCH_TIMEOUT_MS
            connection.readTimeout = FETCH_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                Timber.tag("HashSync").w(
                    "Remote returned HTTP %d",
                    connection.responseCode,
                )
                null
            }
        } catch (e: Exception) {
            Timber.tag("HashSync").w(e, "Failed to fetch remote hashes")
            null
        }

    private fun parseRemoteJson(rawJson: String): Map<String, SpotifyHashProvider.RemoteHashEntry> {
        return try {
            val root = json.parseToJsonElement(rawJson).jsonObject
            val operations = root["operations"]?.jsonObject ?: return emptyMap()

            operations.entries.mapNotNull { (opName, value) ->
                val obj = value.jsonObject
                val hash = obj["hash"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val previousHash = obj["previous_hash"]?.jsonPrimitive?.contentOrNull
                opName to SpotifyHashProvider.RemoteHashEntry(
                    hash = hash,
                    previousHash = previousHash,
                )
            }.toMap()
        } catch (e: Exception) {
            Timber.tag("HashSync").w(e, "Failed to parse remote hash JSON")
            emptyMap()
        }
    }
}
