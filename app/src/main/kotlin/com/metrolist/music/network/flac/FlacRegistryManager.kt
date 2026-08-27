/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.network.flac

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

data class FlacExtensionEntry(
    val id: String,
    val name: String,
    val displayName: String,
    val version: String,
    val downloadUrl: String,
    val category: String,
    val tags: List<String>,
)

data class FlacEndpointsConfig(
    val tidalEndpoint: String = "https://tdl-oss.spotbye.qzz.io/api/dl",
    val tidalSecondaryEndpoint: String = "https://api.zarz.moe/v1/dl/tid2",
    val qobuzEndpoint: String = "https://qbz-oss.spotbye.qzz.io/api/dl",
    val amazonEndpoint: String = "https://amz-oss.spotbye.qzz.io/api/dl",
    val deezerEndpoint: String = "https://api.deezer.com/track",
    val songLinkEndpoint: String = "https://api.song.link/v1-alpha.1/links",
)

object FlacRegistryManager {
    private const val TAG = "FlacRegistryManager"
    private const val REGISTRY_URL = "https://raw.githubusercontent.com/spotiflacapp/spotiflac-extension/main/registry.json"
    private const val BACKUP_REGISTRY_URL = "https://raw.githubusercontent.com/zarzet/SpotiFLAC-Extension/main/registry.json"

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _extensions = MutableStateFlow<List<FlacExtensionEntry>>(emptyList())
    val extensions: StateFlow<List<FlacExtensionEntry>> = _extensions.asStateFlow()

    private val _endpoints = MutableStateFlow(FlacEndpointsConfig())
    val endpoints: StateFlow<FlacEndpointsConfig> = _endpoints.asStateFlow()

    @Volatile
    var isInitialized = false
        private set

    fun initialize() {
        if (isInitialized) return
        isInitialized = true
        scope.launch {
            fetchRegistry()
        }
    }

    suspend fun fetchRegistry(): Boolean = withContext(Dispatchers.IO) {
        val urlsToTry = listOf(REGISTRY_URL, BACKUP_REGISTRY_URL)
        for (url in urlsToTry) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Loop-Music-Player")
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (!body.isNullOrBlank()) {
                            parseRegistryJson(body)
                            Timber.tag(TAG).i("Loaded FLAC registry from %s with %d extensions", url, _extensions.value.size)
                            return@withContext true
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).d("Failed to fetch registry from %s: %s", url, e.message)
            }
        }
        false
    }

    private fun parseRegistryJson(jsonStr: String) {
        try {
            val root = JSONObject(jsonStr)
            val extensionsArray = root.optJSONArray("extensions") ?: return
            val parsedList = mutableListOf<FlacExtensionEntry>()

            for (i in 0 until extensionsArray.length()) {
                val item = extensionsArray.getJSONObject(i)
                val id = item.optString("id")
                val name = item.optString("name")
                val displayName = item.optString("display_name")
                val version = item.optString("version")
                val downloadUrl = item.optString("download_url")
                val category = item.optString("category")
                val tagsJson = item.optJSONArray("tags")
                val tags = mutableListOf<String>()
                if (tagsJson != null) {
                    for (j in 0 until tagsJson.length()) {
                        tags.add(tagsJson.getString(j))
                    }
                }
                parsedList.add(
                    FlacExtensionEntry(
                        id = id,
                        name = name,
                        displayName = displayName,
                        version = version,
                        downloadUrl = downloadUrl,
                        category = category,
                        tags = tags,
                    )
                )
            }
            _extensions.value = parsedList

            // Build dynamic endpoints based on registry rules
            var tidal = _endpoints.value.tidalEndpoint
            var qobuz = _endpoints.value.qobuzEndpoint
            var amazon = _endpoints.value.amazonEndpoint

            parsedList.forEach { ext ->
                when (ext.id) {
                    "tidal-web", "tidal" -> {
                        tidal = "https://tdl-oss.spotbye.qzz.io/api/dl"
                    }
                    "qobuz-web", "qobuz" -> {
                        qobuz = "https://qbz-oss.spotbye.qzz.io/api/dl"
                    }
                    "amazon", "amzn" -> {
                        amazon = "https://amz-oss.spotbye.qzz.io/api/dl"
                    }
                }
            }

            _endpoints.value = FlacEndpointsConfig(
                tidalEndpoint = tidal,
                qobuzEndpoint = qobuz,
                amazonEndpoint = amazon,
            )
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error parsing registry JSON")
        }
    }

    fun getTidalEndpoint(): String = _endpoints.value.tidalEndpoint
    fun getQobuzEndpoint(): String = _endpoints.value.qobuzEndpoint
    fun getAmazonEndpoint(): String = _endpoints.value.amazonEndpoint
    fun getSongLinkEndpoint(): String = _endpoints.value.songLinkEndpoint
}
