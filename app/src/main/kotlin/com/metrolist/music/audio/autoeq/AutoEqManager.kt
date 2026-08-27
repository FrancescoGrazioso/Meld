/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.audio.autoeq

import com.metrolist.music.eq.data.ParametricEQ
import com.metrolist.music.eq.data.ParametricEQParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class AutoEqHeadphone(
    val name: String,
    val brand: String,
    val source: String,
    val path: String,
)

object AutoEqManager {
    private const val TAG = "AutoEqManager"
    private const val BASE_URL = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results"
    
    private val SOURCES = listOf("oratory1990", "crinacle", "rtings", "innerfidelity", "headphonecom")

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    // Pre-indexed top popular headphone models for instant offline & online search
    private val POPULAR_HEADPHONES = listOf(
        AutoEqHeadphone("Sony WH-1000XM4", "Sony", "oratory1990", "oratory1990/over-ear/Sony WH-1000XM4"),
        AutoEqHeadphone("Sony WH-1000XM5", "Sony", "oratory1990", "oratory1990/over-ear/Sony WH-1000XM5"),
        AutoEqHeadphone("Sony WF-1000XM4", "Sony", "crinacle", "crinacle/harman_in-ear_2019v2/Sony WF-1000XM4"),
        AutoEqHeadphone("Sony WF-1000XM5", "Sony", "crinacle", "crinacle/harman_in-ear_2019v2/Sony WF-1000XM5"),
        AutoEqHeadphone("Sony MDR-7506", "Sony", "oratory1990", "oratory1990/over-ear/Sony MDR-7506"),
        AutoEqHeadphone("Sennheiser HD 600", "Sennheiser", "oratory1990", "oratory1990/over-ear/Sennheiser HD 600"),
        AutoEqHeadphone("Sennheiser HD 650", "Sennheiser", "oratory1990", "oratory1990/over-ear/Sennheiser HD 650"),
        AutoEqHeadphone("Sennheiser HD 560S", "Sennheiser", "oratory1990", "oratory1990/over-ear/Sennheiser HD 560S"),
        AutoEqHeadphone("Sennheiser HD 800 S", "Sennheiser", "oratory1990", "oratory1990/over-ear/Sennheiser HD 800 S"),
        AutoEqHeadphone("Sennheiser Momentum 4", "Sennheiser", "oratory1990", "oratory1990/over-ear/Sennheiser Momentum 4 Wireless"),
        AutoEqHeadphone("Apple AirPods Pro (2nd generation)", "Apple", "crinacle", "crinacle/harman_in-ear_2019v2/Apple AirPods Pro 2"),
        AutoEqHeadphone("Apple AirPods Pro", "Apple", "crinacle", "crinacle/harman_in-ear_2019v2/Apple AirPods Pro"),
        AutoEqHeadphone("Apple AirPods Max", "Apple", "oratory1990", "oratory1990/over-ear/Apple AirPods Max"),
        AutoEqHeadphone("Apple EarPods", "Apple", "crinacle", "crinacle/harman_in-ear_2019v2/Apple EarPods"),
        AutoEqHeadphone("Bose QuietComfort 45", "Bose", "rtings", "rtings/rtings_harman_over-ear_2018/Bose QuietComfort 45"),
        AutoEqHeadphone("Bose QuietComfort 35 II", "Bose", "oratory1990", "oratory1990/over-ear/Bose QuietComfort 35 II"),
        AutoEqHeadphone("Bose QuietComfort Ultra", "Bose", "rtings", "rtings/rtings_harman_over-ear_2018/Bose QuietComfort Ultra Headphones"),
        AutoEqHeadphone("Audio-Technica ATH-M50x", "Audio-Technica", "oratory1990", "oratory1990/over-ear/Audio-Technica ATH-M50x"),
        AutoEqHeadphone("Audio-Technica ATH-M40x", "Audio-Technica", "oratory1990", "oratory1990/over-ear/Audio-Technica ATH-M40x"),
        AutoEqHeadphone("Beyerdynamic DT 770 PRO 80 Ohm", "Beyerdynamic", "oratory1990", "oratory1990/over-ear/Beyerdynamic DT 770 PRO (80 Ohm)"),
        AutoEqHeadphone("Beyerdynamic DT 990 PRO", "Beyerdynamic", "oratory1990", "oratory1990/over-ear/Beyerdynamic DT 990 PRO"),
        AutoEqHeadphone("Beyerdynamic DT 1990 PRO", "Beyerdynamic", "oratory1990", "oratory1990/over-ear/Beyerdynamic DT 1990 PRO (Balanced earpads)"),
        AutoEqHeadphone("Moondrop Chu", "Moondrop", "crinacle", "crinacle/harman_in-ear_2019v2/Moondrop Chu"),
        AutoEqHeadphone("Moondrop Chu II", "Moondrop", "crinacle", "crinacle/harman_in-ear_2019v2/Moondrop Chu 2"),
        AutoEqHeadphone("Moondrop Aria", "Moondrop", "crinacle", "crinacle/harman_in-ear_2019v2/Moondrop Aria"),
        AutoEqHeadphone("Moondrop Blessing 2", "Moondrop", "crinacle", "crinacle/harman_in-ear_2019v2/Moondrop Blessing 2"),
        AutoEqHeadphone("Moondrop Blessing 3", "Moondrop", "crinacle", "crinacle/harman_in-ear_2019v2/Moondrop Blessing 3"),
        AutoEqHeadphone("Moondrop Kato", "Moondrop", "crinacle", "crinacle/harman_in-ear_2019v2/Moondrop Kato"),
        AutoEqHeadphone("Samsung Galaxy Buds 2 Pro", "Samsung", "crinacle", "crinacle/harman_in-ear_2019v2/Samsung Galaxy Buds2 Pro"),
        AutoEqHeadphone("Samsung Galaxy Buds Pro", "Samsung", "crinacle", "crinacle/harman_in-ear_2019v2/Samsung Galaxy Buds Pro"),
        AutoEqHeadphone("Samsung Galaxy Buds 2", "Samsung", "crinacle", "crinacle/harman_in-ear_2019v2/Samsung Galaxy Buds2"),
        AutoEqHeadphone("HiFiMAN Sundara", "HiFiMAN", "oratory1990", "oratory1990/over-ear/HiFiMAN Sundara (2020 revised earpads)"),
        AutoEqHeadphone("HiFiMAN Edition XS", "HiFiMAN", "oratory1990", "oratory1990/over-ear/HiFiMAN Edition XS"),
        AutoEqHeadphone("HiFiMAN Arya", "HiFiMAN", "oratory1990", "oratory1990/over-ear/HiFiMAN Arya Stealth"),
        AutoEqHeadphone("7Hz Salnotes Zero", "7Hz", "crinacle", "crinacle/harman_in-ear_2019v2/7Hz Zero"),
        AutoEqHeadphone("Tangzu Wan'er", "Tangzu", "crinacle", "crinacle/harman_in-ear_2019v2/Tangzu Wan'er"),
        AutoEqHeadphone("AKG K371", "AKG", "oratory1990", "oratory1990/over-ear/AKG K371"),
        AutoEqHeadphone("AKG K240 Studio", "AKG", "oratory1990", "oratory1990/over-ear/AKG K240 Studio"),
        AutoEqHeadphone("Shure SE215", "Shure", "crinacle", "crinacle/harman_in-ear_2019v2/Shure SE215"),
        AutoEqHeadphone("Koss Porta Pro", "Koss", "oratory1990", "oratory1990/on-ear/Koss Porta Pro"),
        AutoEqHeadphone("Focal Bathys", "Focal", "oratory1990", "oratory1990/over-ear/Focal Bathys (active, ANC on)"),
        AutoEqHeadphone("Focal Clear", "Focal", "oratory1990", "oratory1990/over-ear/Focal Clear"),
        AutoEqHeadphone("Anker Soundcore Space Q45", "Anker", "rtings", "rtings/rtings_harman_over-ear_2018/Anker Soundcore Space Q45"),
        AutoEqHeadphone("Anker Soundcore Life Q30", "Anker", "rtings", "rtings/rtings_harman_over-ear_2018/Anker Soundcore Life Q30"),
        AutoEqHeadphone("Nothing Ear (2)", "Nothing", "crinacle", "crinacle/harman_in-ear_2019v2/Nothing Ear (2)"),
    )

    fun searchHeadphones(query: String): List<AutoEqHeadphone> {
        val trimmed = query.trim().lowercase()
        if (trimmed.isEmpty()) return POPULAR_HEADPHONES
        return POPULAR_HEADPHONES.filter {
            it.name.lowercase().contains(trimmed) ||
            it.brand.lowercase().contains(trimmed) ||
            it.source.lowercase().contains(trimmed)
        }
    }

    suspend fun fetchParametricEq(headphone: AutoEqHeadphone): Result<ParametricEQ> = withContext(Dispatchers.IO) {
        val encodedPath = headphone.path.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        val rawUrl = "$BASE_URL/$encodedPath/${URLEncoder.encode("${headphone.name} ParametricEQ.txt", "UTF-8").replace("+", "%20")}"
        val fallbackUrl = "$BASE_URL/$encodedPath/ParametricEQ.txt"

        val urlsToTry = listOf(rawUrl, fallbackUrl)

        for (url in urlsToTry) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Loop-Music-Player")
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (!body.isNullOrBlank()) {
                            val parsed = ParametricEQParser.parseText(body)
                            if (parsed.bands.isNotEmpty()) {
                                Timber.tag(TAG).i("Successfully fetched AutoEQ for ${headphone.name} with ${parsed.bands.size} bands")
                                return@withContext Result.success(parsed)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).d("AutoEQ download try failed for $url: ${e.message}")
            }
        }

        Result.failure(Exception("Failed to download AutoEQ curve for ${headphone.name}"))
    }
}
