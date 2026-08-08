/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import android.content.Context
import com.metrolist.music.constants.EnableNetEaseKey
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import kotlin.math.abs

@Serializable
private data class NetEaseArtist(
    val name: String? = null,
)

@Serializable
private data class NetEaseSong(
    val id: Long,
    val name: String? = null,
    val artists: List<NetEaseArtist>? = null,
    val duration: Long? = null,
)

@Serializable
private data class NetEaseSearchResult(
    val songs: List<NetEaseSong>? = null,
)

@Serializable
private data class NetEaseSearchResponse(
    val result: NetEaseSearchResult? = null,
)

@Serializable
private data class NetEaseLyricContent(
    val lyric: String? = null,
)

@Serializable
private data class NetEaseLyricResponse(
    val lrc: NetEaseLyricContent? = null,
)

object NetEaseLyricsProvider : LyricsProvider {
    override val name = "NetEase"

    private val client by lazy {
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(
                    Json {
                        isLenient = true
                        ignoreUnknownKeys = true
                    },
                )
            }

            install(HttpTimeout) {
                requestTimeoutMillis = 5000
                connectTimeoutMillis = 3000
                socketTimeoutMillis = 5000
            }

            expectSuccess = false
        }
    }

    override fun isEnabled(context: Context): Boolean = context.dataStore[EnableNetEaseKey] ?: true

    private val titleCleanupPatterns = listOf(
        Regex("""\s*\(.*?(official|video|audio|lyrics|lyric|visualizer|hd|hq|4k|remaster|remix|live|acoustic|version|edit|extended|radio|clean|explicit).*?\)""", RegexOption.IGNORE_CASE),
        Regex("""\s*\[.*?(official|video|audio|lyrics|lyric|visualizer|hd|hq|4k|remaster|remix|live|acoustic|version|edit|extended|radio|clean|explicit).*?\]""", RegexOption.IGNORE_CASE),
        Regex("""\s*【.*?】"""),
        Regex("""\s*\|.*$"""),
        Regex("""\s*-\s*(official|video|audio|lyrics|lyric|visualizer).*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*\(feat\..*?\)""", RegexOption.IGNORE_CASE),
        Regex("""\s*\(ft\..*?\)""", RegexOption.IGNORE_CASE),
        Regex("""\s*feat\..*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*ft\..*$""", RegexOption.IGNORE_CASE),
    )

    private val artistSeparators = listOf(" & ", " and ", ", ", " x ", " X ", " × ", " / ", " | ", " 、 ", " feat. ", " feat ", " ft. ", " ft ", " featuring ", " with ")

    private fun cleanTitle(title: String): String {
        var cleaned = title.trim()
        for (pattern in titleCleanupPatterns) {
            cleaned = cleaned.replace(pattern, "")
        }
        return cleaned.trim()
    }

    private fun cleanArtist(artist: String): String {
        var cleaned = artist.trim()
        for (separator in artistSeparators) {
            if (cleaned.contains(separator, ignoreCase = true)) {
                cleaned = cleaned.split(separator, ignoreCase = true, limit = 2)[0]
                break
            }
        }
        return cleaned.trim()
    }

    private val creditKeywords = listOf(
        "作词", "作曲", "编曲", "制作人", "混音", "母带", "录音", "吉他", "贝斯", "鼓", "键盘", "和声", "企划", "监制", "出品",
        "Lyricist", "Composer", "Arranger", "Producer", "Mixing", "Mastering", "Recorded", "Guitar", "Bass", "Drums", "Written by"
    )

    private fun parseTimestampMs(timestampStr: String): Long {
        val clean = timestampStr.removeSurrounding("[", "]")
        val parts = clean.split(":", ".")
        if (parts.size >= 3) {
            val min = parts[0].toLongOrNull() ?: 0L
            val sec = parts[1].toLongOrNull() ?: 0L
            var ms = parts[2].toLongOrNull() ?: 0L
            if (parts[2].length == 2) ms *= 10
            return min * 60000L + sec * 1000L + ms
        }
        return 0L
    }

    private fun sanitizeLrc(lrc: String, title: String, artist: String): String {
        val timestampRegex = Regex("""^\[\d{2}:\d{2}\.\d{2,3}\]""")
        val cleanedTitle = cleanTitle(title).lowercase()
        val cleanedArtist = cleanArtist(artist).lowercase()

        val rawLines = lrc.lineSequence()
            .map { it.trim() }
            .filter { line ->
                if (line.isBlank()) return@filter false

                if (line.startsWith("[ti:") || line.startsWith("[ar:") || line.startsWith("[al:") ||
                    line.startsWith("[by:") || line.startsWith("[offset:") || line.startsWith("[total:")
                ) {
                    return@filter false
                }

                val match = timestampRegex.find(line)
                if (match != null) {
                    val content = line.substring(match.value.length).trim()
                    if (content.isBlank()) return@filter false

                    if (creditKeywords.any { content.contains(it, ignoreCase = true) }) {
                        return@filter false
                    }
                }

                true
            }
            .toList()

        data class LineMeta(val rawLine: String, val timestampMs: Long, val content: String)

        val entries = rawLines.mapNotNull { line ->
            val match = timestampRegex.find(line)
            if (match != null) {
                val timeMs = parseTimestampMs(match.value)
                val content = line.substring(match.value.length).trim()
                if (content.isNotBlank()) LineMeta(line, timeMs, content) else null
            } else null
        }

        if (entries.isEmpty()) return lrc

        val contentOccurrences = entries.groupBy { it.content.lowercase() }

        val validEntries = entries.filter { entry ->
            val contentLower = entry.content.lowercase()

            if (entry.timestampMs < 3500L) {
                if (contentLower.contains(cleanedTitle) || contentLower.contains(cleanedArtist) || contentLower.contains(" - ")) {
                    return@filter false
                }
                val totalCopies = contentOccurrences[contentLower]?.size ?: 0
                if (totalCopies > 1 && contentOccurrences[contentLower]?.any { it.timestampMs >= 3500L } == true) {
                    return@filter false
                }
            }

            true
        }

        val resultLines = mutableListOf<String>()
        var lastContent = ""

        for (entry in validEntries) {
            if (entry.content.equals(lastContent, ignoreCase = true)) {
                continue
            }
            resultLines.add(entry.rawLine)
            lastContent = entry.content
        }

        return resultLines.joinToString("\n")
    }

    private suspend fun searchSongs(query: String): List<NetEaseSong> = runCatching {
        val response = client.get("https://music.163.com/api/search/get") {
            parameter("s", query)
            parameter("type", 1)
            parameter("limit", 5)
            parameter("offset", 0)
            header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
        }
        if (response.status == HttpStatusCode.OK) {
            response.body<NetEaseSearchResponse>().result?.songs ?: emptyList()
        } else emptyList()
    }.getOrDefault(emptyList())

    private suspend fun fetchLyricsById(songId: Long): String? = runCatching {
        val response = client.get("https://music.163.com/api/song/lyric") {
            parameter("id", songId)
            parameter("lv", 1)
            parameter("kv", 1)
            parameter("tv", 0)
            header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
        }
        if (response.status == HttpStatusCode.OK) {
            val body = response.body<NetEaseLyricResponse>()
            body.lrc?.lyric?.takeIf { it.isNotBlank() }
        } else null
    }.getOrNull()

    override suspend fun getLyrics(
        context: Context,
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<String> = runCatching {
        val cleanedTitle = cleanTitle(title)
        val cleanedArtist = cleanArtist(artist)
        val durationMs = duration.toLong() * 1000

        val queries = listOf(
            "$cleanedTitle $cleanedArtist",
            cleanedTitle
        )

        for (query in queries) {
            val songs = searchSongs(query)
            if (songs.isNotEmpty()) {
                val matchedSong = if (duration > 0) {
                    songs.firstOrNull { song ->
                        song.duration != null && abs(song.duration - durationMs) <= 6000
                    } ?: songs.first()
                } else {
                    songs.first()
                }

                val lrc = fetchLyricsById(matchedSong.id)
                if (!lrc.isNullOrBlank()) {
                    val sanitized = sanitizeLrc(lrc, title, artist)
                    if (sanitized.isNotBlank()) {
                        return@runCatching sanitized
                    }
                }
            }
        }
        throw IllegalStateException("Lyrics unavailable from NetEase")
    }

    override suspend fun getAllLyrics(
        context: Context,
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
        callback: (String) -> Unit,
    ) {
        getLyrics(context, id, title, artist, duration, album)
            .onSuccess { lrcString ->
                callback(lrcString)
            }
    }
}
