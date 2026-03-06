/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.MediaInfo
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.db.entities.FormatEntity
import com.metrolist.music.db.entities.Song
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.component.shimmer.ShimmerHost
import com.metrolist.music.ui.component.shimmer.TextPlaceholder

@Composable
fun ShowMediaInfo(videoId: String) {
    if (videoId.isBlank() || videoId.isEmpty()) return

    val windowInsets = WindowInsets.systemBars

    var info by remember {
        mutableStateOf<MediaInfo?>(null)
    }

    val database = LocalDatabase.current
    var song by remember { mutableStateOf<Song?>(null) }

    var currentFormat by remember { mutableStateOf<FormatEntity?>(null) }

    val playerConnection = LocalPlayerConnection.current
    val context = LocalContext.current

    LaunchedEffect(Unit, videoId) {
        info = YouTube.getMediaInfo(videoId).getOrNull()
    }

    LaunchedEffect(Unit, videoId) {
        database.song(videoId).collect {
            song = it
        }
    }

    LaunchedEffect(Unit, videoId) {
        database.format(videoId).collect {
            currentFormat = it
        }
    }

    LazyColumn(
        state = rememberLazyListState(),
        modifier = Modifier
            .padding(
                windowInsets
                    .asPaddingValues()
            )
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (info != null && song != null) {
            item(contentType = "MediaDetails") {
                Column {
                    val baseList = listOf(
                        stringResource(R.string.song_title) to song?.title,
                        stringResource(R.string.song_artists) to song?.artists?.joinToString { it.name },
                        stringResource(R.string.media_id) to song?.id
                    )

                    val baseIconsList = listOf(
                        R.drawable.music_note,
                        R.drawable.person,
                        R.drawable.media3_icon_bookmark_filled,
                    )

                    val iconsList = listOf(
                        R.drawable.media3_icon_feed,
                        R.drawable.media3_icon_thumb_up_unfilled,
                        R.drawable.media3_icon_thumb_down_unfilled,
                        R.drawable.key,
                        R.drawable.info,
                        R.drawable.radio,
                        R.drawable.gradient,
                        R.drawable.contrast,
                        R.drawable.volume_up,
                        R.drawable.volume_mute,
                        R.drawable.content_copy
                    )

                    val extendedList = if (currentFormat != null) {
                        listOf(
                            stringResource(R.string.views) to info?.viewCount?.let(::numberFormatter).orEmpty(),
                            stringResource(R.string.likes) to info?.like?.let(::numberFormatter).orEmpty(),
                            stringResource(R.string.dislikes) to info?.dislike?.let(::numberFormatter).orEmpty(),
                            "Itag" to currentFormat?.itag?.toString(),
                            stringResource(R.string.mime_type) to currentFormat?.mimeType,
                            stringResource(R.string.codecs) to currentFormat?.codecs,
                            stringResource(R.string.bitrate) to currentFormat?.bitrate?.let { "${it / 1000} Kbps" },
                            stringResource(R.string.sample_rate) to currentFormat?.sampleRate?.let { "$it Hz" },
                            stringResource(R.string.loudness) to currentFormat?.loudnessDb?.let { "$it dB" },
                            stringResource(R.string.volume) to if (playerConnection != null) "${(playerConnection.player.volume * 100).toInt()}%" else null,
                            stringResource(R.string.file_size) to
                                    currentFormat?.contentLength?.let {
                                        Formatter.formatShortFileSize(
                                            context,
                                            it
                                        )
                                    },
                        )
                    } else {
                        emptyList()
                    }

                    val cardsBaseList = mutableListOf<Material3SettingsItem>()
                    val cardsExtendedList = mutableListOf<Material3SettingsItem>()
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

                    baseList.forEachIndexed { index, (label, text) ->
                        val displayText = text ?: stringResource(R.string.unknown)
                        cardsBaseList += Material3SettingsItem(
                            title = { Text(label) },
                            description = { Text(displayText) },
                            icon = painterResource(baseIconsList[index]),
                            onClick = {
                                cm.setPrimaryClip(ClipData.newPlainText("text", displayText))
                                Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
                            },
                        )
                    }

                    extendedList.forEachIndexed { index, (label, text) ->
                        val displayText = text ?: stringResource(R.string.unknown)
                        cardsExtendedList += Material3SettingsItem(
                            title = { Text(label) },
                            description = { Text(displayText) },
                            icon = painterResource(iconsList[index]),
                            onClick = {
                                cm.setPrimaryClip(ClipData.newPlainText("text", displayText))
                                Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
                            },
                        )
                    }

                    Material3SettingsGroup(
                        title = stringResource(R.string.general),
                        items = cardsBaseList
                    )

                    Spacer(Modifier.height(8.dp))

                    Material3SettingsGroup(
                        title = stringResource(R.string.information),
                        items = cardsExtendedList
                    )

                    Spacer(Modifier.height(8.dp))

                    val descriptionText = info?.description ?: stringResource(R.string.unknown)

                    Material3SettingsGroup(
                        title = stringResource(R.string.description),
                        items = listOf(
                            Material3SettingsItem(
                                title = { Text(stringResource(R.string.description)) },
                                description = { Text(descriptionText) },
                                onClick = {
                                    cm.setPrimaryClip(ClipData.newPlainText("text", descriptionText))
                                    Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
                                }
                            )
                        )
                    )
                }
            }
        } else {
            item(contentType = "MediaInfoLoader") {
                ShimmerHost {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(all = 16.dp)
                    ) {
                        TextPlaceholder()
                    }
                }
            }
        }
    }
}