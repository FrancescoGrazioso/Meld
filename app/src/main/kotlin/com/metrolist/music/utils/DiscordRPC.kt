/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import com.metrolist.music.R
import com.metrolist.music.db.entities.Song
import com.my.kizzy.rpc.KizzyRPC
import com.my.kizzy.rpc.RpcImage

class DiscordRPC(
    val context: Context,
    token: String,
) : KizzyRPC(
    token = token,
    os = "Android",
    browser = "Discord Android",
    device = android.os.Build.DEVICE,
    userAgent = SuperProperties.userAgent,
    superPropertiesBase64 = SuperProperties.superPropertiesBase64
) {
    suspend fun updateSong(
        song: Song,
        currentPlaybackTimeMillis: Long,
        playbackSpeed: Float = 1.0f,
        useDetails: Boolean = false,
        status: String = "online",
        button1Text: String = "",
        button1Visible: Boolean = true,
        button1Url: String = "",
        button2Text: String = "",
        button2Visible: Boolean = true,
        button2Url: String = "",
        activityType: String = "listening",
        activityName: String = "",
    ) = runCatching {
        val currentTime = System.currentTimeMillis()

        val adjustedPlaybackTime = (currentPlaybackTimeMillis / playbackSpeed).toLong()
        val calculatedStartTime = currentTime - adjustedPlaybackTime

        val songTitleWithRate = if (playbackSpeed != 1.0f) {
            "${song.song.title} [${String.format("%.2fx", playbackSpeed)}]"
        } else {
            song.song.title
        }

        val remainingDuration = song.song.duration * 1000L - currentPlaybackTimeMillis
        val adjustedRemainingDuration = (remainingDuration / playbackSpeed).toLong()

        val buttonsList = mutableListOf<Pair<String, String>>()
        if (button1Visible) {
            // Discord silently drops a button whose label is empty or longer than 32 chars,
            // so clamp it here as a final safety net (the UI already enforces this).
            val label = resolveVariables(
                button1Text.ifEmpty { "Listen on YouTube Music" },
                song
            ).take(BUTTON_LABEL_MAX)
            val url = resolveVariables(
                button1Url.ifEmpty { "https://music.youtube.com/watch?v=${song.song.id}" },
                song
            ).trim()
            if (label.isNotBlank() && url.isValidButtonUrl()) {
                buttonsList.add(label to url)
            }
        }
        if (button2Visible) {
            val label = resolveVariables(
                button2Text.ifEmpty { "Visit Meld" },
                song
            ).take(BUTTON_LABEL_MAX)
            val url = resolveVariables(
                button2Url.ifEmpty { "https://github.com/FrancescoGrazioso/Meld" },
                song
            ).trim()
            if (label.isNotBlank() && url.isValidButtonUrl()) {
                buttonsList.add(label to url)
            }
        }

        val type = when (activityType) {
            "playing" -> Type.PLAYING
            "watching" -> Type.WATCHING
            "competing" -> Type.COMPETING
            else -> Type.LISTENING
        }

        val name = activityName.ifEmpty {
            context.getString(R.string.app_name).removeSuffix(" Debug")
        }.take(ACTIVITY_NAME_MAX)

        setActivity(
            name = name,
            details = songTitleWithRate,
            state = song.artists.joinToString { it.name },
            detailsUrl = "https://music.youtube.com/watch?v=${song.song.id}",
            largeImage = song.song.thumbnailUrl?.let { RpcImage.ExternalImage(it) },
            smallImage = song.artists.firstOrNull()?.thumbnailUrl?.let { RpcImage.ExternalImage(it) },
            largeText = song.album?.title,
            smallText = song.artists.firstOrNull()?.name,
            buttons = if (buttonsList.isNotEmpty()) buttonsList else null,
            type = type,
            statusDisplayType = if (useDetails) StatusDisplayType.DETAILS else StatusDisplayType.STATE,
            since = currentTime,
            startTime = calculatedStartTime,
            endTime = currentTime + adjustedRemainingDuration,
            applicationId = APPLICATION_ID,
            status = status
        )
    }

    override suspend fun close() {
        super.close()
    }

    companion object {
        private const val APPLICATION_ID = "1411019391843172514"

        // Discord-enforced limits (see Discord API docs for activity buttons).
        private const val BUTTON_LABEL_MAX = 32
        private const val ACTIVITY_NAME_MAX = 128

        /**
         * Resolves template variables in text.
         * Supported: {song_name}, {artist_name}, {album_name}
         */
        fun resolveVariables(text: String, song: Song): String {
            return text
                .replace("{song_name}", song.song.title)
                .replace("{artist_name}", song.artists.joinToString { it.name })
                .replace("{album_name}", song.album?.title ?: "")
        }

        /** Discord only accepts http/https button URLs; anything else is dropped. */
        private fun String.isValidButtonUrl(): Boolean =
            startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)
    }
}
