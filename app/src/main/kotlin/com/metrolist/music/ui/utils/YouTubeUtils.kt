/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:Suppress("LocalVariableName")

package com.metrolist.music.ui.utils

import kotlin.math.roundToInt

private val GOOGLEUSERCONTENT_SIZE_PATTERN =
    Regex("^(https://(?:lh3|yt3)\\.googleusercontent\\.com/[^?]*?)=w(\\d+)-h(\\d+)[^?]*(\\?.*)?$")
private val GGPHT_SIZE_PATTERN =
    Regex("^(https://yt3\\.ggpht\\.com/[^?=]+)=(?:s\\d+|w\\d+-h\\d+)[^?]*(\\?.*)?$")
fun String.resize(
    width: Int? = null,
    height: Int? = null,
): String {
    if (width == null && height == null) return this

    GOOGLEUSERCONTENT_SIZE_PATTERN
        .matchEntire(this)
        ?.groupValues
        ?.let { group ->
            val originalWidth = group[2].toInt()
            val originalHeight = group[3].toInt()
            val query = group[4]
            val targetWidth = width ?: ((height!!.toDouble() * originalWidth) / originalHeight).roundToInt()
            val targetHeight = height ?: ((width!!.toDouble() * originalHeight) / originalWidth).roundToInt()
            return "${group[1]}=w${targetWidth.coerceAtLeast(1)}-h${targetHeight.coerceAtLeast(1)}-p-l90-rj$query"
        }

    GGPHT_SIZE_PATTERN.matchEntire(this)?.groupValues?.let { group ->
        val query = group[2]
        return if (width != null && height != null) {
            "${group[1]}=w$width-h$height-p-l90-rj$query"
        } else {
            "${group[1]}=s${width ?: height}$query"
        }
    }

    return this
}

/**
 * Best-effort lower-res YouTube video thumbnail URL to fall back to when
 * maxresdefault.jpg is not available for the video. Returns null when the
 * input is not an i.ytimg.com URL.
 */
fun String.ytVideoThumbFallback(): String? {
    val match = YT_VIDEO_THUMB_PATTERN.matchEntire(this) ?: return null
    val (kind, videoId, variant, ext) = match.destructured
    val next = when (variant.lowercase()) {
        "maxresdefault" -> "sddefault"
        "sddefault" -> "hqdefault"
        "hqdefault" -> "mqdefault"
        else -> return null
    }
    return "https://i.ytimg.com/$kind/$videoId/$next.$ext"
}
