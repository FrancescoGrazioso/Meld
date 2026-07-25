package com.metrolist.music.utils

import com.metrolist.innertube.models.YouTubeClient.Companion.TVHTML5
import com.metrolist.innertube.models.YouTubeClient.Companion.WEB
import com.metrolist.innertube.models.YouTubeClient.Companion.WEB_CREATOR
import com.metrolist.music.utils.YTPlayerUtils.NORMAL_CONTENT_STREAM_START_INDEX
import com.metrolist.music.utils.YTPlayerUtils.STREAM_FALLBACK_CLIENTS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YTPlayerUtilsTest {

    @Test
    fun testNormalContentStreamStartIndexIsValid() {
        assertTrue(
            "NORMAL_CONTENT_STREAM_START_INDEX must be >= 0 to prevent silent fallback routing regressions",
            NORMAL_CONTENT_STREAM_START_INDEX >= 0
        )
    }

    @Test
    fun testTvHtml5IndexIsValid() {
        val tvHtml5Index = STREAM_FALLBACK_CLIENTS.indexOf(TVHTML5)
        assertTrue(
            "TVHTML5 must exist in STREAM_FALLBACK_CLIENTS for private track compatibility",
            tvHtml5Index >= 0
        )
    }

    @Test
    fun testWebIndexIsValid() {
        val webIndex = STREAM_FALLBACK_CLIENTS.indexOf(WEB)
        assertTrue(
            "WEB must exist in STREAM_FALLBACK_CLIENTS as non-login fallback client",
            webIndex >= 0
        )
    }

    @Test
    fun testLastAttemptableClientIndexWhenLoggedOut() {
        val isLoggedIn = false
        val hasCookie = false

        val lastAttemptableClientIndex = STREAM_FALLBACK_CLIENTS.indexOfLast { client ->
            !client.loginRequired || isLoggedIn || hasCookie
        }

        val expectedWebIndex = STREAM_FALLBACK_CLIENTS.indexOf(WEB)
        assertEquals(
            "When logged out, lastAttemptableClientIndex must point to WEB (non-login client) so NewPipe scrape safety net runs",
            expectedWebIndex,
            lastAttemptableClientIndex
        )
    }

    @Test
    fun testLastAttemptableClientIndexWhenLoggedIn() {
        val isLoggedIn = true
        val hasCookie = false

        val lastAttemptableClientIndex = STREAM_FALLBACK_CLIENTS.indexOfLast { client ->
            !client.loginRequired || isLoggedIn || hasCookie
        }

        val expectedWebCreatorIndex = STREAM_FALLBACK_CLIENTS.indexOf(WEB_CREATOR)
        assertEquals(
            "When logged in, lastAttemptableClientIndex must point to WEB_CREATOR",
            expectedWebCreatorIndex,
            lastAttemptableClientIndex
        )
    }
}
