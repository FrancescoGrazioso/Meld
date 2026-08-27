/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.network.flac.providers

import com.metrolist.music.network.flac.FlacAudioQuality
import com.metrolist.music.network.flac.FlacProvider
import com.metrolist.music.network.flac.FlacRegistryManager
import com.metrolist.music.network.flac.FlacStreamResult
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

class QobuzFlacProvider(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .writeTimeout(1500, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build(),
    private var customBaseUrl: String? = null,
) {
    companion object {
        private const val TAG = "QobuzFlacProvider"
        const val COMMUNITY_ENDPOINT = "https://qbz-oss.spotbye.qzz.io/api/dl"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36"
    }

    fun resolveStream(
        trackId: String,
        quality: FlacAudioQuality = FlacAudioQuality.HI_RES_24,
        isrc: String? = null,
    ): FlacStreamResult? {
        val cleanTrackId = trackId.trim()
        if (cleanTrackId.isEmpty()) return null

        val endpoint = customBaseUrl?.takeIf { it.isNotBlank() }?.let { if (it.endsWith("/api/dl")) it else "$it/api/dl" }
            ?: FlacRegistryManager.getQobuzEndpoint()
        val qualityCode = quality.toQualityCode()

        val jsonPayload = JSONObject().apply {
            put("id", cleanTrackId)
            put("quality", qualityCode)
        }.toString()

        val request = Request.Builder()
            .url(endpoint)
            .header("User-Agent", USER_AGENT)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .post(jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.tag(TAG).d("Qobuz endpoint returned %d for trackId %s", response.code, cleanTrackId)
                    return null
                }
                val body = response.body?.string() ?: return null
                parseQobuzResponse(body, cleanTrackId, quality, isrc)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error fetching Qobuz FLAC stream for trackId %s", cleanTrackId)
            null
        }
    }

    private fun parseQobuzResponse(
        jsonString: String,
        trackId: String,
        quality: FlacAudioQuality,
        isrc: String?,
    ): FlacStreamResult? {
        val root = JSONObject(jsonString)
        var streamUrl = root.optString("url").trim()

        if (streamUrl.isEmpty()) {
            val dataObj = root.optJSONObject("data")
            if (dataObj != null) {
                streamUrl = dataObj.optString("url").trim()
            }
        }

        if (streamUrl.isEmpty() || !streamUrl.startsWith("http")) {
            Timber.tag(TAG).d("No valid stream URL in Qobuz response")
            return null
        }

        val returnedQuality = root.optString("quality", quality.toQualityCode())
        val isHiRes = returnedQuality == "24" || quality == FlacAudioQuality.HI_RES_24
        val bitDepth = if (isHiRes) 24 else 16
        val sampleRate = if (isHiRes) 96000 else 44100
        val bitrate = if (isHiRes) 2304000 else 1411200

        return FlacStreamResult(
            mediaUri = streamUrl,
            provider = FlacProvider.QOBUZ,
            label = "Qobuz FLAC (${bitDepth}-bit/${sampleRate / 1000}kHz)",
            mimeType = "audio/flac",
            bitrate = bitrate,
            bitDepth = bitDepth,
            sampleRate = sampleRate,
            isrc = isrc,
            trackId = trackId,
            expiresAtMs = System.currentTimeMillis() + 10 * 60 * 1000L,
        )
    }
}
