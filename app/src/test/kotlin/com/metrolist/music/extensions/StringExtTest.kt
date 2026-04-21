package com.metrolist.music.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StringExtTest {

    @Test
    fun `normalizeForSearch strips diacritics and lowercases`() {
        assertEquals("bjork", "Björk".normalizeForSearch())
        assertEquals("sigur ros", "Sigur Rós".normalizeForSearch())
        assertEquals("beyonce", "Beyoncé".normalizeForSearch())
    }

    @Test
    fun `normalizeForSearch trims surrounding whitespace`() {
        assertEquals("radiohead", "  Radiohead  ".normalizeForSearch())
    }

    @Test
    fun `matchesNormalizedQuery returns true when any field contains the normalized query`() {
        val query = "bjo"
        assertTrue(matchesNormalizedQuery(query, "Björk", "Debut"))
        assertTrue(matchesNormalizedQuery(query, null, "BJORK"))
    }

    @Test
    fun `matchesNormalizedQuery returns false when no field contains the query`() {
        assertFalse(matchesNormalizedQuery("radiohead", "Björk", "Debut", null))
    }

    @Test
    fun `matchesNormalizedQuery blank query matches everything`() {
        assertTrue(matchesNormalizedQuery("", "anything"))
        assertTrue(matchesNormalizedQuery("   ", "anything"))
    }

    @Test
    fun `matchesNormalizedQuery null values are ignored not treated as match`() {
        // Only nulls — no match
        assertFalse(matchesNormalizedQuery("x", null, null))
    }
}
