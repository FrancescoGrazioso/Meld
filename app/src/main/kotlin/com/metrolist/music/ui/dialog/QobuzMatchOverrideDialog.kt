/**
 * Dialog that lets the user pin a specific Qobuz track to a given mediaId
 * when the auto-matcher picks the wrong one. Fires a Qobuz multi-backend
 * search on open, shows the top candidates, and persists the user's choice
 * via [com.metrolist.music.playback.MusicService.setQobuzMatchOverride].
 */
package com.metrolist.music.ui.dialog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.qobuz.QobuzAudioProvider
import com.metrolist.music.qobuz.QobuzMatchOverride

@Composable
fun QobuzMatchOverrideDialog(
    mediaId: String,
    title: String,
    artists: List<String>,
    album: String?,
    isrc: String?,
    durationMs: Long?,
    onDismiss: () -> Unit,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val service = playerConnection.service

    var loading by remember { mutableStateOf(true) }
    var candidates by remember { mutableStateOf<List<QobuzAudioProvider.CandidateMetadata>>(emptyList()) }
    var current by remember { mutableStateOf<QobuzMatchOverride?>(null) }
    var selectedTrackId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(mediaId) {
        current = service.getQobuzMatchOverride(mediaId)
        selectedTrackId = current?.qobuzTrackId
        candidates = service.searchQobuzCandidates(
            mediaId = mediaId,
            title = title,
            artists = artists,
            album = album,
            isrc = isrc,
            durationMs = durationMs,
        )
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.qobuz_match_override)) },
        text = {
            Column {
                current?.let {
                    Text(
                        text = stringResource(R.string.qobuz_match_override_current, it.label),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                when {
                    loading -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(vertical = 16.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                            Text(stringResource(R.string.qobuz_match_override_searching))
                        }
                    }

                    candidates.isEmpty() -> {
                        Text(
                            text = stringResource(R.string.qobuz_match_override_empty),
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    }

                    else -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp),
                        ) {
                            items(candidates, key = { it.trackId }) { candidate ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedTrackId = candidate.trackId }
                                        .padding(vertical = 8.dp),
                                ) {
                                    RadioButton(
                                        selected = selectedTrackId == candidate.trackId,
                                        onClick = { selectedTrackId = candidate.trackId },
                                    )
                                    Column(modifier = Modifier.padding(start = 8.dp)) {
                                        Text(
                                            text = candidate.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        val subtitle = buildString {
                                            append(candidate.artist)
                                            candidate.album?.takeIf { it.isNotBlank() }?.let {
                                                if (isNotEmpty()) append(" • ")
                                                append(it)
                                            }
                                            if (candidate.hires) {
                                                if (isNotEmpty()) append(" • ")
                                                append("Hi-Res")
                                                candidate.bitDepth?.let { append(" ${it}bit") }
                                                candidate.samplingRateKhz?.let { append("/${formatKhz(it)}kHz") }
                                            }
                                        }
                                        if (subtitle.isNotBlank()) {
                                            Text(
                                                text = subtitle,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selectedTrackId != null && selectedTrackId != current?.qobuzTrackId,
                onClick = {
                    val candidate = candidates.firstOrNull { it.trackId == selectedTrackId }
                    val override = candidate?.let {
                        QobuzMatchOverride(
                            qobuzTrackId = it.trackId,
                            label = buildString {
                                append(it.title)
                                if (it.artist.isNotBlank()) append(" — ${it.artist}")
                            },
                            hires = it.hires,
                            bitDepth = it.bitDepth,
                            samplingRateKhz = it.samplingRateKhz,
                        )
                    }
                    if (override != null) {
                        service.setQobuzMatchOverride(mediaId, override)
                    }
                    onDismiss()
                },
            ) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            Row {
                if (current != null) {
                    TextButton(onClick = {
                        service.setQobuzMatchOverride(mediaId, null)
                        onDismiss()
                    }) {
                        Text(stringResource(R.string.qobuz_match_override_auto))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        },
    )
}

private fun formatKhz(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
