package com.meld.db

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.meld.spotify.match.MatchConfidence
import com.meld.spotify.match.MatchQuery

/**
 * The YouTube video chosen for a Spotify track. A durable record, not a cache: rows only go away when the
 * matcher version moves on or the user resets them, and a manual override never does.
 * [youtubeId] is null when the search found nothing acceptable ([confidence] NONE), so "no match" is
 * remembered and callers can tell it from "never tried". [title] to [isrc] are the Spotify metadata the
 * match was made from, enough to match again without asking Spotify.
 */
@Entity(tableName = "spotify_match", indices = [Index("youtubeId")])
data class SpotifyMatchEntity(
    @PrimaryKey val spotifyId: String,
    val youtubeId: String?,
    val score: Double,
    val confidence: MatchConfidence,
    val isManualOverride: Boolean,
    val matcherVersion: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val title: String,
    val artists: List<String>,
    val album: String?,
    val durationMs: Long?,
    val explicit: Boolean?,
    val isrc: String?,
)

fun SpotifyMatchEntity.toMatchQuery(): MatchQuery = MatchQuery(title, artists, album, durationMs, explicit, isrc)
