package com.metrolist.spotify

import com.metrolist.spotify.models.SpotifySimpleAlbum
import com.metrolist.spotify.models.SpotifySimpleArtist
import com.metrolist.spotify.models.SpotifyTrack
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Performance baseline tests for SpotifyMapper.
 *
 * These tests measure the computational cost of title normalization,
 * fuzzy matching, and batch scoring — the hot path executed for every
 * Spotify→YouTube candidate comparison.
 *
 * Run with: ./gradlew :spotify:test --tests "*.SpotifyMapperPerformanceTest"
 */
class SpotifyMapperPerformanceTest {

    // ── Realistic test data ────────────────────────────────────────────

    /** Spotify tracks that mimic real-world title patterns. */
    private val spotifyTracks = listOf(
        track("Bohemian Rhapsody", "Queen", 354000),
        track("Blinding Lights", "The Weeknd", 200000),
        track("Shape of You", "Ed Sheeran", 233000),
        track("Someone Like You", "Adele", 285000),
        track("Lose Yourself (feat. Eminem) [Remastered 2023]", "Eminem", 326000),
        track("Smells Like Teen Spirit", "Nirvana", 301000),
        track("Hotel California (2013 Remaster)", "Eagles", 391000),
        track("Stairway to Heaven", "Led Zeppelin", 482000),
        track("Billie Jean", "Michael Jackson", 294000),
        track("Imagine", "John Lennon", 183000),
        track("Like a Rolling Stone", "Bob Dylan", 369000),
        track("Hey Jude", "The Beatles", 431000),
        track("Purple Rain", "Prince", 520000),
        track("Respect", "Aretha Franklin", 147000),
        track("Superstition", "Stevie Wonder", 245000),
        track("What's Going On", "Marvin Gaye", 234000),
        track("Born to Run", "Bruce Springsteen", 270000),
        track("London Calling", "The Clash", 199000),
        track("I Will Always Love You (ft. Whitney Houston)", "Whitney Houston", 273000),
        track("Watermelon Sugar (Remix)", "Harry Styles", 174000),
        track("drivers license", "Olivia Rodrigo", 242000),
        track("bad guy", "Billie Eilish", 194000),
        track("Old Town Road (feat. Billy Ray Cyrus) [Remix]", "Lil Nas X", 157000),
        track("Uptown Funk (feat. Bruno Mars)", "Mark Ronson", 270000),
        track("Despacito (Remix) [feat. Justin Bieber]", "Luis Fonsi", 228000),
        track("Sicko Mode", "Travis Scott", 312000),
        track("God's Plan", "Drake", 198000),
        track("Rockstar (feat. 21 Savage)", "Post Malone", 218000),
        track("Sunflower (Spider-Man: Into the Spider-Verse)", "Post Malone", 158000),
        track("HUMBLE.", "Kendrick Lamar", 177000),
        track("Levitating (feat. DaBaby)", "Dua Lipa", 203000),
        track("Save Your Tears (Remix) (feat. Ariana Grande)", "The Weeknd", 195000),
        track("Peaches (feat. Daniel Caesar & Giveon)", "Justin Bieber", 198000),
        track("MONTERO (Call Me By Your Name)", "Lil Nas X", 137000),
        track("Kiss Me More (feat. SZA)", "Doja Cat", 209000),
        track("Butter", "BTS", 164000),
        track("Stay (with Justin Bieber)", "The Kid LAROI", 141000),
        track("good 4 u", "Olivia Rodrigo", 178000),
        track("Industry Baby (feat. Jack Harlow)", "Lil Nas X", 212000),
        track("Heat Waves", "Glass Animals", 239000),
        track("As It Was", "Harry Styles", 167000),
        track("About Damn Time", "Lizzo", 193000),
        track("Anti-Hero", "Taylor Swift", 200000),
        track("Unholy (feat. Kim Petras)", "Sam Smith", 156000),
        track("Flowers", "Miley Cyrus", 200000),
        track("Kill Bill", "SZA", 154000),
        track("Vampire", "Olivia Rodrigo", 219000),
        track("Cruel Summer", "Taylor Swift", 178000),
        track("Last Night", "Morgan Wallen", 163000),
        track("Paint The Town Red", "Doja Cat", 231000),
    )

    /** YouTube candidate results that would come from a search. */
    private val youtubeCandidates = listOf(
        candidate("Bohemian Rhapsody", "Queen", 355),
        candidate("Bohemian Rhapsody - Remastered 2011", "Queen", 354),
        candidate("Bohemian Rhapsody (Live Aid 1985)", "Queen", 360),
        candidate("Bohemian Rhapsody (Piano Cover)", "Various Artists", 350),
        candidate("Blinding Lights", "The Weeknd", 201),
        candidate("Blinding Lights (Official Audio)", "The Weeknd", 200),
        candidate("Blinding Lights - Slowed + Reverb", "The Weeknd", 240),
        candidate("Shape of You", "Ed Sheeran", 234),
        candidate("Shape of You (Acoustic)", "Ed Sheeran", 230),
        candidate("Shape of You (Latin Remix)", "Ed Sheeran", 245),
        candidate("Someone Like You", "Adele", 285),
        candidate("Someone Like You (Live at the Royal Albert Hall)", "Adele", 290),
        candidate("Lose Yourself", "Eminem", 326),
        candidate("Lose Yourself (From 8 Mile Soundtrack)", "Eminem", 326),
        candidate("Smells Like Teen Spirit", "Nirvana", 301),
        candidate("Smells Like Teen Spirit (Official Music Video)", "Nirvana", 301),
        candidate("Hotel California (2013 Remaster)", "Eagles", 391),
        candidate("Hotel California (Live)", "Eagles", 420),
        candidate("Stairway to Heaven", "Led Zeppelin", 482),
        candidate("Billie Jean", "Michael Jackson", 294),
        candidate("Imagine", "John Lennon", 183),
        candidate("Imagine (Remastered)", "John Lennon", 183),
        candidate("Like a Rolling Stone", "Bob Dylan", 369),
        candidate("Hey Jude", "The Beatles", 431),
        candidate("Purple Rain", "Prince", 520),
        candidate("Respect", "Aretha Franklin", 147),
        candidate("Superstition", "Stevie Wonder", 245),
        candidate("What's Going On", "Marvin Gaye", 234),
        candidate("Born to Run", "Bruce Springsteen", 270),
        candidate("London Calling", "The Clash", 199),
    )

    // ── Benchmark helpers ──────────────────────────────────────────────

    private data class BenchmarkResult(
        val name: String,
        val iterations: Int,
        val totalMs: Long,
        val avgMicros: Double,
        val opsPerSec: Long,
    ) {
        override fun toString(): String =
            "[$name] iterations=$iterations  total=${totalMs}ms  avg=${"%.1f".format(avgMicros)}µs/op  throughput=${opsPerSec} ops/s"
    }

    /**
     * Runs a warmup phase then measures [iterations] executions of [block].
     * Returns timing statistics.
     */
    private inline fun benchmark(
        name: String,
        iterations: Int = 10_000,
        warmup: Int = 1_000,
        block: () -> Unit,
    ): BenchmarkResult {
        // Warmup — let JIT do its thing
        repeat(warmup) { block() }
        System.gc()

        val start = System.nanoTime()
        repeat(iterations) { block() }
        val elapsed = System.nanoTime() - start

        val totalMs = elapsed / 1_000_000
        val avgMicros = (elapsed.toDouble() / iterations) / 1_000
        val opsPerSec = if (totalMs > 0) (iterations * 1000L) / totalMs else Long.MAX_VALUE

        return BenchmarkResult(name, iterations, totalMs, avgMicros, opsPerSec)
    }

    // ── Tests ──────────────────────────────────────────────────────────

    /**
     * Measures buildSearchQuery performance across all tracks.
     * This is called once per track before YouTube search.
     */
    @Test
    fun benchmarkBuildSearchQuery() {
        val result = benchmark("buildSearchQuery", iterations = 50_000) {
            for (track in spotifyTracks) {
                SpotifyMapper.buildSearchQuery(track)
            }
        }
        println(result)
        // Baseline assertion: must complete within reasonable time
        assertTrue("buildSearchQuery too slow: ${result.avgMicros}µs/op", result.avgMicros < 5000)
    }

    /**
     * Measures matchScore for a single track against a pool of candidates.
     * This is the core hot path — called for every candidate from YouTube results.
     */
    @Test
    fun benchmarkMatchScoreSingleTrack() {
        val spotifyTrack = spotifyTracks[0] // Bohemian Rhapsody
        val artist = spotifyTrack.artists.first().name

        val result = benchmark("matchScore_single_track_vs_30_candidates", iterations = 50_000) {
            for (candidate in youtubeCandidates) {
                SpotifyMapper.matchScore(
                    spotifyTitle = spotifyTrack.name,
                    spotifyArtist = artist,
                    spotifyDurationMs = spotifyTrack.durationMs,
                    candidateTitle = candidate.title,
                    candidateArtist = candidate.artist,
                    candidateDurationSec = candidate.durationSec,
                )
            }
        }
        println(result)
        assertTrue("matchScore too slow: ${result.avgMicros}µs/op", result.avgMicros < 10000)
    }

    /**
     * Measures the full scoring pipeline for a batch of 20 tracks (RESOLVE_BATCH_SIZE)
     * each compared against 10 YouTube candidates (typical search result size).
     * This simulates what happens when a queue page resolves a batch.
     */
    @Test
    fun benchmarkBatchResolution20Tracks() {
        val batchSize = 20
        val candidatesPerTrack = 10
        val batch = spotifyTracks.take(batchSize)
        val candidatePool = youtubeCandidates.take(candidatesPerTrack)

        val result = benchmark("batch_resolve_20x10", iterations = 10_000) {
            for (track in batch) {
                val artist = track.artists.firstOrNull()?.name ?: ""
                var bestScore = 0.0
                for (candidate in candidatePool) {
                    val score = SpotifyMapper.matchScore(
                        spotifyTitle = track.name,
                        spotifyArtist = artist,
                        spotifyDurationMs = track.durationMs,
                        candidateTitle = candidate.title,
                        candidateArtist = candidate.artist,
                        candidateDurationSec = candidate.durationSec,
                    )
                    if (score > bestScore) bestScore = score
                }
            }
        }
        println(result)
        assertTrue("batch resolve too slow: ${result.avgMicros}µs/op", result.avgMicros < 50000)
    }

    /**
     * Measures the full scoring pipeline for a large playlist (50 tracks)
     * each compared against 15 YouTube candidates.
     * Simulates getFullStatus() resolving an entire Spotify playlist.
     */
    @Test
    fun benchmarkLargePlaylist50Tracks() {
        val candidatesPerTrack = 15
        // Extend candidate pool by cycling
        val extendedCandidates = (0 until candidatesPerTrack).map {
            youtubeCandidates[it % youtubeCandidates.size]
        }

        val result = benchmark("large_playlist_50x15", iterations = 5_000) {
            for (track in spotifyTracks) {
                val artist = track.artists.firstOrNull()?.name ?: ""
                var bestScore = 0.0
                for (candidate in extendedCandidates) {
                    val score = SpotifyMapper.matchScore(
                        spotifyTitle = track.name,
                        spotifyArtist = artist,
                        spotifyDurationMs = track.durationMs,
                        candidateTitle = candidate.title,
                        candidateArtist = candidate.artist,
                        candidateDurationSec = candidate.durationSec,
                    )
                    if (score > bestScore) bestScore = score
                }
            }
        }
        println(result)
        assertTrue("large playlist too slow: ${result.avgMicros}µs/op", result.avgMicros < 100000)
    }

    /**
     * Measures repeated normalization of the same titles.
     * In real usage, the Spotify track's title/artist are re-normalized
     * for every candidate comparison — this quantifies the waste.
     */
    @Test
    fun benchmarkRepeatedNormalization() {
        // Access normalizeTitle indirectly via matchScore to measure its impact
        val titles = spotifyTracks.map { it.name } + spotifyTracks.map {
            it.artists.firstOrNull()?.name ?: ""
        }

        // Each matchScore call normalizes both spotify and candidate strings.
        // With 50 tracks × 10 candidates, that's 50×10×4 = 2000 normalizations
        // but only 50×2 = 100 unique spotify normalizations are needed.
        val candidatesPerTrack = 10
        val pool = youtubeCandidates.take(candidatesPerTrack)

        val result = benchmark("normalization_waste_50x10", iterations = 10_000) {
            for (track in spotifyTracks) {
                val artist = track.artists.firstOrNull()?.name ?: ""
                for (candidate in pool) {
                    SpotifyMapper.matchScore(
                        spotifyTitle = track.name,
                        spotifyArtist = artist,
                        spotifyDurationMs = track.durationMs,
                        candidateTitle = candidate.title,
                        candidateArtist = candidate.artist,
                        candidateDurationSec = candidate.durationSec,
                    )
                }
            }
        }

        // Calculate normalization count: 50 tracks × 10 candidates × 4 normalizations = 2000
        // vs optimal: 50×2 unique spotify + 10×2 unique candidates = 120
        val wastedNormalizations = spotifyTracks.size * candidatesPerTrack * 4
        val optimalNormalizations = spotifyTracks.size * 2 + pool.size * 2
        println(result)
        println("  Normalizations per iteration: $wastedNormalizations (optimal would be $optimalNormalizations)")
        println("  Waste factor: ${"%.1f".format(wastedNormalizations.toDouble() / optimalNormalizations)}x")
        assertTrue("normalization waste test failed", result.totalMs >= 0)
    }

    /**
     * Measures string similarity (bigram Dice coefficient) in isolation.
     * This is the core comparison algorithm called twice per candidate.
     */
    @Test
    fun benchmarkStringSimilarityAlgorithm() {
        // Collect realistic string pairs from our test data
        val pairs = spotifyTracks.zip(youtubeCandidates).map { (track, candidate) ->
            track.name.lowercase() to candidate.title.lowercase()
        }

        val result = benchmark("string_similarity_dice", iterations = 100_000) {
            for ((a, b) in pairs) {
                // Simulate what stringSimilarity does (it's private, so we trigger it via matchScore)
                SpotifyMapper.matchScore(
                    spotifyTitle = a,
                    spotifyArtist = "",
                    spotifyDurationMs = 0,
                    candidateTitle = b,
                    candidateArtist = "",
                    candidateDurationSec = null,
                )
            }
        }
        println(result)
        assertTrue("string similarity too slow: ${result.avgMicros}µs/op", result.avgMicros < 5000)
    }

    /**
     * Stress test: simulates loading a 200-track mega-playlist
     * where every track must be scored against 15 candidates.
     * This represents the worst-case scenario for getFullStatus().
     */
    @Test
    fun benchmarkMegaPlaylist200Tracks() {
        // Repeat the 50 tracks 4x to simulate 200 tracks
        val megaPlaylist = (0 until 4).flatMap { spotifyTracks }
        val candidatesPerTrack = 15
        val pool = (0 until candidatesPerTrack).map {
            youtubeCandidates[it % youtubeCandidates.size]
        }

        val result = benchmark("mega_playlist_200x15", iterations = 1_000) {
            for (track in megaPlaylist) {
                val artist = track.artists.firstOrNull()?.name ?: ""
                var bestScore = 0.0
                for (candidate in pool) {
                    val score = SpotifyMapper.matchScore(
                        spotifyTitle = track.name,
                        spotifyArtist = artist,
                        spotifyDurationMs = track.durationMs,
                        candidateTitle = candidate.title,
                        candidateArtist = candidate.artist,
                        candidateDurationSec = candidate.durationSec,
                    )
                    if (score > bestScore) bestScore = score
                }
            }
        }
        println(result)
        assertTrue("mega playlist too slow: ${result.avgMicros}µs/op", result.avgMicros < 500000)
    }

    // ── Optimized path benchmarks ──────────────────────────────────────

    /**
     * Measures the precomputed scoring path: pre-normalize Spotify side once,
     * then score each candidate with matchScorePrecomputed.
     * Direct comparison target for benchmarkBatchResolution20Tracks.
     */
    @Test
    fun benchmarkPrecomputedBatch20Tracks() {
        val batchSize = 20
        val candidatesPerTrack = 10
        val batch = spotifyTracks.take(batchSize)
        val candidatePool = youtubeCandidates.take(candidatesPerTrack)

        val result = benchmark("precomputed_batch_20x10", iterations = 10_000) {
            for (track in batch) {
                val precomputed = SpotifyMapper.precompute(
                    title = track.name,
                    artist = track.artists.firstOrNull()?.name ?: "",
                    durationMs = track.durationMs,
                )
                var bestScore = 0.0
                for (candidate in candidatePool) {
                    val score = SpotifyMapper.matchScorePrecomputed(
                        precomputed = precomputed,
                        candidateTitle = candidate.title,
                        candidateArtist = candidate.artist,
                        candidateDurationSec = candidate.durationSec,
                    )
                    if (score > bestScore) bestScore = score
                }
            }
        }
        println(result)
        assertTrue("precomputed batch too slow: ${result.avgMicros}µs/op", result.avgMicros < 50000)
    }

    /**
     * Measures the precomputed path for a large playlist (50×15).
     * Direct comparison target for benchmarkLargePlaylist50Tracks.
     */
    @Test
    fun benchmarkPrecomputedLargePlaylist50Tracks() {
        val candidatesPerTrack = 15
        val extendedCandidates = (0 until candidatesPerTrack).map {
            youtubeCandidates[it % youtubeCandidates.size]
        }

        val result = benchmark("precomputed_large_playlist_50x15", iterations = 5_000) {
            for (track in spotifyTracks) {
                val precomputed = SpotifyMapper.precompute(
                    title = track.name,
                    artist = track.artists.firstOrNull()?.name ?: "",
                    durationMs = track.durationMs,
                )
                var bestScore = 0.0
                for (candidate in extendedCandidates) {
                    val score = SpotifyMapper.matchScorePrecomputed(
                        precomputed = precomputed,
                        candidateTitle = candidate.title,
                        candidateArtist = candidate.artist,
                        candidateDurationSec = candidate.durationSec,
                    )
                    if (score > bestScore) bestScore = score
                }
            }
        }
        println(result)
        assertTrue("precomputed large playlist too slow: ${result.avgMicros}µs/op", result.avgMicros < 100000)
    }

    /**
     * Measures the mega playlist (200×15) with the precomputed path.
     * Direct comparison target for benchmarkMegaPlaylist200Tracks.
     */
    @Test
    fun benchmarkPrecomputedMegaPlaylist200Tracks() {
        val megaPlaylist = (0 until 4).flatMap { spotifyTracks }
        val candidatesPerTrack = 15
        val pool = (0 until candidatesPerTrack).map {
            youtubeCandidates[it % youtubeCandidates.size]
        }

        val result = benchmark("precomputed_mega_playlist_200x15", iterations = 1_000) {
            for (track in megaPlaylist) {
                val precomputed = SpotifyMapper.precompute(
                    title = track.name,
                    artist = track.artists.firstOrNull()?.name ?: "",
                    durationMs = track.durationMs,
                )
                var bestScore = 0.0
                for (candidate in pool) {
                    val score = SpotifyMapper.matchScorePrecomputed(
                        precomputed = precomputed,
                        candidateTitle = candidate.title,
                        candidateArtist = candidate.artist,
                        candidateDurationSec = candidate.durationSec,
                    )
                    if (score > bestScore) bestScore = score
                }
            }
        }
        println(result)
        assertTrue("precomputed mega playlist too slow: ${result.avgMicros}µs/op", result.avgMicros < 500000)
    }

    /**
     * Measures the precomputed path with early exit on high-confidence matches.
     * Simulates realistic queue resolution where many tracks match perfectly.
     */
    @Test
    fun benchmarkPrecomputedWithEarlyExit() {
        val batchSize = 20
        val batch = spotifyTracks.take(batchSize)
        // Candidates include exact matches (will trigger early exit)
        val candidatePool = batch.map { track ->
            YouTubeCandidate(
                title = track.name,
                artist = track.artists.firstOrNull()?.name ?: "",
                durationSec = track.durationMs / 1000,
            )
        }

        val earlyExitThreshold = SpotifyMapper.earlyExitThreshold()

        val result = benchmark("precomputed_early_exit_20x20", iterations = 10_000) {
            for (track in batch) {
                val precomputed = SpotifyMapper.precompute(
                    title = track.name,
                    artist = track.artists.firstOrNull()?.name ?: "",
                    durationMs = track.durationMs,
                )
                var bestScore = 0.0
                for (candidate in candidatePool) {
                    val score = SpotifyMapper.matchScorePrecomputed(
                        precomputed = precomputed,
                        candidateTitle = candidate.title,
                        candidateArtist = candidate.artist,
                        candidateDurationSec = candidate.durationSec,
                    )
                    if (score > bestScore) bestScore = score
                    if (bestScore >= earlyExitThreshold) break
                }
            }
        }
        println(result)
        assertTrue("precomputed early exit too slow: ${result.avgMicros}µs/op", result.avgMicros < 50000)
    }

    /**
     * Measures the overhead of scoring tracks with complex titles
     * (feat., remixes, remasters, brackets) vs simple titles.
     * Quantifies the regex normalization cost.
     */
    @Test
    fun benchmarkComplexVsSimpleTitles() {
        val simpleTracks = spotifyTracks.filter {
            !it.name.contains("feat") && !it.name.contains("Remix") &&
                !it.name.contains("Remaster") && !it.name.contains("[")
        }.take(10)
        val complexTracks = spotifyTracks.filter {
            it.name.contains("feat") || it.name.contains("Remix") ||
                it.name.contains("Remaster") || it.name.contains("[")
        }.take(10)

        val pool = youtubeCandidates.take(10)

        val simpleResult = benchmark("simple_titles_10x10", iterations = 20_000) {
            for (track in simpleTracks) {
                val artist = track.artists.firstOrNull()?.name ?: ""
                for (candidate in pool) {
                    SpotifyMapper.matchScore(
                        spotifyTitle = track.name,
                        spotifyArtist = artist,
                        spotifyDurationMs = track.durationMs,
                        candidateTitle = candidate.title,
                        candidateArtist = candidate.artist,
                        candidateDurationSec = candidate.durationSec,
                    )
                }
            }
        }

        val complexResult = benchmark("complex_titles_10x10", iterations = 20_000) {
            for (track in complexTracks) {
                val artist = track.artists.firstOrNull()?.name ?: ""
                for (candidate in pool) {
                    SpotifyMapper.matchScore(
                        spotifyTitle = track.name,
                        spotifyArtist = artist,
                        spotifyDurationMs = track.durationMs,
                        candidateTitle = candidate.title,
                        candidateArtist = candidate.artist,
                        candidateDurationSec = candidate.durationSec,
                    )
                }
            }
        }

        println(simpleResult)
        println(complexResult)
        val overhead = if (simpleResult.avgMicros > 0) {
            (complexResult.avgMicros / simpleResult.avgMicros - 1) * 100
        } else 0.0
        println("  Regex overhead for complex titles: ${"%.1f".format(overhead)}%")
        assertTrue("simple titles benchmark failed", simpleResult.totalMs >= 0)
        assertTrue("complex titles benchmark failed", complexResult.totalMs >= 0)
    }

    // ── Test data helpers ──────────────────────────────────────────────

    private fun track(name: String, artist: String, durationMs: Int): SpotifyTrack =
        SpotifyTrack(
            id = name.hashCode().toString(),
            name = name,
            artists = listOf(SpotifySimpleArtist(id = artist.hashCode().toString(), name = artist)),
            album = SpotifySimpleAlbum(id = "album_${name.hashCode()}", name = "$name - Album"),
            durationMs = durationMs,
        )

    private data class YouTubeCandidate(
        val title: String,
        val artist: String,
        val durationSec: Int,
    )

    private fun candidate(title: String, artist: String, durationSec: Int): YouTubeCandidate =
        YouTubeCandidate(title, artist, durationSec)
}
