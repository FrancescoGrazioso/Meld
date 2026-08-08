/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import android.content.Context
import android.util.LruCache
import com.metrolist.music.constants.LyricsProviderOrderKey
import com.metrolist.music.constants.PreferredLyricsProvider
import com.metrolist.music.constants.PreferredLyricsProviderKey
import com.metrolist.music.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import com.metrolist.music.extensions.toEnum
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.utils.NetworkConnectivityObserver
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.reportException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject

private const val MAX_LYRICS_FETCH_MS = 10000L
private const val PRIMARY_GRACE_PERIOD_MS = 1200L
private const val PROVIDER_FETCH_TIMEOUT_MS = 5000L
private const val PROVIDER_NONE = ""

class LyricsHelper
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val networkConnectivity: NetworkConnectivityObserver,
) {
    private var lyricsProviders =
        listOf(
            LrcLibLyricsProvider,
            NetEaseLyricsProvider,
            KuGouLyricsProvider,
            BetterLyricsProvider,
            PaxsenixLyricsProvider,
            LyricsPlusProvider,
            YouTubeSubtitleLyricsProvider,
            YouTubeLyricsProvider
        )

    val preferred =
        context.dataStore.data
            .map { preferences ->
                val providerOrder = preferences[LyricsProviderOrderKey] ?: ""
                if (providerOrder.isNotBlank()) {
                    LyricsProviderRegistry.getOrderedProviders(providerOrder)
                } else {
                    LyricsProviderRegistry.getOrderedProviders(
                        LyricsProviderRegistry.serializeProviderOrder(LyricsProviderRegistry.getDefaultProviderOrder())
                    )
                }
            }.distinctUntilChanged()
            .map { providers ->
                lyricsProviders = providers
            }

    private val cache = LruCache<String, List<LyricsResult>>(MAX_CACHE_SIZE)
    private var currentLyricsJob: Job? = null

    suspend fun getLyrics(mediaMetadata: MediaMetadata): LyricsWithProvider {
        currentLyricsJob?.cancel()

        val cached = cache.get(mediaMetadata.id)?.firstOrNull()
        if (cached != null) {
            return LyricsWithProvider(cached.lyrics, cached.providerName)
        }

        // Check network connectivity before making network requests
        // Use synchronous check as fallback if flow doesn't emit
        val isNetworkAvailable = try {
            networkConnectivity.isCurrentlyConnected()
        } catch (e: Exception) {
            // If network check fails, try to proceed anyway
            true
        }

        if (!isNetworkAvailable) {
            return LyricsWithProvider(LYRICS_NOT_FOUND, PROVIDER_NONE)
        }

        val enabledProviders = lyricsProviders.filter { it.isEnabled(context) }
        if (enabledProviders.isEmpty()) {
            return LyricsWithProvider(LYRICS_NOT_FOUND, PROVIDER_NONE)
        }

        val cleanedTitle = LyricsUtils.cleanTitleForSearch(mediaMetadata.title)
        val artists = mediaMetadata.artists.joinToString { it.name }
        val duration = mediaMetadata.duration
        val album = mediaMetadata.album?.title

        suspend fun fetchFromProvider(provider: LyricsProvider): LyricsWithProvider? {
            return try {
                val res = withTimeoutOrNull(PROVIDER_FETCH_TIMEOUT_MS) {
                    provider.getLyrics(context, mediaMetadata.id, cleanedTitle, artists, duration, album)
                }
                if (res?.isSuccess == true) {
                    val lyricsStr = res.getOrNull()
                    if (!lyricsStr.isNullOrBlank() && lyricsStr != LYRICS_NOT_FOUND) {
                        val filteredLyrics = LyricsUtils.filterLyricsCreditLines(lyricsStr)
                        LyricsWithProvider(filteredLyrics, provider.name)
                    } else null
                } else null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag("LyricsHelper").w(e, "${provider.name} threw exception: ${e.message}")
                null
            }
        }

        val overallResult = withTimeoutOrNull(MAX_LYRICS_FETCH_MS) {
            coroutineScope {
                // 1. Start primary provider immediately as a deferred task
                val primaryProvider = enabledProviders.first()
                Timber.tag("LyricsHelper").d("Trying primary provider: ${primaryProvider.name} for $cleanedTitle")
                val primaryDeferred = async { fetchFromProvider(primaryProvider) }

                // Wait up to PRIMARY_GRACE_PERIOD_MS for primary provider to complete
                val primaryResult = withTimeoutOrNull(PRIMARY_GRACE_PERIOD_MS) {
                    primaryDeferred.await()
                }

                if (primaryResult != null) {
                    Timber.tag("LyricsHelper").i("Successfully got lyrics from primary provider ${primaryProvider.name}")
                    coroutineContext.cancelChildren()
                    return@coroutineScope primaryResult
                }

                // 2. If primary provider was delayed or returned null, launch remaining providers concurrently
                // Note: primaryDeferred remains active and is NOT cancelled, preserving its head start!
                Timber.tag("LyricsHelper").d("Primary provider delayed or returned null. Launching parallel fallbacks for $cleanedTitle")
                val fallbackDeferreds = enabledProviders.drop(1).map { provider ->
                    provider to async { fetchFromProvider(provider) }
                }

                val allDeferreds = listOf(primaryProvider to primaryDeferred) + fallbackDeferreds

                // Check results in priority order
                var bestResult: LyricsWithProvider? = null
                for ((_, deferred) in allDeferreds) {
                    val res = deferred.await()
                    if (res != null && bestResult == null) {
                        bestResult = res
                        break
                    }
                }
                coroutineContext.cancelChildren()
                bestResult
            }
        }

        val finalResult = overallResult ?: LyricsWithProvider(LYRICS_NOT_FOUND, PROVIDER_NONE)
        if (finalResult.lyrics != LYRICS_NOT_FOUND && finalResult.provider.isNotBlank()) {
            cache.put(mediaMetadata.id, listOf(LyricsResult(finalResult.provider, finalResult.lyrics)))
        }
        return finalResult
    }

    suspend fun getAllLyrics(
        mediaId: String,
        songTitle: String,
        songArtists: String,
        duration: Int,
        album: String? = null,
        callback: (LyricsResult) -> Unit,
    ) {
        currentLyricsJob?.cancel()

        val cacheKey = "$songArtists-$songTitle".replace(" ", "")
        cache.get(cacheKey)?.let { results ->
            results.forEach {
                callback(it)
            }
            return
        }

        // Check network connectivity before making network requests
        // Use synchronous check as fallback if flow doesn't emit
        val isNetworkAvailable = try {
            networkConnectivity.isCurrentlyConnected()
        } catch (e: Exception) {
            // If network check fails, try to proceed anyway
            true
        }

        if (!isNetworkAvailable) {
            // Still try to proceed in case of false negative
            return
        }

        val allResult = mutableListOf<LyricsResult>()
        currentLyricsJob = CoroutineScope(SupervisorJob()).launch {
            val cleanedTitle = LyricsUtils.cleanTitleForSearch(songTitle)
            lyricsProviders.forEach { provider ->
                if (provider.isEnabled(context)) {
                    try {
                        provider.getAllLyrics(context, mediaId, cleanedTitle, songArtists, duration, album) { lyrics ->
                            val filteredLyrics = LyricsUtils.filterLyricsCreditLines(lyrics)
                            val result = LyricsResult(provider.name, filteredLyrics)
                            allResult += result
                            callback(result)
                        }
                    } catch (e: Exception) {
                        // Catch network-related exceptions like UnresolvedAddressException
                        reportException(e)
                    }
                }
            }
            cache.put(cacheKey, allResult)
        }

        currentLyricsJob?.join()
    }

    fun cancelCurrentLyricsJob() {
        currentLyricsJob?.cancel()
        currentLyricsJob = null
    }

    companion object {
        private const val MAX_CACHE_SIZE = 3
    }
}

data class LyricsResult(
    val providerName: String,
    val lyrics: String,
)

data class LyricsWithProvider(
    val lyrics: String,
    val provider: String,
)
