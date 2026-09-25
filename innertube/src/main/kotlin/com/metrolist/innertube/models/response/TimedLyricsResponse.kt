/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.innertube.models.response

import kotlinx.serialization.Serializable

/**
 * YouTube Music serves line-timed lyrics only to its mobile clients. The same lyrics browseId that
 * answers WEB_REMIX with a flat `musicDescriptionShelfRenderer` answers ANDROID_MUSIC with this
 * component tree, where every line carries the cue range it should be highlighted for.
 */
@Serializable
data class TimedLyricsResponse(
    val contents: Contents? = null,
) {
    @Serializable
    data class Contents(
        val elementRenderer: ElementRenderer? = null,
    )

    @Serializable
    data class ElementRenderer(
        val newElement: NewElement? = null,
    )

    @Serializable
    data class NewElement(
        val type: Type? = null,
    )

    @Serializable
    data class Type(
        val componentType: ComponentType? = null,
    )

    @Serializable
    data class ComponentType(
        val model: Model? = null,
    )

    @Serializable
    data class Model(
        val timedLyricsModel: TimedLyricsModel? = null,
    )

    @Serializable
    data class TimedLyricsModel(
        val lyricsData: LyricsData? = null,
    )

    @Serializable
    data class LyricsData(
        val timedLyricsData: List<TimedLyric>? = null,
    )

    @Serializable
    data class TimedLyric(
        val lyricLine: String? = null,
        val cueRange: CueRange? = null,
    )

    /** Times arrive as strings, as everywhere else in InnerTube. */
    @Serializable
    data class CueRange(
        val startTimeMilliseconds: String? = null,
        val endTimeMilliseconds: String? = null,
    )

    val lines: List<TimedLyric>
        get() = contents
            ?.elementRenderer
            ?.newElement
            ?.type
            ?.componentType
            ?.model
            ?.timedLyricsModel
            ?.lyricsData
            ?.timedLyricsData
            .orEmpty()
}
