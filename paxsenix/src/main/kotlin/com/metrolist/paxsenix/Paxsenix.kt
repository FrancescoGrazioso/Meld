package com.metrolist.paxsenix

import android.content.Context
import com.metrolist.music.betterlyrics.TTMLParser
import com.metrolist.paxsenix.models.LyricsResponse
import com.metrolist.paxsenix.models.SearchResponse
import com.metrolist.paxsenix.models.SearchResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.util.Locale
import kotlin.math.abs

object Paxsenix {
    @Volatile
    private var client: HttpClient? = null
    private var appVersion: String = "Unknown"

    fun init(context: Context) {
        if (client != null) return // Already initialized
        
        synchronized(this) {
            if (client != null) return
            
            appVersion = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
                    ?: "Unknown"
            } catch (e: Exception) {
                Timber.e(e, "Failed to get app version")
                "Unknown"
            }
            
            Timber.d("Initializing Paxsenix with version: $appVersion")
            
            client = HttpClient(CIO) {
                install(HttpTimeout) {
                    requestTimeoutMillis = 15000
                    connectTimeoutMillis = 10000
                }
                install(ContentNegotiation) {
                    json(
                        Json {
                            isLenient = true
                            ignoreUnknownKeys = true
                        },
                    )
                }

                defaultRequest {
                    url("https://lyrics.paxsenix.org")
                    header("User-Agent", "Metrolist/$appVersion")
                }

                expectSuccess = true
            }
            
            Timber.d("Paxsenix HTTP client initialized")
        }
    }

    private val httpClient: HttpClient
        get() = client ?: throw IllegalStateException("Paxsenix.init() must be called before using Paxsenix")

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
        Regex("""\s*\([^)]*\d{4}[^)]*\)""", RegexOption.IGNORE_CASE),
    )

    private val artistSeparators = listOf(" & ", " and ", ", ", " x ", " X ", " feat. ", " feat ", " ft. ", " ft ", " featuring ", " with ")

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

    private suspend fun search(query: String): List<SearchResult> = runCatching {
        Timber.d("Searching for: $query")
        val response = httpClient.get("/apple-music/search") {
            parameter("q", query)
        }.body<SearchResponse>()
        
        Timber.d("Search results count: ${response.size}")
        response.forEach { result ->
            Timber.v("  - ${result.displayName} by ${result.displayArtist} (ID: ${result.id}, Duration: ${result.duration})")
        }
        
        response
    }.getOrElse { e ->
        Timber.e(e, "Search error: ${e.message}")
        emptyList()
    }

    suspend fun getLyrics(
        title: String,
        artist: String,
        duration: Int,
        album: String? = null,
    ): Result<String> = runCatching {
        val cleanedTitle = cleanTitle(title)
        val cleanedArtist = cleanArtist(artist)
        
        Timber.d("getLyrics called: title='$title', artist='$artist', duration=$duration, album=$album")
        Timber.d("Cleaned: title='$cleanedTitle', artist='$cleanedArtist'")
        
        // Try multiple search queries for better matching
        val searchQueries = buildList {
            add("$cleanedTitle $cleanedArtist")
            add(cleanedTitle) // Just title as fallback
            if (!album.isNullOrBlank()) {
                add("$cleanedTitle $cleanedArtist $album")
            }
        }
        
        var allResults: List<Pair<SearchResult, Double>> = emptyList()
        
        for (query in searchQueries) {
            if (allResults.isEmpty()) {
                Timber.d("Trying search query: $query")
                val searchResults = search(query)
                
                if (searchResults.isNotEmpty()) {
                    allResults = scoreAndFilterResults(searchResults, title, artist, duration)
                }
            }
        }
        
        if (allResults.isEmpty()) {
            Timber.w("No tracks found for any query")
            throw IllegalStateException("No tracks found on Paxsenix")
        }
        
        // First, try to find exact title match with word timings
        // Only prioritize word timings if title also matches well
        for (item in allResults.take(3)) {
            val result = item.first
            val score = item.second
            
            Timber.d("Trying: ${result.displayName} (ID: ${result.id}, dur: ${result.duration}, score: $score)")
            
            val (lrc, hasWordTimings) = fetchLyricsForTrackWithType(result.id)
            
            if (lrc.isNotEmpty()) {
                Timber.d("Got lyrics, hasWordTimings=$hasWordTimings")
                
                if (!hasWordTimings) {
                    continue
                }
                
                return Result.success(lrc)
            }
        }
        
        // Return first valid result if no good title match found
        val firstResult = allResults.first().first
        Timber.d("Returning: ${firstResult.displayName}")
        val (lrc, hasWordTimings) = fetchLyricsForTrackWithType(firstResult.id)
        if (lrc.isNotEmpty() && hasWordTimings) {
            return Result.success(lrc)
        }
        Timber.w("No word-by-word lyrics found from Paxsenix, letting other providers handle it")
        return Result.failure(IllegalStateException("No word-by-word lyrics available from Paxsenix"))
    }
    
    private fun scoreAndFilterResults(
        results: List<SearchResult>,
        title: String,
        artist: String,
        duration: Int
    ): List<Pair<SearchResult, Double>> {
        val durationMs = duration * 1000
        val cleanupRegex = Regex("""\s*\(.*?\)|\s*\[.*?\]""")
        
        // Cleaned versions for fuzzy matching
        val cleanedTitle = title.replace(cleanupRegex, "").lowercase().trim()
        val cleanedArtist = cleanArtist(artist).lowercase()
        
        // Track if target has version markers
        val targetIsMixed = title.contains("mixed", ignoreCase = true)
        val targetIsRemix = title.contains("remix", ignoreCase = true)
        
        return results.map { result ->
            var score = 0.0
            
            val resultTitle = result.displayName
            val resultArtist = result.displayArtist
            
            result.duration?.let { d ->
                val diff = abs(d - durationMs)
                when {
                    diff <= 2000 -> score += 100 // Excellent match
                    diff <= 5000 -> score += 50  // Good match
                    diff <= 10000 -> score += 10 // Acceptable match
                    else -> score -= 50          // Likely wrong version (Mixed/Edit/etc)
                }
            }
            
            val resultTitleCleaned = resultTitle.replace(cleanupRegex, "").lowercase().trim()
            
            when {
                resultTitleCleaned == cleanedTitle -> score += 80
                resultTitleCleaned.contains(cleanedTitle) || cleanedTitle.contains(resultTitleCleaned) -> score += 40
            }
            
            // Penalize version mismatch
            val resultIsMixed = resultTitle.contains("mixed", ignoreCase = true)
            val resultIsRemix = resultTitle.contains("remix", ignoreCase = true)
            
            if (resultIsMixed && !targetIsMixed) score -= 60
            if (resultIsRemix && !targetIsRemix) score -= 40
            
            val resultArtistLower = resultArtist.lowercase()
            val targetArtistPrimary = cleanedArtist
            
            when {
                resultArtistLower.contains(targetArtistPrimary) -> score += 50
                else -> {
                    val artistWords = targetArtistPrimary.split(Regex("\\s+")).filter { it.length > 2 }
                    if (artistWords.any { resultArtistLower.contains(it) }) {
                        score += 25
                    }
                }
            }
            
            Timber.v("  Score for '${resultTitle}': $score (dur=${result.duration}, targetDur=$durationMs)")
            result to score
        }.sortedByDescending { it.second }.filter { it.second > 0 }.take(10)
    }

    private suspend fun fetchLyricsForTrackWithType(id: String): Pair<String, Boolean> {
        val result = fetchLyricsForTrack(id)
        if (result.isSuccess) {
            val lrc = result.getOrNull()!!
            val hasWordTimings = lrc.contains("<") && lrc.contains(">")
            return lrc to hasWordTimings
        }
        return "" to false
    }

    private suspend fun fetchLyricsForTrack(id: String): Result<String> = runCatching {
        Timber.d("Fetching lyrics for track ID: $id")
        
        val response = httpClient.get("/apple-music/lyrics") {
            parameter("id", id)
        }.body<LyricsResponse>()
        
        val lyricsType = response.type
        Timber.d("Lyrics response: type=$lyricsType")
        
        // Prioritize ttmlContent using the robust TTMLParser
        if (!response.ttmlContent.isNullOrBlank()) {
            val lrc = convertTTMLToAppFormat(response.ttmlContent)
            if (lrc.isNotEmpty()) {
                Timber.d("Generated LRC from ttmlContent using TTMLParser")
                return@runCatching lrc
            }
        }

        // Fallback to ELRC formats if TTML failed or is missing
        if (!response.elrcMultiPerson.isNullOrBlank()) {
            Timber.d("Using elrcMultiPerson as fallback")
            return@runCatching response.elrcMultiPerson
        }
        if (!response.elrc.isNullOrBlank()) {
            Timber.d("Using elrc as fallback")
            return@runCatching response.elrc
        }

        if (!response.plain.isNullOrBlank()) {
            Timber.d("Using plain lyrics field")
            return@runCatching response.plain
        }

        if (response.content.isEmpty()) {
            throw IllegalStateException("No lyrics found")
        }
        
        val hasWordLevel = lyricsType == "Syllable"
        Timber.d("Using content array as source, hasWordLevel=$hasWordLevel")

        if (!hasWordLevel) {
            // Non-synced: return as plain text with no timestamps
            val plain = response.content
                .map { line -> line.text.joinToString(" ") { it.text } }
                .filter { it.isNotBlank() }
                .joinToString("\n")
            Timber.d("Generated plain (non-synced) lyrics: ${response.content.size} lines")
            return@runCatching plain
        }

        val lrc = buildString {
            response.content.forEach { line ->
                val timeMs = line.timestamp
                val minutes = timeMs / 1000 / 60
                val seconds = (timeMs / 1000) % 60
                val centiseconds = (timeMs % 1000) / 10

                val agent = when {
                    line.background -> "{bg}"
                    line.oppositeTurn -> "{agent:v2}"
                    else -> "{agent:v1}"
                }

                val lineText = line.text.joinToString(" ") { it.text }

                if (lineText.isNotBlank()) {
                    appendLine(String.format(Locale.US, "[%02d:%02d.%02d]%s%s", minutes, seconds, centiseconds, agent, lineText))

                    if (line.text.isNotEmpty()) {
                        val wordsData = line.text.joinToString("|") { word ->
                            "${word.text}:${word.timestamp.toDouble() / 1000}:${word.endtime.toDouble() / 1000}"
                        }
                        if (wordsData.isNotEmpty()) {
                            appendLine("<$wordsData>")
                        }
                    }
                }
            }
        }

        Timber.d("Generated ${response.content.size} lines from content array")
        return@runCatching lrc
    }

    suspend fun getAllLyrics(
        title: String,
        artist: String,
        duration: Int,
        album: String? = null,
        callback: (String) -> Unit,
    ) {
        val cleanedTitle = cleanTitle(title)
        val cleanedArtist = cleanArtist(artist)

        val searchQueries = listOf(
            "$cleanedTitle $cleanedArtist",
            cleanedTitle
        )

        var plainFallback: String? = null

        var scoredResults: List<Pair<SearchResult, Double>> = emptyList()
        searchLoop@ for (query in searchQueries) {
            val results = search(query)
            if (results.isEmpty()) continue

            val filtered = scoreAndFilterResults(results, title, artist, duration)
            if (filtered.isNotEmpty()) {
                scoredResults = filtered
                break@searchLoop
            }
        }

        for ((result, _) in scoredResults.take(3)) {
            Timber.d("Trying lyrics for: ${result.displayName}")
            val (lrc, hasWordTimings) = fetchLyricsForTrackWithType(result.id)
            if (lrc.isNotEmpty()) {
                if (hasWordTimings) {
                    callback(lrc)
                    return
                } else if (plainFallback == null) {
                    Timber.d("Storing plain lyrics as fallback from: ${result.displayName}")
                    plainFallback = lrc
                }
            }
        }

        // No word-by-word found — offer plain lyrics as fallback option, like other providers do
        plainFallback?.let {
            Timber.d("Offering plain/non-synced lyrics as fallback")
            callback(it)
        }
    }
    
    /**
     * Convert TTML format to app format with v1/v2/bg support
     * TTML has native agent info via ttm:agent="v1" or ttm:agent="v2"
     */
    private fun convertTTMLToAppFormat(ttml: String): String {
        return try {
            val parsedLines = TTMLParser.parseTTML(ttml)
            TTMLParser.toLRC(parsedLines)
        } catch (e: Exception) {
            Timber.e(e, "TTML conversion failed: ${e.message}")
            ""
        }
    }
    
    data class ManualSearchResult(
        val id: String,
        val title: String,
        val artist: String,
        val album: String?,
        val duration: Int?,
        val hasWordSync: Boolean,
        val lyrics: String?
    )
    
    /**
     * Manual search that returns both standard and word-by-word lyrics for user to choose
     */
    suspend fun manualSearch(
        title: String,
        artist: String,
        duration: Int,
        album: String? = null,
    ): List<ManualSearchResult> {
        val cleanedTitle = cleanTitle(title)
        val cleanedArtist = cleanArtist(artist)
        
        val searchQuery = if (!album.isNullOrBlank()) {
            "$cleanedTitle $cleanedArtist $album"
        } else {
            "$cleanedTitle $cleanedArtist"
        }
        
        val results = search(searchQuery)
        
        if (results.isEmpty()) return emptyList()
        
        val scoredResults = scoreAndFilterResults(results, title, artist, duration)
        
        return scoredResults.take(5).map { (result, _) ->
            var hasWordSync = false
            var lyrics: String? = null
            
            try {
                val (fetchedLyrics, wordSync) = fetchLyricsForTrackWithType(result.id)
                if (fetchedLyrics.isNotEmpty()) {
                    lyrics = fetchedLyrics
                    hasWordSync = wordSync
                }
            } catch (e: Exception) {
                Timber.e(e, "Manual search lyrics fetch failed")
            }
            
            ManualSearchResult(
                id = result.id,
                title = result.displayName,
                artist = result.displayArtist,
                album = result.albumName,
                duration = result.duration,
                hasWordSync = hasWordSync,
                lyrics = lyrics
            )
        }
    }
}
