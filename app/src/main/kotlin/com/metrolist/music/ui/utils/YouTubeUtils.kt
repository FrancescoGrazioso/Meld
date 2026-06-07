/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.utils

private val YT_VIDEO_THUMB_PATTERN =
    "https?://i\\.ytimg\\.com/(vi|vi_webp)/([^/]+)/([a-z0-9_]+)\\.(jpg|webp)(\\?.*)?"
        .toRegex(RegexOption.IGNORE_CASE)

fun String.resize(
    width: Int? = null,
    height: Int? = null,
): String {
    if (width == null && height == null) return this
    "https://lh3\\.googleusercontent\\.com/.*=w(\\d+)-h(\\d+).*".toRegex()
        .matchEntire(this)?.groupValues?.let { group ->
        val (W, H) = group.drop(1).map { it.toInt() }
        var w = width
        var h = height
        if (w != null && h == null) h = (w / W) * H
        if (w == null && h != null) w = (h / H) * W
        return "${split("=w")[0]}=w$w-h$h-p-l90-rj"
    }
    if (this matches "https://yt3\\.ggpht\\.com/.*=s(\\d+)".toRegex()) {
        return "$this-s${width ?: height}"
    }
    // YouTube video thumbnails live at i.ytimg.com/vi/<videoId>/<variant>.jpg.
    // Variants run default (120x90) → mqdefault → hqdefault → sddefault →
    // maxresdefault (1280x720). When a low-tier variant is requested but the
    // caller wants a high-res image, rewrite to maxresdefault. The Coil
    // fallback chain in [com.metrolist.music.ui.player.Thumbnail.ThumbnailImage]
    // swaps back down if maxresdefault 404s (not all videos have it).
    YT_VIDEO_THUMB_PATTERN.matchEntire(this)?.let { match ->
        val target = (width ?: height ?: 0)
        if (target <= 360) return this
        val (kind, videoId, _, ext) = match.destructured
        return "https://i.ytimg.com/$kind/$videoId/maxresdefault.$ext"
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
