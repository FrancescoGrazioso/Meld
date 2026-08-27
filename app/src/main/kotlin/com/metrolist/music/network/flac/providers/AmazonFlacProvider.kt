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

class AmazonFlacProvider(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .writeTimeout(1500, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build(),
    private var customBaseUrl: String? = null,
) {
    companion object {
        private const val TAG = "AmazonFlacProvider"
        const val COMMUNITY_ENDPOINT = "https://amz-oss.spotbye.qzz.io/api/dl"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36"
    }

    fun resolveStream(
        asin: String,
        quality: FlacAudioQuality = FlacAudioQuality.HI_RES_24,
        isrc: String? = null,
    ): FlacStreamResult? {
        val cleanAsin = asin.trim()
        if (cleanAsin.isEmpty()) return null

        val endpoint = customBaseUrl?.takeIf { it.isNotBlank() }?.let { if (it.endsWith("/api/dl")) it else "$it/api/dl" }
            ?: FlacRegistryManager.getAmazonEndpoint()
        val qualityCode = quality.toQualityCode()

        val jsonPayload = JSONObject().apply {
            put("id", cleanAsin)
            put("quality", qualityCode)
            put("country", "US")
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
                    Timber.tag(TAG).d("Amazon endpoint returned %d for ASIN %s", response.code, cleanAsin)
                    return null
                }
                val body = response.body?.string() ?: return null
                parseAmazonResponse(body, cleanAsin, quality, isrc)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error fetching Amazon FLAC stream for ASIN %s", cleanAsin)
            null
        }
    }

    private fun parseAmazonResponse(
        jsonString: String,
        asin: String,
        quality: FlacAudioQuality,
        isrc: String?,
    ): FlacStreamResult? {
        val root = JSONObject(jsonString)
        var streamUrl = root.optString("stream_url").trim()

        if (streamUrl.isEmpty()) {
            streamUrl = root.optString("url").trim()
        }

        if (streamUrl.isEmpty() || !streamUrl.startsWith("http")) {
            Timber.tag(TAG).d("No valid stream URL in Amazon response")
            return null
        }

        val returnedBitDepth = root.optInt("bit_depth", if (quality == FlacAudioQuality.HI_RES_24) 24 else 16)
        val isHiRes = returnedBitDepth >= 24
        val bitDepth = if (isHiRes) 24 else 16
        val sampleRate = if (isHiRes) 96000 else 44100
        val bitrate = if (isHiRes) 2304000 else 1411200

        return FlacStreamResult(
            mediaUri = streamUrl,
            provider = FlacProvider.AMAZON,
            label = "Amazon Music FLAC (${bitDepth}-bit/${sampleRate / 1000}kHz)",
            mimeType = "audio/flac",
            bitrate = bitrate,
            bitDepth = bitDepth,
            sampleRate = sampleRate,
            isrc = isrc,
            trackId = asin,
            expiresAtMs = System.currentTimeMillis() + 10 * 60 * 1000L,
        )
    }
}
