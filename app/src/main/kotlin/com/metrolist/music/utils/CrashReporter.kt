/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.metrolist.music.BuildConfig
import com.metrolist.music.constants.CrashReportingEnabledKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.PrintWriter
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/**
 * Posts crash data to the project's GitHub Issues for remote debugging.
 *
 * Privacy contract: we send what is needed to diagnose a crash and nothing else.
 * The payload is deliberately limited to (a) the throwable's class, message, and stack
 * trace, sanitized to strip filesystem paths and high-entropy tokens, and (b) device /
 * app metadata that does not identify the user (manufacturer, model, Android version,
 * app version, ABI, locale). No account info, cookies, playlist names, song IDs,
 * Spotify/YouTube tokens, file URIs, or content from settings is included.
 *
 * Reports are throttled per-fingerprint (class + top stack frames) so a recurring
 * exception does not spam the Issues tracker.
 */
object CrashReporter {

    private const val DEDUPE_PREFS = "crash_reporter_dedupe"
    private const val DEDUPE_COOLDOWN_MS = 60 * 60 * 1000L // 1 hour
    private const val MAX_BODY_CHARS = 60_000 // GitHub Issues body limit is 65536
    private const val FATAL_TIMEOUT_MS = 4_000
    private const val NONFATAL_TIMEOUT_MS = 8_000

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Synchronously posts a fatal crash report. Called from the uncaught exception
     * handler before the process is killed; capped at a few seconds so a slow network
     * never delays the crash dialog. Failures (network, misconfigured token, DataStore
     * unavailable, etc.) are swallowed — the original throwable always wins.
     */
    fun reportFatal(throwable: Throwable) {
        try {
            val ctx = appContext ?: return
            if (!isEnabledSafe(ctx)) return
            val fingerprint = fingerprint(throwable)
            if (isThrottledSafe(ctx, fingerprint)) return
            val (title, body) = buildIssue(throwable, kind = Kind.FATAL)
            val ok = postIssue(title, body, listOf("crash-report", "fatal"), FATAL_TIMEOUT_MS)
            if (ok) markReportedSafe(ctx, fingerprint)
        } catch (t: Throwable) {
            // Never let the reporter itself derail the crash flow.
            runCatching { Timber.tag("CrashReporter").w(t, "reportFatal failed") }
        }
    }

    /**
     * Asynchronously posts a non-fatal exception report. Safe to call from any thread.
     */
    fun reportNonFatal(throwable: Throwable) {
        val ctx = appContext ?: return
        if (!isEnabledSafe(ctx)) return
        ioScope.launch {
            try {
                val fingerprint = fingerprint(throwable)
                if (isThrottledSafe(ctx, fingerprint)) return@launch
                val (title, body) = buildIssue(throwable, kind = Kind.NON_FATAL)
                val ok = postIssue(title, body, listOf("crash-report", "non-fatal"), NONFATAL_TIMEOUT_MS)
                if (ok) markReportedSafe(ctx, fingerprint)
            } catch (t: Throwable) {
                runCatching { Timber.tag("CrashReporter").w(t, "reportNonFatal failed") }
            }
        }
    }

    /**
     * Reports a suspected ANR with the captured main-thread stack. Non-fatal; the app
     * may recover. The stack is provided by [AnrWatchdog] which captured it at the
     * moment the watchdog detected the block.
     */
    fun reportAnr(mainThreadStack: Array<StackTraceElement>, blockedMs: Long) {
        val ctx = appContext ?: return
        if (!isEnabledSafe(ctx)) return
        ioScope.launch {
            try {
                val synthetic = AnrException(blockedMs).apply { stackTrace = mainThreadStack }
                val fingerprint = fingerprint(synthetic)
                if (isThrottledSafe(ctx, fingerprint)) return@launch
                val (title, body) = buildIssue(synthetic, kind = Kind.ANR)
                val ok = postIssue(title, body, listOf("crash-report", "anr"), NONFATAL_TIMEOUT_MS)
                if (ok) markReportedSafe(ctx, fingerprint)
            } catch (t: Throwable) {
                runCatching { Timber.tag("CrashReporter").w(t, "reportAnr failed") }
            }
        }
    }

    private class AnrException(blockedMs: Long) :
        RuntimeException("Application Not Responding: main thread blocked for ${blockedMs}ms")

    private enum class Kind(val label: String, val severity: String) {
        FATAL("Crash", "fatal"),
        NON_FATAL("Non-fatal", "non-fatal"),
        ANR("ANR", "anr"),
    }

    private fun isEnabledSafe(ctx: Context): Boolean = try {
        if (BuildConfig.CRASH_REPORT_TOKEN.isEmpty() || BuildConfig.CRASH_REPORT_REPO.isEmpty()) {
            false
        } else {
            ctx.dataStore.get(CrashReportingEnabledKey, defaultValue = true)
        }
    } catch (_: Throwable) {
        // If reading prefs blows up (e.g. DataStore not yet ready during early crash),
        // err on the side of NOT reporting so we never hide the original exception
        // behind a reporter failure.
        false
    }

    private fun isThrottledSafe(ctx: Context, fingerprint: String): Boolean = try {
        val prefs = ctx.getSharedPreferences(DEDUPE_PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(fingerprint, 0L)
        System.currentTimeMillis() - last < DEDUPE_COOLDOWN_MS
    } catch (_: Throwable) {
        false
    }

    private fun markReportedSafe(ctx: Context, fingerprint: String) {
        try {
            ctx.getSharedPreferences(DEDUPE_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(fingerprint, System.currentTimeMillis())
                .apply()
        } catch (_: Throwable) {
            // Best-effort; if dedupe cache write fails the worst case is a duplicate
            // report later.
        }
    }

    private fun fingerprint(throwable: Throwable): String {
        val root = throwable.deepestCause()
        val key = buildString {
            append(root.javaClass.name)
            root.stackTrace.take(5).forEach { f ->
                append('|').append(f.className).append('#').append(f.methodName).append(':').append(f.lineNumber)
            }
        }
        val md = MessageDigest.getInstance("SHA-1")
        return md.digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun buildIssue(throwable: Throwable, kind: Kind): Pair<String, String> {
        val root = throwable.deepestCause()
        val rawMessage = root.message?.takeIf { it.isNotBlank() } ?: ""
        val sanitizedMessage = sanitize(rawMessage).take(160)
        val topFrame = root.stackTrace.firstOrNull()?.let {
            "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
        } ?: "?"
        val title = buildString {
            append("[${kind.label}] ")
            append(root.javaClass.simpleName)
            if (sanitizedMessage.isNotEmpty()) append(": ").append(sanitizedMessage)
            append(" @ ").append(topFrame)
        }.take(200)

        val stackTrace = StringWriter().apply {
            throwable.printStackTrace(PrintWriter(this))
        }.toString()
        val sanitizedStack = sanitize(stackTrace)

        val ctx = appContext
        val mem = ctx?.let(::memoryInfo).orEmpty()

        val body = buildString {
            appendLine("**Severity:** ${kind.severity}")
            appendLine("**App:** ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.FLAVOR}/${BuildConfig.BUILD_TYPE}")
            appendLine("**Device:** ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("**Android:** ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · ABI ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}")
            appendLine("**Locale:** ${Locale.getDefault().toLanguageTag()}")
            if (mem.isNotEmpty()) appendLine("**Memory:** $mem")
            appendLine()
            appendLine("```")
            append(sanitizedStack)
            appendLine()
            appendLine("```")
        }
        return title to body.take(MAX_BODY_CHARS)
    }

    private fun memoryInfo(ctx: Context): String = try {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(info)
        if (info.totalMem > 0) {
            val freeMb = info.availMem / (1024 * 1024)
            val totalMb = info.totalMem / (1024 * 1024)
            val low = if (info.lowMemory) " (low)" else ""
            "${freeMb}MB free / ${totalMb}MB$low"
        } else ""
    } catch (_: Throwable) {
        ""
    }

    /**
     * Strips data that could leak user state from a string.
     * - Filesystem paths under /storage/emulated, /data/user, /data/data
     * - URLs reduced to scheme + host + path (no query/fragment)
     * - Long opaque tokens (>= 24 chars of base64/hex/url-safe alphabet)
     * - Email addresses
     */
    internal fun sanitize(input: String): String {
        if (input.isEmpty()) return input
        var out = input
        out = STORAGE_PATH.replace(out, "<storage>")
        out = DATA_USER_PATH.replace(out, "<data>")
        out = DATA_DATA_PATH.replace(out, "<data>")
        out = URL_REGEX.replace(out) { match ->
            // Strip query/fragment but keep scheme://host/path
            match.value.substringBefore('?').substringBefore('#')
        }
        out = EMAIL_REGEX.replace(out, "<email>")
        out = TOKEN_REGEX.replace(out) { match ->
            // Don't blank-out short identifiers/words; only redact opaque blobs.
            if (looksLikeToken(match.value)) "<redacted>" else match.value
        }
        return out
    }

    private fun looksLikeToken(value: String): Boolean {
        if (value.length < 24) return false
        // Heuristic: must contain mixed case OR digits + letters, and not be a known
        // package-style identifier (which won't trigger TOKEN_REGEX anyway).
        val hasUpper = value.any { it.isUpperCase() }
        val hasLower = value.any { it.isLowerCase() }
        val hasDigit = value.any { it.isDigit() }
        return (hasUpper && hasLower) || (hasLower && hasDigit) || (hasUpper && hasDigit)
    }

    private val STORAGE_PATH = Regex("""/storage/emulated/\d+(?:/[^\s"')\]]*)?""")
    private val DATA_USER_PATH = Regex("""/data/user/\d+/[^\s"')\]]*""")
    private val DATA_DATA_PATH = Regex("""/data/data/[^\s"')\]]*""")
    private val URL_REGEX = Regex("""https?://[^\s"')\]]+""")
    private val EMAIL_REGEX = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
    private val TOKEN_REGEX = Regex("""[A-Za-z0-9+/=_\-]{24,}""")

    private fun postIssue(
        title: String,
        body: String,
        labels: List<String>,
        timeoutMs: Int,
    ): Boolean {
        val url = URL("https://api.github.com/repos/${BuildConfig.CRASH_REPORT_REPO}/issues")
        val payload = JSONObject().apply {
            put("title", title)
            put("body", body)
            put("labels", JSONArray().apply { labels.forEach { put(it) } })
        }.toString()

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Authorization", "Bearer ${BuildConfig.CRASH_REPORT_TOKEN}")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("User-Agent", "Meld-CrashReporter/${BuildConfig.VERSION_NAME}")
        }
        return try {
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code in 200..299) {
                true
            } else {
                val err = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                Timber.tag("CrashReporter").w("GitHub Issues API returned %d: %s", code, err?.take(200))
                false
            }
        } catch (t: Throwable) {
            Timber.tag("CrashReporter").w(t, "Failed to post issue")
            false
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private tailrec fun Throwable.deepestCause(): Throwable {
        val c = cause
        return if (c == null || c === this) this else c.deepestCause()
    }
}
