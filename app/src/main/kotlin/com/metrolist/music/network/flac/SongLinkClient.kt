/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.network.flac

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class SongLinkClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .writeTimeout(1500, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()
) {
    companion object {
        private const val TAG = "SongLinkClient"
        private const val SONG_LINK_API = "https://api.song.link/v1-alpha.1/links"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36"

        private val TIDAL_TRACK_ID_REGEX = Pattern.compile("/track/(\\d+)")
        private val AMAZON_ASIN_REGEX = Pattern.compile("(?i)(?:/tracks/|/albums/[A-Z0-9]{10}/|[?&]asin=)(B[0-9A-Z]{9})")
        private val DEEZER_TRACK_ID_REGEX = Pattern.compile("/track/(\\d+)")
        private val QOBUZ_TRACK_ID_REGEX = Pattern.compile("/track/(\\d+)")
        private val ISRC_REGEX = Pattern.compile("\\b([A-Z]{2}[A-Z0-9]{3}\\d{7})\\b")
    }

    fun resolveLinksForSpotifyTrack(spotifyTrackId: String): CrossPlatformLinks? {
        val cleanTrackId = spotifyTrackId.removePrefix("spotify:track:").trim()
        if (cleanTrackId.isEmpty()) return null

        val spotifyUrl = "https://open.spotify.com/track/$cleanTrackId"
        return resolveLinksForUrl(spotifyUrl, cleanTrackId)
    }

    fun resolveLinksByTrackInfo(title: String, artist: String, album: String? = null): CrossPlatformLinks? {
        val cleanTitle = title.trim()
        val cleanArtist = artist.trim()
        if (cleanTitle.isEmpty() || cleanArtist.isEmpty()) return null

        Timber.tag(TAG).d("Searching SongLink for '%s' by '%s'...", cleanTitle, cleanArtist)

        // Strategy 1: Search via iTunes Search API (fast, open, reliable cross-platform bridge for SongLink)
        try {
            val query = "$cleanArtist $cleanTitle"
            val itunesUrl = "https://itunes.apple.com/search".toHttpUrlOrNull()?.newBuilder()
                ?.addQueryParameter("term", query)
                ?.addQueryParameter("media", "music")
                ?.addQueryParameter("entity", "song")
                ?.addQueryParameter("limit", "1")
                ?.build()

            if (itunesUrl != null) {
                val req = Request.Builder()
                    .url(itunesUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .build()

                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string()
                        if (!body.isNullOrBlank()) {
                            val itunesJson = JSONObject(body)
                            val results = itunesJson.optJSONArray("results")
                            if (results != null && results.length() > 0) {
                                val trackObj = results.getJSONObject(0)
                                val trackViewUrl = trackObj.optString("trackViewUrl")
                                if (trackViewUrl.isNotBlank()) {
                                    val links = resolveLinksForUrl(trackViewUrl)
                                    if (links != null && (links.hasTidal || links.hasQobuz || links.hasAmazon || links.hasDeezer)) {
                                        Timber.tag(TAG).i("Matched track to Tidal ID / ISRC: %s / %s", links.tidalTrackId ?: "none", links.isrc ?: "none")
                                        android.util.Log.d(TAG, "Matched track to Tidal ID / ISRC: ${links.tidalTrackId ?: "none"} / ${links.isrc ?: "none"}")
                                        return links
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).d(e, "iTunes bridge search failed for %s - %s", cleanArtist, cleanTitle)
        }

        // Strategy 2: Search via Deezer Search API
        try {
            val query = "$cleanArtist $cleanTitle"
            val deezerSearchUrl = "https://api.deezer.com/search".toHttpUrlOrNull()?.newBuilder()
                ?.addQueryParameter("q", query)
                ?.addQueryParameter("limit", "1")
                ?.build()

            if (deezerSearchUrl != null) {
                val req = Request.Builder()
                    .url(deezerSearchUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .build()

                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string()
                        if (!body.isNullOrBlank()) {
                            val json = JSONObject(body)
                            val data = json.optJSONArray("data")
                            if (data != null && data.length() > 0) {
                                val item = data.getJSONObject(0)
                                val link = item.optString("link")
                                val isrc = item.optString("isrc").takeIf { it.isNotBlank() }

                                if (link.isNotBlank()) {
                                    val links = resolveLinksForUrl(link)
                                    if (links != null) {
                                        val enriched = if (links.isrc == null && isrc != null) links.copy(isrc = isrc) else links
                                        Timber.tag(TAG).i("Matched track to Tidal ID / ISRC: %s / %s", enriched.tidalTrackId ?: "none", enriched.isrc ?: "none")
                                        android.util.Log.d(TAG, "Matched track to Tidal ID / ISRC: ${enriched.tidalTrackId ?: "none"} / ${enriched.isrc ?: "none"}")
                                        return enriched
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).d(e, "Deezer search failed for %s - %s", cleanArtist, cleanTitle)
        }

        return null
    }

    fun resolveLinksForUrl(targetUrl: String, originalSpotifyId: String? = null): CrossPlatformLinks? {
        val endpointUrl = FlacRegistryManager.getSongLinkEndpoint()
        val httpUrl = endpointUrl.toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("url", targetUrl)
            ?.build() ?: return null

        val request = Request.Builder()
            .url(httpUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.tag(TAG).w("SongLink API returned code %d", response.code)
                    return null
                }
                val body = response.body?.string() ?: return null
                parseSongLinkJson(body, originalSpotifyId)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to resolve links from SongLink for %s", targetUrl)
            null
        }
    }

    fun resolveIsrcFromDeezer(deezerTrackId: String): String? {
        val url = "https://api.deezer.com/track/$deezerTrackId"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val json = JSONObject(response.body?.string() ?: return null)
                val isrc = json.optString("isrc").trim().takeIf { it.isNotEmpty() }
                isrc
            }
        } catch (e: Exception) {
            Timber.tag(TAG).d(e, "Deezer ISRC lookup failed for trackId: %s", deezerTrackId)
            null
        }
    }

    private fun parseSongLinkJson(jsonString: String, originalSpotifyId: String?): CrossPlatformLinks {
        val root = JSONObject(jsonString)
        val linksByPlatform = root.optJSONObject("linksByPlatform")
        val entitiesByUniqueId = root.optJSONObject("entitiesByUniqueId")

        var isrc: String? = null

        // Attempt to find ISRC from entities
        if (entitiesByUniqueId != null) {
            val keys = entitiesByUniqueId.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val entity = entitiesByUniqueId.optJSONObject(key) ?: continue
                val entityIsrc = entity.optString("isrc").trim()
                if (entityIsrc.isNotEmpty()) {
                    isrc = entityIsrc
                    break
                }
            }
        }

        // Parse Tidal
        val tidalUrl = linksByPlatform?.optJSONObject("tidal")?.optString("url")?.takeIf { it.isNotBlank() }
        val tidalTrackId = tidalUrl?.let { extractTidalTrackId(it) }

        // Parse Amazon
        val amazonUrl = (linksByPlatform?.optJSONObject("amazonMusic")?.optString("url")
            ?: linksByPlatform?.optJSONObject("amazonStore")?.optString("url"))?.takeIf { it.isNotBlank() }
        val amazonAsin = amazonUrl?.let { extractAmazonAsin(it) }

        // Parse Deezer
        val deezerUrl = linksByPlatform?.optJSONObject("deezer")?.optString("url")?.takeIf { it.isNotBlank() }
        val deezerTrackId = deezerUrl?.let { extractDeezerTrackId(it) }

        // Parse Qobuz
        val qobuzUrl = linksByPlatform?.optJSONObject("qobuz")?.optString("url")?.takeIf { it.isNotBlank() }
        val qobuzTrackId = qobuzUrl?.let { extractQobuzTrackId(it) }

        // If ISRC was not in SongLink response, try Deezer lookup
        if (isrc.isNullOrBlank() && deezerTrackId != null) {
            isrc = resolveIsrcFromDeezer(deezerTrackId)
        }

        return CrossPlatformLinks(
            spotifyTrackId = originalSpotifyId,
            isrc = isrc,
            tidalTrackId = tidalTrackId,
            tidalUrl = tidalUrl,
            amazonAsin = amazonAsin,
            amazonUrl = amazonUrl,
            qobuzTrackId = qobuzTrackId,
            qobuzUrl = qobuzUrl,
            deezerTrackId = deezerTrackId,
            deezerUrl = deezerUrl,
        )
    }

    private fun extractTidalTrackId(url: String): String? {
        val matcher = TIDAL_TRACK_ID_REGEX.matcher(url)
        return if (matcher.find()) matcher.group(1) else null
    }

    private fun extractAmazonAsin(url: String): String? {
        val matcher = AMAZON_ASIN_REGEX.matcher(url)
        return if (matcher.find()) matcher.group(1) else null
    }

    private fun extractDeezerTrackId(url: String): String? {
        val matcher = DEEZER_TRACK_ID_REGEX.matcher(url)
        return if (matcher.find()) matcher.group(1) else null
    }

    private fun extractQobuzTrackId(url: String): String? {
        val matcher = QOBUZ_TRACK_ID_REGEX.matcher(url)
        return if (matcher.find()) matcher.group(1) else null
    }
}
