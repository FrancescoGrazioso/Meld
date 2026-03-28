package com.metrolist.music.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.metrolist.music.db.entities.SoundCloudMatchEntity

/**
 * Partial DAO logic to merge into the existing `DatabaseDao.kt` or use as a separate module.
 * Assuming a separate interface that can be added to `MusicDatabase.kt`.
 */
@Dao
interface SoundCloudDao {

    @Query("SELECT * FROM soundcloud_match WHERE soundCloudId = :soundCloudId LIMIT 1")
    fun getSoundCloudMatch(soundCloudId: Long): SoundCloudMatchEntity?

    @Query("SELECT * FROM soundcloud_match WHERE youtubeId = :youtubeId LIMIT 1")
    fun getSoundCloudMatchByYouTubeId(youtubeId: String): SoundCloudMatchEntity?

    @Upsert
    fun upsertSoundCloudMatch(match: SoundCloudMatchEntity)

    @Query("DELETE FROM soundcloud_match WHERE cachedAt < :before")
    fun clearOldSoundCloudMatches(before: Long)

    @Query("DELETE FROM soundcloud_match")
    fun clearAllSoundCloudMatches()

    @Query("DELETE FROM soundcloud_match WHERE soundCloudId = :soundCloudId")
    fun deleteSoundCloudMatch(soundCloudId: Long)
}
