package com.meld.db

import androidx.room3.ColumnTypeConverter
import androidx.room3.ColumnTypeConverters
import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import kotlinx.serialization.json.Json

const val MELD_DATABASE_NAME = "meld.db"

/**
 * Meld-only data. Kept out of upstream's song.db because its legacy importer and backup validator reject
 * unknown tables, and every upstream schema bump would collide with ours.
 */
@Database(
    entities = [SpotifyMatchEntity::class, SpotifyLikeOutboxEntity::class, SpotifyLikedTrackEntity::class, QobuzMatchEntity::class],
    version = 5,
    exportSchema = true,
)
@ColumnTypeConverters(MeldConverters::class)
@ConstructedBy(MeldDatabaseConstructor::class)
abstract class MeldDatabase : RoomDatabase() {
    abstract fun spotifyMatchDao(): SpotifyMatchDao

    abstract fun spotifyLikeDao(): SpotifyLikeDao

    abstract fun qobuzMatchDao(): QobuzMatchDao
}

@Suppress("KotlinNoActualForExpect")
expect object MeldDatabaseConstructor : RoomDatabaseConstructor<MeldDatabase> {
    override fun initialize(): MeldDatabase
}

class MeldConverters {
    @ColumnTypeConverter
    fun stringsToJson(value: List<String>): String = Json.encodeToString(value)

    @ColumnTypeConverter
    fun jsonToStrings(value: String): List<String> = Json.decodeFromString(value)
}

val MELD_MIGRATION_1_2 =
    object : Migration(1, 2) {
        override suspend fun migrate(connection: SQLiteConnection) {
            listOf(
                "CREATE TABLE IF NOT EXISTS `spotify_like_outbox` (`youtubeId` TEXT NOT NULL, `liked` INTEGER NOT NULL, " +
                    "`notFound` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`youtubeId`))",
                "CREATE TABLE IF NOT EXISTS `spotify_liked_track` (`spotifyId` TEXT NOT NULL, `youtubeId` TEXT, " +
                    "`pending` INTEGER NOT NULL, PRIMARY KEY(`spotifyId`))",
                "CREATE INDEX IF NOT EXISTS `index_spotify_liked_track_youtubeId` ON `spotify_liked_track` (`youtubeId`)",
            ).forEach { connection.execSQL(it) }
        }
    }

val MELD_MIGRATION_2_3 =
    object : Migration(2, 3) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `qobuz_match` (`youtubeId` TEXT NOT NULL, `qobuzTrackId` TEXT NOT NULL, " +
                    "`hires` INTEGER NOT NULL, `bitDepth` INTEGER, `samplingRateKhz` REAL, `matchedAt` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`youtubeId`))",
            )
        }
    }

val MELD_MIGRATION_3_4 =
    object : Migration(3, 4) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE `spotify_liked_track` ADD COLUMN `addedAt` INTEGER")
        }
    }

val MELD_MIGRATION_4_5 =
    object : Migration(4, 5) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE `spotify_liked_track` ADD COLUMN `origin` INTEGER NOT NULL DEFAULT 0")
        }
    }

/** The database every platform opens: the platform's builder plus Meld's migrations. */
fun buildMeldDatabase(context: Any? = null): MeldDatabase =
    getMeldDatabaseBuilder(context).addMigrations(MELD_MIGRATION_1_2, MELD_MIGRATION_2_3, MELD_MIGRATION_3_4, MELD_MIGRATION_4_5).build()
