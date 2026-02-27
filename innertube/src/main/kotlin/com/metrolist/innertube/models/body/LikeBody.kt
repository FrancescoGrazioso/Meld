package com.metrolist.innertube.models.body

import com.metrolist.innertube.models.Context
import kotlinx.serialization.Serializable

@Serializable
data class LikeBody(
    val context: Context,
    val target: Target,
) {
    /**
     * Target for like/unlike operations.
     * Note: Only one of videoId or playlistId should be set.
     * Using a flat structure instead of sealed class to avoid type discriminator in serialization.
     */
    @Serializable
    data class Target(
        val videoId: String? = null,
        val playlistId: String? = null,
    ) {
        companion object {
            fun video(id: String) = Target(videoId = id)
            fun playlist(id: String) = Target(playlistId = id)
        }
    }
}
