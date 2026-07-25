package com.metrolist.music.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsProviderRegistryTest {

    @Test
    fun `getDefaultProviderOrder returns LRCLIB first and NetEase second`() {
        val defaultOrder = LyricsProviderRegistry.getDefaultProviderOrder()
        assertEquals("LrcLib", defaultOrder[0])
        assertEquals("NetEase", defaultOrder[1])
        assertEquals("KuGou", defaultOrder[2])
    }

    @Test
    fun `deserializeProviderOrder with empty string returns default provider order`() {
        val result = LyricsProviderRegistry.deserializeProviderOrder("")
        assertEquals(LyricsProviderRegistry.getDefaultProviderOrder(), result)
    }

    @Test
    fun `deserializeProviderOrder appends missing default providers for existing custom user lists`() {
        val customUserOrder = "KuGou,BetterLyrics"
        val result = LyricsProviderRegistry.deserializeProviderOrder(customUserOrder)

        // Custom order preserved at top
        assertEquals("KuGou", result[0])
        assertEquals("BetterLyrics", result[1])

        // Missing providers appended
        assertTrue(result.contains("LrcLib"))
        assertTrue(result.contains("NetEase"))
        assertEquals(LyricsProviderRegistry.providerNames.size, result.size)
    }

    @Test
    fun `serializeProviderOrder converts list into comma-separated string`() {
        val providers = listOf("LrcLib", "NetEase", "KuGou")
        val serialized = LyricsProviderRegistry.serializeProviderOrder(providers)
        assertEquals("LrcLib,NetEase,KuGou", serialized)
    }
}
