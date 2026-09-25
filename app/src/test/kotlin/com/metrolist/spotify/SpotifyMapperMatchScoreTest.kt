package com.metrolist.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Correctness tests for [SpotifyMapper.matchScore]. The matching logic picks the YouTube
 * candidate that should play when a user starts a Spotify track — wrong scoring means the
 * user hears the wrong song, so the ordering properties tested here are load-bearing.
 */
class SpotifyMapperMatchScoreTest {

    @Test
    fun `exact title artist and duration produce near-perfect score`() {
        val score = SpotifyMapper.matchScore(
            spotifyTitle = "Bohemian Rhapsody",
            spotifyArtist = "Queen",
            spotifyDurationMs = 354_000,
            candidateTitle = "Bohemian Rhapsody",
            candidateArtist = "Queen",
            candidateDurationSec = 354,
        )
        assertTrue("expected near-1.0 for identical track, got $score", score >= 0.95)
    }

    @Test
    fun `feat and remaster tags in title do not tank the score`() {
        // Spotify titles often carry "(feat. X)" / "(Remastered)" clutter that the
        // candidate on YouTube does not — normalizeTitle must strip these so the two
        // sides score as equivalent.
        val score = SpotifyMapper.matchScore(
            spotifyTitle = "Lose Yourself (feat. Eminem) [Remastered 2023]",
            spotifyArtist = "Eminem",
            spotifyDurationMs = 326_000,
            candidateTitle = "Lose Yourself",
            candidateArtist = "Eminem",
            candidateDurationSec = 326,
        )
        assertTrue("tag-stripped titles should still match strongly, got $score", score >= 0.90)
    }

    @Test
    fun `wrong artist drops the score substantially`() {
        val score = SpotifyMapper.matchScore(
            spotifyTitle = "Imagine",
            spotifyArtist = "John Lennon",
            spotifyDurationMs = 183_000,
            candidateTitle = "Imagine",
            candidateArtist = "Ariana Grande",
            candidateDurationSec = 183,
        )
        // Title and duration match perfectly but artist is off — score must reflect it.
        assertTrue("wrong artist should pull score below 0.8, got $score", score < 0.8)
    }

    @Test
    fun `null candidate duration gives neutral duration score, not zero`() {
        val score = SpotifyMapper.matchScore(
            spotifyTitle = "Flowers",
            spotifyArtist = "Miley Cyrus",
            spotifyDurationMs = 200_000,
            candidateTitle = "Flowers",
            candidateArtist = "Miley Cyrus",
            candidateDurationSec = null,
        )
        // 0.5 duration * 0.20 = 0.10 from duration; title+artist should still push high.
        assertTrue("null duration should not collapse the score, got $score", score >= 0.80)
    }

    @Test
    fun `duration mismatch thresholds behave monotonically`() {
        // For identical title/artist, the total score should monotonically decrease as
        // the candidate duration drifts further from the spotify duration. Proves the
        // threshold buckets in durationScore() are ordered correctly.
        fun scoreAt(diffSec: Int): Double = SpotifyMapper.matchScore(
            spotifyTitle = "Song",
            spotifyArtist = "Artist",
            spotifyDurationMs = 200_000,
            candidateTitle = "Song",
            candidateArtist = "Artist",
            candidateDurationSec = 200 + diffSec,
        )
        val s0 = scoreAt(0)
        val s3 = scoreAt(3)
        val s7 = scoreAt(7)
        val s20 = scoreAt(20)
        val s60 = scoreAt(60)
        assertTrue("diff=0 ($s0) should be >= diff=3 ($s3)", s0 >= s3)
        assertTrue("diff=3 ($s3) should be >= diff=7 ($s7)", s3 >= s7)
        assertTrue("diff=7 ($s7) should be >= diff=20 ($s20)", s7 >= s20)
        assertTrue("diff=20 ($s20) should be >= diff=60 ($s60)", s20 >= s60)
    }

    @Test
    fun `early exit threshold is a stable published constant`() {
        // If this value is lowered carelessly the matcher will start picking weak
        // candidates and stop scanning; if raised too high the matcher becomes slow.
        assertEquals(0.95, SpotifyMapper.earlyExitThreshold(), 0.0001)
    }

    @Test
    fun `precompute path returns the same score as the non-precomputed path`() {
        val title = "Blinding Lights"
        val artist = "The Weeknd"
        val durationMs = 200_000
        val direct = SpotifyMapper.matchScore(
            spotifyTitle = title,
            spotifyArtist = artist,
            spotifyDurationMs = durationMs,
            candidateTitle = "Blinding Lights (Official Audio)",
            candidateArtist = "The Weeknd",
            candidateDurationSec = 200,
        )
        val pre = SpotifyMapper.precompute(title, artist, durationMs)
        val viaPrecomputed = SpotifyMapper.matchScorePrecomputed(
            precomputed = pre,
            candidateTitle = "Blinding Lights (Official Audio)",
            candidateArtist = "The Weeknd",
            candidateDurationSec = 200,
        )
        // Both paths must produce the same score — the precomputed version exists for
        // performance only, never to diverge in semantics.
        assertEquals(direct, viaPrecomputed, 0.0001)
    }
}
