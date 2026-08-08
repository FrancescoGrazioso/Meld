package com.metrolist.music.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetEaseLyricsProviderTest {

    @Test
    fun `sanitizeLrc removes pre-roll intro headers and duplicate lines`() {
        val rawLrc = """
            [00:00.00] 作曲 : Jason Mraz/Michael Natter
            [00:00.00] 作词 : Jason Mraz/Michael Natter
            [00:00.00]When I look into your eyes
            [00:22.00]When I look into your eyes
            [00:27.00]It's like watching the night sky
            [00:33.00]Or a beautiful sunrise
        """.trimIndent()

        // Call private sanitizeLrc method using reflection for thorough unit testing
        val method = NetEaseLyricsProvider::class.java.getDeclaredMethod(
            "sanitizeLrc",
            String::class.java,
            String::class.java,
            String::class.java
        )
        method.isAccessible = true
        val result = method.invoke(NetEaseLyricsProvider, rawLrc, "I Won't Give Up", "Jason Mraz") as String

        val lines = result.lines().map { it.trim() }

        // Must not contain pre-roll credit headers or 00:00 duplicate lines
        assertFalse(result.contains("作曲"))
        assertFalse(result.contains("作词"))
        assertFalse(result.contains("[00:00.00]When I look into your eyes"))

        // Must start directly at 00:22.00 vocal entry and contain unique lines
        assertEquals(3, lines.size)
        assertEquals("[00:22.00]When I look into your eyes", lines[0])
        assertEquals("[00:27.00]It's like watching the night sky", lines[1])
        assertEquals("[00:33.00]Or a beautiful sunrise", lines[2])
    }
}
