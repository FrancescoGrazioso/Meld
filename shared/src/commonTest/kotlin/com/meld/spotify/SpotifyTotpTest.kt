package com.meld.spotify

import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalStdlibApi::class)
class SpotifyTotpTest {
    private val rfcKey = "12345678901234567890".encodeToByteArray()

    @Test
    fun sha1MatchesTheStandardVectors() {
        fun digest(text: String) = sha1(text.encodeToByteArray()).toHexString()

        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", digest(""))
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", digest("abc"))
        // 56 bytes: the length no longer fits the first block.
        assertEquals(
            "84983e441c3bd26ebaae4aa1f95129e5e54670f1",
            digest("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
        )
        assertEquals("34aa973cd4c4daa4f61eeb2bdbad27316534016f", digest("a".repeat(1_000_000)))
    }

    @Test
    fun hmacSha1MatchesRfc2202() {
        fun mac(
            key: ByteArray,
            text: String,
        ) = hmacSha1(key, text.encodeToByteArray()).toHexString()

        assertEquals("b617318655057264e28bc0b6fb378c8ef146be00", mac(ByteArray(20) { 0x0b }, "Hi There"))
        assertEquals("effcdf6ae5eb2fa2d27416d5f184df9c259a7c79", mac("Jefe".encodeToByteArray(), "what do ya want for nothing?"))
        // A key longer than one block is hashed first.
        assertEquals(
            "aa4ae5e15272d00e95705637ce8a3b55ed402112",
            mac(ByteArray(80) { 0xaa.toByte() }, "Test Using Larger Than Block-Size Key - Hash Key First"),
        )
    }

    @Test
    fun hotpMatchesRfc4226() {
        val expected = listOf("755224", "287082", "359152", "969429", "338314", "254676", "287922", "162583", "399871", "520489")
        assertEquals(expected, expected.indices.map { hotp(rfcKey, it.toLong()) })
    }

    @Test
    fun totpMatchesRfc6238Sha1() {
        val expected =
            mapOf(
                59L to "94287082",
                1_111_111_109L to "07081804",
                1_111_111_111L to "14050471",
                1_234_567_890L to "89005924",
                2_000_000_000L to "69279037",
                20_000_000_000L to "65353130",
            )
        assertEquals(expected, expected.mapValues { (seconds, _) -> hotp(rfcKey, seconds / 30, digits = 8) })
    }

    @Test
    fun aCodeIsSixDigitsAndHoldsForItsWholePeriod() {
        val start = 1_800_000_000_000L
        val code = SpotifyTotp.code(start)
        assertEquals(6, code.length)
        assertEquals(code, SpotifyTotp.code(start + 29_999))
    }
}
