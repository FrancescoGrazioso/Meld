package com.meld.db

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * A change to the Spotify Liked Songs that Spotify has not confirmed yet: [liked] is what the song
 * [youtubeId] should be there. One row per song, so the latest intent replaces the one before it.
 * [notFound] parks a like whose song has no confident match on Spotify; it is only tried again on request.
 */
@Entity(tableName = "spotify_like_outbox")
data class SpotifyLikeOutboxEntity(
    @PrimaryKey val youtubeId: String,
    val liked: Boolean,
    val notFound: Boolean,
    val createdAt: Long,
)

/**
 * A track that was in the Spotify Liked Songs when Meld last looked, or that Meld put there. This is the
 * base of the three-way comparison: only a track listed here and gone from Spotify was removed there.
 * [pending] rows still have to be found on YouTube; after that [youtubeId] is the song liked for the
 * track, or null when it has no confident match. [addedAt] is when Spotify says the track was liked, in
 * epoch milliseconds; null on rows written before it was kept.
 */
@Entity(tableName = "spotify_liked_track", indices = [Index("youtubeId")])
data class SpotifyLikedTrackEntity(
    @PrimaryKey val spotifyId: String,
    val youtubeId: String?,
    val pending: Boolean,
    val addedAt: Long? = null,
)
