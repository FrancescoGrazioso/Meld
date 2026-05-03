/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import io.ktor.serialization.kotlinx.json.json
import timber.log.Timber
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes

/**
 * SponsorBlock client. Fetches "skip" segments for a YouTube videoId from the
 * privacy-friendly hash-prefix endpoint and caches them in memory for the
 * lifetime of the process.
 *
 * The hash-prefix endpoint sends only the first 4 hex chars of SHA-256(videoId)
 * so the SponsorBlock server cannot tell which track the user is playing.
 */
object SponsorBlockManager {
    private const val BASE_URL = "https://sponsor.ajay.app"
    private const val HASH_PREFIX_LEN = 4

    @Serializable
    data class Segment(
        val category: String,
        val segment: List<Double>, // [startSec, endSec]
        val videoDuration: Double = 0.0,
        val actionType: String = "skip",
    ) {
        val startMs: Long get() = (segment.getOrNull(0) ?: 0.0).times(1000).toLong()
        val endMs: Long get() = (segment.getOrNull(1) ?: 0.0).times(1000).toLong()
    }

    @Serializable
    private data class HashPrefixEntry(
        val videoID: String,
        val segments: List<Segment> = emptyList(),
    )

    private val client by lazy {
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(
                    Json {
                        isLenient = true
                        ignoreUnknownKeys = true
                    }
                )
            }
            defaultRequest {
                url(BASE_URL)
            }
            expectSuccess = false
        }
    }

    private data class CacheEntry(val segments: List<Segment>, val fetchedAt: Long)

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val cacheTtlMs = 30.minutes.inWholeMilliseconds

    /**
     * Fetch "skip" segments for [videoId] limited to [categories]. Returns an
     * empty list when nothing applies, the network call fails, or the
     * response is malformed. Caches successful and empty responses for
     * [cacheTtlMs] to avoid hammering the API.
     */
    suspend fun fetchSegments(
        videoId: String,
        categories: Collection<String>,
    ): List<Segment> {
        if (videoId.isBlank() || categories.isEmpty()) return emptyList()

        cache[videoId]?.takeIf { System.currentTimeMillis() - it.fetchedAt < cacheTtlMs }?.let {
            return filterAndSort(it.segments, categories)
        }

        val hashPrefix = sha256(videoId).take(HASH_PREFIX_LEN)
        val all: List<HashPrefixEntry> = runCatching<List<HashPrefixEntry>> {
            client.get("/api/skipSegments/$hashPrefix") {
                // Request all categories we ever care about so we can cache once
                // and re-filter locally if the user changes the selection.
                com.metrolist.music.constants.SPONSORBLOCK_ALL_CATEGORIES.forEach {
                    parameter("category", it)
                }
                parameter("actionType", "skip")
            }.body<List<HashPrefixEntry>>()
        }.getOrElse { e ->
            Timber.tag("SponsorBlock").w("fetch failed for $videoId: ${e.message}")
            return emptyList()
        }

        val segments = all
            .firstOrNull { it.videoID.equals(videoId, ignoreCase = true) }
            ?.segments
            .orEmpty()

        cache[videoId] = CacheEntry(segments, System.currentTimeMillis())
        return filterAndSort(segments, categories)
    }

    fun cachedSegments(videoId: String, categories: Collection<String>): List<Segment> {
        val entry = cache[videoId] ?: return emptyList()
        if (System.currentTimeMillis() - entry.fetchedAt >= cacheTtlMs) return emptyList()
        return filterAndSort(entry.segments, categories)
    }

    fun clearCache() {
        cache.clear()
    }

    private fun filterAndSort(
        segments: List<Segment>,
        categories: Collection<String>,
    ): List<Segment> = segments
        .filter { it.actionType == "skip" && it.category in categories && it.endMs > it.startMs }
        .sortedBy { it.startMs }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xff
            if (v < 16) sb.append('0')
            sb.append(Integer.toHexString(v))
        }
        return sb.toString()
    }
}
