package com.meld.db

import androidx.room3.ColumnTypeConverter
import androidx.room3.ColumnTypeConverters
import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import kotlinx.serialization.json.Json

const val MELD_DATABASE_NAME = "meld.db"

/**
 * Meld-only data. Kept out of upstream's song.db because its legacy importer and backup validator reject
 * unknown tables, and every upstream schema bump would collide with ours.
 */
@Database(
    entities = [SpotifyMatchEntity::class],
    version = 1,
    exportSchema = true,
)
@ColumnTypeConverters(MeldConverters::class)
@ConstructedBy(MeldDatabaseConstructor::class)
abstract class MeldDatabase : RoomDatabase() {
    abstract fun spotifyMatchDao(): SpotifyMatchDao
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
