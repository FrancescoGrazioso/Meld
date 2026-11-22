package com.meld.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction

// SQLite before 3.32 (Android 10 and older) allows 999 bound variables per statement.
private const val MAX_IDS_PER_QUERY = 900

@Dao
interface SpotifyLikeDao {
    @Query("SELECT * FROM spotify_like_outbox WHERE youtubeId = :youtubeId")
    suspend fun outboxEntry(youtubeId: String): SpotifyLikeOutboxEntity?

    /** The oldest entry that can be sent. */
    @Query("SELECT * FROM spotify_like_outbox WHERE notFound = 0 ORDER BY createdAt, youtubeId LIMIT 1")
    suspend fun nextOutboxEntry(): SpotifyLikeOutboxEntity?

    @Query("SELECT youtubeId FROM spotify_like_outbox")
    suspend fun outboxIds(): List<String>

    @Query("SELECT COUNT(*) FROM spotify_like_outbox WHERE notFound = :notFound")
    suspend fun outboxCount(notFound: Boolean): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putOutboxEntry(entry: SpotifyLikeOutboxEntity)

    @Query("UPDATE spotify_like_outbox SET notFound = 1 WHERE youtubeId = :youtubeId")
    suspend fun markNotFound(youtubeId: String)

    @Query("UPDATE spotify_like_outbox SET notFound = 0")
    suspend fun retryNotFound()

    @Query("DELETE FROM spotify_like_outbox WHERE youtubeId = :youtubeId")
    suspend fun deleteOutboxEntry(youtubeId: String)

    @Query("SELECT * FROM spotify_liked_track")
    suspend fun likedTracks(): List<SpotifyLikedTrackEntity>

    @Query("SELECT spotifyId FROM spotify_liked_track")
    suspend fun likedTrackIds(): List<String>

    @Query("SELECT * FROM spotify_liked_track WHERE spotifyId = :spotifyId")
    suspend fun likedTrack(spotifyId: String): SpotifyLikedTrackEntity?

    @Query("SELECT * FROM spotify_liked_track WHERE youtubeId = :youtubeId")
    suspend fun likedTracksOf(youtubeId: String): List<SpotifyLikedTrackEntity>

    @Query("SELECT COUNT(*) FROM spotify_liked_track WHERE pending = 1")
    suspend fun pendingLikedTrackCount(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLikedTracks(tracks: List<SpotifyLikedTrackEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putLikedTracks(tracks: List<SpotifyLikedTrackEntity>)

    @Query("UPDATE spotify_liked_track SET addedAt = :addedAt WHERE spotifyId = :spotifyId")
    suspend fun setLikedTrackDate(
        spotifyId: String,
        addedAt: Long,
    )

    @Transaction
    suspend fun setLikedTrackDates(dates: Map<String, Long>) = dates.forEach { (spotifyId, addedAt) -> setLikedTrackDate(spotifyId, addedAt) }

    /** Lets the tracks that had no match be looked up again. */
    @Query("UPDATE spotify_liked_track SET pending = 1 WHERE youtubeId IS NULL")
    suspend fun retryUnmatchedLikedTracks()

    @Query("DELETE FROM spotify_liked_track WHERE spotifyId IN (:spotifyIds)")
    suspend fun deleteLikedTrackChunk(spotifyIds: List<String>)

    @Transaction
    suspend fun deleteLikedTracks(spotifyIds: Collection<String>) =
        spotifyIds.chunked(MAX_IDS_PER_QUERY).forEach { deleteLikedTrackChunk(it) }

    @Query("DELETE FROM spotify_like_outbox")
    suspend fun clearOutbox()

    @Query("DELETE FROM spotify_liked_track")
    suspend fun clearLikedTracks()

    /** Both tables belong to one Spotify account. */
    @Transaction
    suspend fun clear() {
        clearOutbox()
        clearLikedTracks()
    }
}
