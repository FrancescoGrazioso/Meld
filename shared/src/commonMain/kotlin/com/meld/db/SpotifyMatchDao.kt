package com.meld.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction

// SQLite before 3.32 (Android 10 and older) allows 999 bound variables per statement.
private const val MAX_IDS_PER_QUERY = 900

/**
 * There is deliberately no plain upsert: every write goes through a method that cannot replace a manual
 * override unless it is itself a manual override.
 */
@Dao
interface SpotifyMatchDao {
    @Query("SELECT * FROM spotify_match WHERE spotifyId = :spotifyId")
    suspend fun get(spotifyId: String): SpotifyMatchEntity?

    @Query("SELECT * FROM spotify_match WHERE spotifyId IN (:spotifyIds)")
    suspend fun getChunk(spotifyIds: List<String>): List<SpotifyMatchEntity>

    @Transaction
    suspend fun getAll(spotifyIds: Collection<String>): List<SpotifyMatchEntity> =
        spotifyIds.distinct().chunked(MAX_IDS_PER_QUERY).flatMap { getChunk(it) }

    /** Several Spotify tracks (single, album, deluxe) can share one video; the most trustworthy comes first. */
    @Query("SELECT * FROM spotify_match WHERE youtubeId = :youtubeId ORDER BY isManualOverride DESC, score DESC, spotifyId")
    suspend fun getByYoutubeId(youtubeId: String): List<SpotifyMatchEntity>

    /** For reverse (YouTube to Spotify) lookups: records the pair only when the track has no row yet. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(match: SpotifyMatchEntity): Long

    @Query("DELETE FROM spotify_match WHERE spotifyId = :spotifyId AND isManualOverride = 0")
    suspend fun deleteAutomatic(spotifyId: String)

    /** Stores the matcher's result. Returns false, changing nothing, when the track has a manual override. */
    @Transaction
    suspend fun saveAutomatic(match: SpotifyMatchEntity): Boolean {
        val existing = get(match.spotifyId)
        if (existing?.isManualOverride == true) return false
        deleteAutomatic(match.spotifyId)
        insertIfAbsent(match.copy(isManualOverride = false, createdAt = existing?.createdAt ?: match.createdAt))
        return true
    }

    @Transaction
    suspend fun saveManualOverride(match: SpotifyMatchEntity) {
        val existing = get(match.spotifyId)
        delete(match.spotifyId)
        insertIfAbsent(match.copy(isManualOverride = true, createdAt = existing?.createdAt ?: match.createdAt))
    }

    /** Removes the row whatever it is; this is how the user goes back from an override to automatic matching. */
    @Query("DELETE FROM spotify_match WHERE spotifyId = :spotifyId")
    suspend fun delete(spotifyId: String)

    @Query("DELETE FROM spotify_match WHERE isManualOverride = 0 AND matcherVersion < :matcherVersion")
    suspend fun deleteAutomaticOlderThan(matcherVersion: Int): Int
}
