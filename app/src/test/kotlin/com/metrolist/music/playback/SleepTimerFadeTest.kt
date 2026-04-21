package com.metrolist.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerFadeTest {

    @Test
    fun `full volume while remainingMs is at or beyond the fade window`() {
        assertEquals(1f, computeSleepTimerVolumeMultiplier(60_000L), 0f)
        assertEquals(1f, computeSleepTimerVolumeMultiplier(120_000L), 0f)
    }

    @Test
    fun `linear ramp inside the fade window`() {
        // Halfway through the 60s window → half volume.
        assertEquals(0.5f, computeSleepTimerVolumeMultiplier(30_000L), 0.0001f)
        // Quarter of the way → 25% volume.
        assertEquals(0.25f, computeSleepTimerVolumeMultiplier(15_000L), 0.0001f)
    }

    @Test
    fun `zero volume at timer expiry`() {
        assertEquals(0f, computeSleepTimerVolumeMultiplier(0L), 0f)
    }

    @Test
    fun `negative remaining time clamps to zero not to negative volume`() {
        // coerceIn(0,1) is the safety net: without it the audio pipeline could see
        // negative floats which ExoPlayer rejects or turns into clicks.
        assertEquals(0f, computeSleepTimerVolumeMultiplier(-1L), 0f)
        assertEquals(0f, computeSleepTimerVolumeMultiplier(-60_000L), 0f)
    }

    @Test
    fun `custom fade window scales output correctly`() {
        // With a 30s window, being 15s from expiry is halfway.
        assertEquals(0.5f, computeSleepTimerVolumeMultiplier(15_000L, fadeOutWindowMs = 30_000L), 0.0001f)
        // 30s from expiry with a 30s window → full volume (boundary).
        assertEquals(1f, computeSleepTimerVolumeMultiplier(30_000L, fadeOutWindowMs = 30_000L), 0f)
    }

    @Test
    fun `output is always within 0 to 1 across a broad sweep`() {
        val window = 60_000L
        for (remaining in -10_000L..70_000L step 500L) {
            val v = computeSleepTimerVolumeMultiplier(remaining, window)
            assertTrue("volume $v out of bounds at remaining=$remaining", v in 0f..1f)
        }
    }
}
