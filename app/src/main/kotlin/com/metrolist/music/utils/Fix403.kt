/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import com.metrolist.music.BuildConfig
import timber.log.Timber
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/**
 * Deep diagnostic instrumentation for the "HTTP 403 while playing" / "IO_UNSPECIFIED (2000)"
 * reports. **Every line it emits carries the logcat tag [TAG] (`fix403`)**, so a full capture can
 * be reduced to just this instrumentation with:
 *
 * ```
 * adb logcat -s fix403
 * ```
 *
 * ### Why this exists
 * The user logcats collected for these reports were undiagnosable: no stream URL was ever logged
 * (zero occurrences of `googlevideo`, `expire=`, `itag=`, `pot=` in 32k lines), the fallback
 * cascade had to be reconstructed from a dozen scattered debug lines, and several `catch` blocks
 * swallowed the exception that actually explained the failure. This object exists so a single
 * capture answers the question on its own.
 *
 * ### Correlation
 * Every resolution attempt gets a short id from [nextId] (e.g. `res-17`) that prefixes all of its
 * lines, so interleaved playback and download resolutions stay separable. Lines are shaped as
 *
 * ```
 * <id> <op> k=v k=v ...
 * ```
 *
 * which greps and splits cleanly.
 *
 * ### Redaction
 * The logs are verbose but must remain safe to paste into a public issue. Anything that
 * authenticates the user or signs a URL — cookies, `SAPISIDHASH`, PoTokens, `sig`/`signature`/
 * `sparams` values, `dataSyncId` — is passed through [redact], which prints only a length and a
 * short SHA-1 prefix. That is enough to tell "present / absent / changed between attempts" apart,
 * which is all the diagnosis needs, without disclosing the secret itself.
 */
object Fix403 {
    const val TAG = "fix403"

    /**
     * Debug builds only. This instrumentation is deliberately verbose — it exists to make a single
     * captured logcat answer "which client served this stream and why did the CDN refuse it", and
     * that is worth a lot of lines. Beta testers run the debug build, so they keep producing usable
     * traces; release users get nothing.
     *
     * Everything below short-circuits on this flag rather than being stripped at the call sites, so
     * the instrumentation stays readable in the code and can be re-enabled by flipping one value.
     */
    @JvmField
    val ENABLED: Boolean = BuildConfig.DEBUG

    private val seq = AtomicLong(0)

    /** Short correlation id, e.g. `res-42`. Prefix every line of one logical operation with it. */
    fun nextId(prefix: String): String = "$prefix-${seq.incrementAndGet()}"

    // ── Emit ─────────────────────────────────────────────────────────────────────────────

    fun d(id: String, op: String, details: String = "") {
        if (ENABLED) Timber.tag(TAG).d(line(id, op, details))
    }

    fun i(id: String, op: String, details: String = "") {
        if (ENABLED) Timber.tag(TAG).i(line(id, op, details))
    }

    fun w(id: String, op: String, details: String = "") {
        if (ENABLED) Timber.tag(TAG).w(line(id, op, details))
    }

    fun e(id: String, op: String, details: String = "") {
        if (ENABLED) Timber.tag(TAG).e(line(id, op, details))
    }

    /**
     * Log a throwable with its full cause chain **and** stack trace. Use this everywhere an
     * exception is caught — including places that intentionally continue, because a swallowed
     * exception is exactly what made the earlier reports impossible to diagnose.
     */
    fun fail(id: String, op: String, t: Throwable, details: String = "") {
        if (!ENABLED) return
        Timber.tag(TAG).e(line(id, op, "$details chain=${chain(t)}"))
        Timber.tag(TAG).e(t, line(id, "$op.stack", ""))
    }

    private fun line(id: String, op: String, details: String): String =
        if (details.isEmpty()) "$id $op" else "$id $op $details"

    // ── Formatting helpers ───────────────────────────────────────────────────────────────

    /** `k=v` pairs joined by spaces; nulls render as `-` so columns stay aligned when grepping. */
    fun kv(vararg pairs: Pair<String, Any?>): String =
        pairs.joinToString(" ") { (k, v) -> "$k=${render(v)}" }

    private fun render(v: Any?): String = when (v) {
        null -> "-"
        is String -> if (v.isEmpty()) "\"\"" else if (' ' in v) "\"$v\"" else v
        else -> v.toString()
    }

    /** `Outer:msg <- Middle:msg <- Root:msg` — the whole cause chain on one line. */
    fun chain(t: Throwable?): String =
        generateSequence(t) { it.cause.takeIf { c -> c !== it } }
            .take(12)
            .joinToString(" <- ") { "${it.javaClass.simpleName}:${it.message ?: "-"}" }

    /**
     * Length + short SHA-1 prefix of a secret. Enough to distinguish present/absent and to tell
     * whether the value changed between two attempts, without disclosing it.
     */
    fun redact(secret: String?): String {
        if (secret == null) return "absent"
        if (secret.isEmpty()) return "EMPTY(len=0)"
        return try {
            val digest = MessageDigest.getInstance("SHA-1").digest(secret.toByteArray())
            val hex = digest.take(4).joinToString("") { "%02x".format(it) }
            "len=${secret.length},sha1=$hex"
        } catch (t: Throwable) {
            "len=${secret.length},sha1=unavailable"
        }
    }

    // ── Trapping ─────────────────────────────────────────────────────────────────────────

    /**
     * Run [block], logging any throwable in full, and return `null` on failure.
     * For call sites that legitimately continue after an error but must not hide it.
     */
    inline fun <T> trap(id: String, op: String, block: () -> T): T? =
        try {
            block()
        } catch (t: Throwable) {
            fail(id, op, t)
            null
        }

    /** Run [block], logging any throwable in full, then rethrow it unchanged. */
    inline fun <T> trapRethrow(id: String, op: String, block: () -> T): T =
        try {
            block()
        } catch (t: Throwable) {
            fail(id, op, t)
            throw t
        }

    /** Run [block], logging how long it took and any throwable it raised. */
    inline fun <T> timed(id: String, op: String, block: () -> T): T {
        val startedAt = System.nanoTime()
        try {
            val result = block()
            d(id, "$op.done", kv("ms" to (System.nanoTime() - startedAt) / 1_000_000))
            return result
        } catch (t: Throwable) {
            fail(id, "$op.threw", t, kv("ms" to (System.nanoTime() - startedAt) / 1_000_000))
            throw t
        }
    }
}
