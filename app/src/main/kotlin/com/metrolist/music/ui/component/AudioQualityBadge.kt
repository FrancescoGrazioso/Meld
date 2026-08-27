/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.music.db.entities.FormatEntity

@Composable
fun AudioQualityBadge(
    format: FormatEntity?,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    if (format == null) return

    val badgeData = remember(format) {
        val mime = format.mimeType.lowercase()
        val codecs = format.codecs.lowercase()
        val itag = format.itag
        val isFlac = mime.contains("flac") || codecs.contains("flac") || itag in listOf(9924, 9916)
        val isOpus = mime.contains("opus") || codecs.contains("opus")
        val isAac = mime.contains("mp4") || mime.contains("m4a") || codecs.contains("mp4a") || mime.contains("aac")

        val sampleRateKhz = format.sampleRate?.let { if (it > 0) it / 1000.0 else null }
        val sampleRateStr = if (sampleRateKhz != null) {
            if (sampleRateKhz % 1.0 == 0.0) "${sampleRateKhz.toInt()}kHz" else "${sampleRateKhz}kHz"
        } else "96kHz"

        val bitDepth = if (itag == 9924 || (format.sampleRate ?: 0) >= 48000) 24 else if (isFlac) 16 else null
        val isHiRes = isFlac && bitDepth == 24

        val text = when {
            isHiRes -> "24-bit / $sampleRateStr FLAC"
            isFlac -> "${bitDepth ?: 16}-bit / $sampleRateStr FLAC"
            isOpus -> {
                val kbps = (format.bitrate / 1000).coerceAtLeast(1)
                "${kbps}kbps Opus"
            }
            isAac -> {
                val kbps = (format.bitrate / 1000).coerceAtLeast(1)
                "${kbps}kbps AAC"
            }
            else -> {
                val kbps = (format.bitrate / 1000).coerceAtLeast(1)
                "${kbps}kbps ${format.mimeType.substringAfter("/").uppercase()}"
            }
        }

        Triple(text, isFlac, isHiRes)
    }

    val (text, isLossless, isHiRes) = badgeData

    val badgeBg = when {
        isHiRes -> Color(0xFFE5A00D).copy(alpha = 0.22f)
        isLossless -> Color(0xFF00C853).copy(alpha = 0.20f)
        else -> textColor.copy(alpha = 0.12f)
    }

    val borderColor = when {
        isHiRes -> Color(0xFFE5A00D).copy(alpha = 0.55f)
        isLossless -> Color(0xFF00C853).copy(alpha = 0.50f)
        else -> textColor.copy(alpha = 0.25f)
    }

    val dotColor = when {
        isHiRes -> Color(0xFFFFD54F)
        isLossless -> Color(0xFF69F0AE)
        else -> textColor.copy(alpha = 0.70f)
    }

    val badgeTextColor = when {
        isHiRes -> Color(0xFFFFE082)
        isLossless -> Color(0xFFA7F3D0)
        else -> textColor.copy(alpha = 0.90f)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(badgeBg)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .padding(horizontal = 7.dp, vertical = 2.5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 10.5.sp,
                letterSpacing = 0.2.sp
            ),
            color = badgeTextColor
        )
    }
}
