package com.metrolist.music.extensions

import androidx.media3.common.Player
import androidx.media3.common.Player.REPEAT_MODE_ALL
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Player.REPEAT_MODE_ONE
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class PlayerRepeatModeTest {

    @Test
    fun `toggleRepeatMode cycles OFF to ALL to ONE to OFF`() {
        val player = fakePlayer(initialMode = REPEAT_MODE_OFF)
        player.toggleRepeatMode()
        assertEquals(REPEAT_MODE_ALL, player.repeatMode)
        player.toggleRepeatMode()
        assertEquals(REPEAT_MODE_ONE, player.repeatMode)
        player.toggleRepeatMode()
        assertEquals(REPEAT_MODE_OFF, player.repeatMode)
    }

    @Test
    fun `toggleRepeatMode is idempotent after three calls from any start`() {
        for (start in intArrayOf(REPEAT_MODE_OFF, REPEAT_MODE_ALL, REPEAT_MODE_ONE)) {
            val player = fakePlayer(initialMode = start)
            repeat(3) { player.toggleRepeatMode() }
            assertEquals(
                "starting from $start, three toggles should return to start",
                start,
                player.repeatMode,
            )
        }
    }

    /**
     * Builds a minimal [Player] proxy that only answers `getRepeatMode` / `setRepeatMode`.
     * Every other call throws — which is the signal we want: the extension under test must
     * not depend on anything else to perform its cycle.
     */
    private fun fakePlayer(initialMode: Int): Player {
        var mode = initialMode
        val handler = InvocationHandler { _: Any, method: Method, args: Array<Any?>? ->
            when (method.name) {
                "getRepeatMode" -> mode
                "setRepeatMode" -> {
                    mode = args!![0] as Int
                    null
                }
                // Object.hashCode / equals / toString are invoked by Proxy internals;
                // provide sane responses so the test frame can print failures.
                "hashCode" -> System.identityHashCode(this)
                "equals" -> args!![0] === this
                "toString" -> "FakePlayer(mode=$mode)"
                else -> throw UnsupportedOperationException(
                    "FakePlayer does not support ${method.name}; toggleRepeatMode should not reach it"
                )
            }
        }
        return Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java),
            handler,
        ) as Player
    }
}
