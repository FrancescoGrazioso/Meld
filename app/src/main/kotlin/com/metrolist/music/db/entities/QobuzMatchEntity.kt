/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "qobuz_match")
data class QobuzMatchEntity(
    @PrimaryKey val youtubeId: String,
    val qobuzTrackId: String,
    @ColumnInfo(defaultValue = "0")
    val hires: Boolean = false,
    @ColumnInfo(name = "bitDepth", defaultValue = "NULL")
    val bitDepth: Int? = null,
    @ColumnInfo(name = "samplingRateKhz", defaultValue = "NULL")
    val samplingRateKhz: Double? = null,
    val matchedAt: Long = System.currentTimeMillis(),
)
