package com.meld.db

import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query

/**
 * The Qobuz track the pre-KMP app had found for a YouTube song, in the shape that app stored it. Nothing
 * reads these yet: they are carried over from an old install so that the Qobuz port finds them.
 */
@Entity(tableName = "qobuz_match")
data class QobuzMatchEntity(
    @PrimaryKey val youtubeId: String,
    val qobuzTrackId: String,
    val hires: Boolean,
    val bitDepth: Int?,
    val samplingRateKhz: Double?,
    val matchedAt: Long,
)

@Dao
interface QobuzMatchDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(matches: List<QobuzMatchEntity>)

    @Query("SELECT * FROM qobuz_match ORDER BY youtubeId")
    suspend fun all(): List<QobuzMatchEntity>
}
