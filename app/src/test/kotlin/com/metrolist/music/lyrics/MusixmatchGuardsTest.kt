/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Musixmatch answers anonymous desktop-API callers with a placeholder token instead of an error,
 * and queries made with it return the same unrelated track and nonsense lyrics for every song.
 * Testers saw those lyrics over every track, so both guards below are load-bearing.
 */
class MusixmatchGuardsTest {

    @Test
    fun `rejects the all-zero placeholder token`() {
        assertFalse(MusixmatchLyricsProvider.isUsableToken("0".repeat(56)))
    }

    @Test
    fun `rejects the upgrade sentinel and blanks`() {
        assertFalse(MusixmatchLyricsProvider.isUsableToken("UpgradeOnlyUpgradeOnlyUpgradeOnlyUpgradeOnly"))
        assertFalse(MusixmatchLyricsProvider.isUsableToken(""))
        assertFalse(MusixmatchLyricsProvider.isUsableToken("   "))
    }

    @Test
    fun `accepts a realistic token`() {
        assertTrue(MusixmatchLyricsProvider.isUsableToken("2005218b74d9b4a1b4b1d4a0f0e9c3c2b8a7d6e5f4c3b2a1908172"))
    }

    @Test
    fun `match tolerates punctuation case and bracketed suffixes`() {
        assertTrue(MusixmatchLyricsProvider.looselyMatches("Bohemian Rhapsody", "bohemian rhapsody"))
        assertTrue(MusixmatchLyricsProvider.looselyMatches("Grenade (Remastered)", "Grenade"))
        assertTrue(MusixmatchLyricsProvider.looselyMatches("Don't Stop Me Now", "Dont Stop Me Now"))
    }

    @Test
    fun `match rejects the canned fallback track`() {
        // What the placeholder token actually returned for every query.
        assertFalse(MusixmatchLyricsProvider.looselyMatches("NOKIA", "Bohemian Rhapsody"))
        assertFalse(MusixmatchLyricsProvider.looselyMatches("Drake", "Queen"))
    }

    @Test
    fun `match rejects empty input rather than matching everything`() {
        assertFalse(MusixmatchLyricsProvider.looselyMatches("", "Grenade"))
        assertFalse(MusixmatchLyricsProvider.looselyMatches("Grenade", "!!!"))
    }
}
