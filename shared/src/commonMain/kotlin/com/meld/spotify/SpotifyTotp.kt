package com.meld.spotify

/**
 * The one-time code Spotify's web player sends with a token request. Ported from upstream's JVM-only
 * `meow.nyx.spotlib.Totp`, with which it must agree: a test compares the two.
 */
internal object SpotifyTotp {
    // shortcut: the secret and its version are bundled, feed them from the hash registry JSON when Spotify rotates them.
    private const val SECRET = ",7/*F(\"rLJ2oxaKL^f+E1xvP@N"
    const val VERSION = 61
    private const val PERIOD_SECONDS = 30L

    // spotlib also passes the key through a base32 encode and decode, which gives back the same bytes.
    private val key =
        SECRET
            .mapIndexed { index, character -> character.code xor (index % 33 + 9) }
            .joinToString("")
            .encodeToByteArray()

    fun code(nowEpochMillis: Long): String = hotp(key, nowEpochMillis / 1000 / PERIOD_SECONDS)
}

/** RFC 4226 over HMAC-SHA1. */
internal fun hotp(
    key: ByteArray,
    counter: Long,
    digits: Int = 6,
): String {
    val digest = hmacSha1(key, ByteArray(8) { (counter shr (8 * (7 - it))).toByte() })
    val offset = digest.last().toInt() and 0x0F
    val binary =
        ((digest[offset].toInt() and 0x7F) shl 24) or
            ((digest[offset + 1].toInt() and 0xFF) shl 16) or
            ((digest[offset + 2].toInt() and 0xFF) shl 8) or
            (digest[offset + 3].toInt() and 0xFF)
    var modulus = 1
    repeat(digits) { modulus *= 10 }
    return (binary % modulus).toString().padStart(digits, '0')
}

private const val SHA1_BLOCK = 64

internal fun hmacSha1(
    key: ByteArray,
    message: ByteArray,
): ByteArray {
    val block = (if (key.size > SHA1_BLOCK) sha1(key) else key).copyOf(SHA1_BLOCK)
    val inner = ByteArray(SHA1_BLOCK) { (block[it].toInt() xor 0x36).toByte() }
    val outer = ByteArray(SHA1_BLOCK) { (block[it].toInt() xor 0x5C).toByte() }
    return sha1(outer + sha1(inner + message))
}

// Hand-written because common code has no SHA-1; it only has to match Spotify's protocol, it protects nothing of ours.
internal fun sha1(message: ByteArray): ByteArray {
    val padded = message.copyOf(((message.size + 8) / SHA1_BLOCK + 1) * SHA1_BLOCK)
    padded[message.size] = 0x80.toByte()
    val bitLength = message.size * 8L
    for (index in 0 until 8) padded[padded.size - 1 - index] = (bitLength ushr (8 * index)).toByte()

    val state = intArrayOf(0x67452301, 0xEFCDAB89.toInt(), 0x98BADCFE.toInt(), 0x10325476, 0xC3D2E1F0.toInt())
    val words = IntArray(80)
    for (start in padded.indices step SHA1_BLOCK) {
        for (index in 0 until 16) {
            val at = start + index * 4
            words[index] =
                ((padded[at].toInt() and 0xFF) shl 24) or
                ((padded[at + 1].toInt() and 0xFF) shl 16) or
                ((padded[at + 2].toInt() and 0xFF) shl 8) or
                (padded[at + 3].toInt() and 0xFF)
        }
        for (index in 16 until 80) {
            words[index] = (words[index - 3] xor words[index - 8] xor words[index - 14] xor words[index - 16]).rotateLeft(1)
        }
        var a = state[0]
        var b = state[1]
        var c = state[2]
        var d = state[3]
        var e = state[4]
        for (index in 0 until 80) {
            val mixed =
                when (index / 20) {
                    0 -> ((b and c) or (b.inv() and d)) + 0x5A827999
                    1 -> (b xor c xor d) + 0x6ED9EBA1
                    2 -> ((b and c) or (b and d) or (c and d)) + 0x8F1BBCDC.toInt()
                    else -> (b xor c xor d) + 0xCA62C1D6.toInt()
                }
            val next = a.rotateLeft(5) + mixed + e + words[index]
            e = d
            d = c
            c = b.rotateLeft(30)
            b = a
            a = next
        }
        state[0] += a
        state[1] += b
        state[2] += c
        state[3] += d
        state[4] += e
    }
    return ByteArray(20) { (state[it / 4] ushr (24 - 8 * (it % 4))).toByte() }
}
