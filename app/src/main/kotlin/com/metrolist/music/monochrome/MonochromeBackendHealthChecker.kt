/**
 * Pings the Monochrome resolver backends and reports live reachability so users
 * can see which proxy is up before playback fails on them. Designed to be
 * triggered on-demand from the settings UI.
 */
package com.metrolist.music.monochrome

import com.metrolist.music.constants.MonochromeBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object MonochromeBackendHealthChecker {

    enum class Status { ONLINE, REACHABLE, OFFLINE }

    data class Target(
        val backend: MonochromeBackend,
        val name: String,
        val endpoint: String,
    ) {
        fun toRequest(): Request {
            return Request.Builder()
                .url(endpoint)
                .get()
                .header("Accept", "application/json,text/html,*/*")
                .header("User-Agent", MonochromeAudioProvider.BROWSER_USER_AGENT)
                .build()
        }
    }

    data class Result(
        val target: Target,
        val status: Status,
        val latencyMs: Long?,
        val message: String,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun checkAll(customUrl: String?): List<Result> = coroutineScope {
        val targets = mutableListOf(
            Target(
                backend = MonochromeBackend.OFFICIAL,
                name = "Official (api.monochrome.tf)",
                endpoint = "${MonochromeAudioProvider.baseUrlFor(MonochromeBackend.OFFICIAL, null)}/",
            ),
            Target(
                backend = MonochromeBackend.SAMIDY,
                name = "Samidy (samidy.monochrome.tf)",
                endpoint = "${MonochromeAudioProvider.baseUrlFor(MonochromeBackend.SAMIDY, null)}/",
            )
        )
        if (!customUrl.isNullOrBlank()) {
            targets.add(
                Target(
                    backend = MonochromeBackend.CUSTOM,
                    name = "Custom Endpoint",
                    endpoint = "${customUrl.trim().removeSuffix("/")}/",
                )
            )
        }

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
