/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Caches the mapping between a Spotify track ID and the best-matching YouTube video ID.
 * This avoids repeated search API calls for the same track.
 * When [isManualOverride] is true the entry was explicitly chosen by the user
 * and must not be replaced by the automatic fuzzy matcher.
 *
 * The [youtubeId] index backs the reverse lookup (YouTube → Spotify) used during
 * likes sync and the track menus, which would otherwise full-scan the table.
 */
@Entity(tableName = "spotify_match", indices = [Index(value = ["youtubeId"])])
data class SpotifyMatchEntity(
    @PrimaryKey val spotifyId: String,
    val youtubeId: String,
    val title: String,
    val artist: String,
    val matchScore: Double,
    val cachedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0")
    val isManualOverride: Boolean = false,
)
