package com.meld.spotify.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meld.spotify.playback.SpotifyVersionPicker
import com.meld.spotify.playback.SpotifyVersionState
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.close
import com.metrolist.shared.generated.resources.meld_spotify_choose_version
import com.metrolist.shared.generated.resources.meld_spotify_version_automatic
import com.metrolist.shared.generated.resources.meld_spotify_version_chosen
import com.metrolist.shared.generated.resources.meld_spotify_version_current
import com.metrolist.shared.generated.resources.meld_spotify_version_none
import com.metrolist.shared.generated.resources.meld_spotify_version_unreachable
import com.metrolist.shared.generated.resources.retry
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.ui.components.Dialog
import com.metrolist.shared.ui.utils.DragScrollableColumn
import com.metrolist.shared.ui.icons.MetrolistIcons
import org.jetbrains.compose.resources.stringResource
import org.koin.mp.KoinPlatform

private const val VERSION_IMAGE_SIZE = 96

/** The "Choose the YouTube version" dialog, drawn while a track row has opened it. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SpotifyVersionDialog() {
    val picker = remember { KoinPlatform.getKoinOrNull()?.getOrNull<SpotifyVersionPicker>() } ?: return
    val state = picker.state.collectAsState().value ?: return
    Dialog(onDismissRequest = picker::close) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.widthIn(max = 560.dp).heightIn(max = 600.dp).padding(vertical = 16.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(Res.string.meld_spotify_choose_version), style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "${state.track.name} • ${state.track.artists.joinToString { it.name }}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = picker::close, shapes = IconButtonDefaults.shapes()) {
                        Icon(MetrolistIcons.Outlined.Close, contentDescription = stringResource(Res.string.close))
                    }
                }
                when {
                    state.loading -> {
                        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            ContainedLoadingIndicator()
                        }
                    }

                    state.unreachable -> {
                        SpotifyNotice(
                            stringResource(Res.string.meld_spotify_version_unreachable),
                            stringResource(Res.string.retry),
                            picker::retry,
                            isError = true,
                        )
                    }

                    state.candidates.isEmpty() -> {
                        SpotifyNotice(stringResource(Res.string.meld_spotify_version_none), null, {})
                    }

                    else -> {
                        if (state.manual) {
                            TextButton(onClick = picker::useAutomatic, modifier = Modifier.padding(horizontal = 12.dp)) {
                                Text(stringResource(Res.string.meld_spotify_version_automatic))
                            }
                        }
                        DragScrollableColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                            items(state.candidates, key = { it.id }) { song ->
                                SpotifyLibraryRow(
                                    title = song.title,
                                    subtitle = versionSubtitle(song, state),
                                    imageUrl = song.thumbnail,
                                    icon = MetrolistIcons.Outlined.MusicNote,
                                    onClick = { picker.choose(song) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The artists and length that tell two versions apart; the song that plays now says so first. */
@Composable
private fun versionSubtitle(
    song: SongItem,
    state: SpotifyVersionState,
): String {
    val current =
        if (song.id == state.currentId) {
            stringResource(if (state.manual) Res.string.meld_spotify_version_chosen else Res.string.meld_spotify_version_current)
        } else {
            null
        }
    return listOfNotNull(
        current,
        song.artists.joinToString { it.name }.takeIf { it.isNotBlank() },
        song.duration?.let { (it * 1000L).asDuration() }?.takeIf { it.isNotEmpty() },
    ).joinToString(" • ")
}
