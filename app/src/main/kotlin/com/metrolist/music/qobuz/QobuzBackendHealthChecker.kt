/**
 * Pings the Qobuz resolver backends and reports live reachability so users
 * can see which proxy is up before playback fails on them. Designed to be
 * triggered on-demand from the settings UI — no internal polling.
 */
package com.metrolist.music.qobuz

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object QobuzBackendHealthChecker {

    enum class Status { ONLINE, REACHABLE, OFFLINE }

    data class Target(
        val backend: QobuzAudioProvider.ResolverBackend,
        val name: String,
        val endpoint: String,
        private val tokenCountry: String? = null,
    ) {
        fun toRequest(): Request {
            val builder = Request.Builder()
                .url(endpoint)
                .get()
                .header("Accept", "application/json,text/html,*/*")
                .header("User-Agent", QobuzAudioProvider.BROWSER_USER_AGENT)
            if (tokenCountry != null) {
                builder.header("Token-Country", tokenCountry)
            }
            return builder.build()
        }
    }

    data class Result(
        val target: Target,
        val status: Status,
        val latencyMs: Long?,
        val message: String,
    )

    // Computed each call so user-configured endpoint overrides (applied via
    // QobuzAudioProvider.configureEndpoints) are reflected in the health check.
    val targets: List<Target>
        get() = listOf(
            Target(
                backend = QobuzAudioProvider.ResolverBackend.TRYPT,
                name = "TrypT HiFi",
                endpoint = "${QobuzAudioProvider.tryptBase}/api/get-music?q=test&offset=0",
                tokenCountry = "US",
            ),
            Target(
                backend = QobuzAudioProvider.ResolverBackend.JUMO,
                name = "Jumo",
                endpoint = "${QobuzAudioProvider.jumoBase}/",
            ),
            Target(
                backend = QobuzAudioProvider.ResolverBackend.MONOKENNY,
                name = "Monokenny",
                endpoint = "${QobuzAudioProvider.kennyBase}/api/get-music?q=test&offset=0",
            ),
            Target(
                backend = QobuzAudioProvider.ResolverBackend.SQUID,
                name = "Squid",
                endpoint = "${QobuzAudioProvider.squidBase}/api/get-music?q=test&offset=0",
                tokenCountry = "US",
            ),
        )

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun checkAll(): List<Result> = coroutineScope {
        targets.map { target ->
            async(Dispatchers.IO) { check(target) }
        }.awaitAll()
    }

    suspend fun check(target: Target): Result = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        runCatching {
            client.newCall(target.toRequest()).execute().use { response ->
                val elapsed = (System.nanoTime() - started) / 1_000_000L
                Result(
                    target = target,
                    status = response.code.toHealthStatus(),
                    latencyMs = elapsed,
                    message = response.code.toHealthMessage(),
                )
            }
        }.getOrElse { error ->
            Result(
                target = target,
                status = Status.OFFLINE,
                latencyMs = null,
                message = error.message ?: error.javaClass.simpleName,
            )
        }
    }

    private fun Int.toHealthStatus(): Status =
        when (this) {
            in 200..299 -> Status.ONLINE
            in 300..499 -> Status.REACHABLE
            else -> Status.OFFLINE
        }

    private fun Int.toHealthMessage(): String =
        when (this) {
            in 200..299 -> "HTTP $this"
            401, 403 -> "HTTP $this, auth required"
            404, 405 -> "HTTP $this, endpoint answered"
            429 -> "HTTP 429, rate limited"
            in 300..499 -> "HTTP $this, reachable"
            in 500..599 -> "HTTP $this, server error"
            else -> "HTTP $this"
        }
}
