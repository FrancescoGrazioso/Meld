package com.meld.migration

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import com.meld.db.MeldDatabase
import com.meld.db.QobuzMatchEntity
import com.meld.db.SpotifyMatchEntity
import com.meld.spotify.match.MatchConfidence
import com.meld.spotify.match.TrackMatcher
import com.metrolist.shared.backup.checkpointBackupDatabase

// Every release of the pre-KMP app: 0.1.1 shipped schema 33 and 0.9.2, the last one, 41.
private val LEGACY_MELD_VERSIONS = 33..41

// Without Meld's two tables the old schema is upstream's own legacy one, whose last version was 38;
// upstream's importer reads anything from 36 to 39 the same way.
private const val UPSTREAM_LEGACY_VERSION = 38

// As the old app's schema 35 created it. Upstream's importer requires the table; schemas 33 and 34 predate it.
private const val PODCAST_TABLE =
    "CREATE TABLE IF NOT EXISTS `podcast` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `author` TEXT, `thumbnailUrl` TEXT, " +
        "`channelId` TEXT, `bookmarkedAt` INTEGER, `lastUpdateTime` INTEGER NOT NULL, `libraryAddToken` TEXT, " +
        "`libraryRemoveToken` TEXT, PRIMARY KEY(`id`))"

/** What was found in an old Meld database. Counts only, so it can be logged. */
data class LegacyMeldImport(
    val schemaVersion: Int,
    val manualMatches: Int,
    val automaticMatches: Int,
    val qobuzMatches: Int,
    val localSongs: Int,
    val songIsrcs: Int,
    val spotifyArtists: Int,
)

/**
 * Makes a database of the pre-KMP Meld readable by upstream's legacy importer, which rejects Meld's own
 * tables and version numbers. [legacy] must be a copy the importer is about to convert, never the user's
 * database: the manual Spotify matches and the Qobuz matches are first copied into [meld], then Meld's
 * tables are dropped and the version is set to upstream's legacy one.
 *
 * Automatic Spotify matches are not copied: the resolver only trusts rows made by the current matcher,
 * and stamping the old, looser matcher's choices as current would make its mistakes permanent.
 * `song.localPath`, `song.isrc` and `artist.spotifyId` have no home yet and are left for the importer to skip.
 *
 * Returns null, leaving [legacy] untouched, when it is not an old Meld database or is one this cannot
 * read; the importer then decides on its own. Safe to run again: copies never replace what is in [meld],
 * and a database already stripped is no longer recognised.
 */
suspend fun importLegacyMeldDatabase(
    legacy: SQLiteConnection,
    meld: MeldDatabase,
    log: (String) -> Unit = {},
): LegacyMeldImport? {
    // Only the old app had this table in song.db, and its column was renamed when the table moved to meld.db.
    val matchColumns = legacy.columns("spotify_match")
    if ("matchScore" !in matchColumns) return null
    val version = legacy.count("PRAGMA user_version")
    if (version !in LEGACY_MELD_VERSIONS) {
        log("Old Meld database has unknown schema version $version; left as it is")
        return null
    }

    val manual =
        if ("isManualOverride" in matchColumns) {
            legacy.rows(
                "SELECT spotifyId, youtubeId, title, artist, matchScore, cachedAt FROM spotify_match WHERE isManualOverride != 0",
            ) {
                SpotifyMatchEntity(
                    spotifyId = it.getText(0),
                    youtubeId = it.getText(1),
                    score = it.getDouble(4),
                    confidence = MatchConfidence.HIGH,
                    isManualOverride = true,
                    matcherVersion = TrackMatcher.VERSION,
                    createdAt = it.getLong(5),
                    updatedAt = it.getLong(5),
                    title = it.getText(2),
                    artists = listOfNotNull(it.getText(3).takeIf(String::isNotBlank)),
                    album = null,
                    durationMs = null,
                    explicit = null,
                    isrc = null,
                )
            }
        } else {
            emptyList()
        }
    val qobuz =
        if (legacy.columns("qobuz_match").isEmpty()) {
            emptyList()
        } else {
            legacy.rows("SELECT youtubeId, qobuzTrackId, hires, bitDepth, samplingRateKhz, matchedAt FROM qobuz_match") {
                QobuzMatchEntity(
                    youtubeId = it.getText(0),
                    qobuzTrackId = it.getText(1),
                    hires = it.getLong(2) != 0L,
                    bitDepth = if (it.isNull(3)) null else it.getLong(3).toInt(),
                    samplingRateKhz = if (it.isNull(4)) null else it.getDouble(4),
                    matchedAt = it.getLong(5),
                )
            }
        }
    val found =
        LegacyMeldImport(
            schemaVersion = version,
            manualMatches = manual.size,
            automaticMatches = legacy.count("SELECT COUNT(*) FROM spotify_match") - manual.size,
            qobuzMatches = qobuz.size,
            localSongs = legacy.countNotNull("song", "localPath"),
            songIsrcs = legacy.countNotNull("song", "isrc"),
            spotifyArtists = legacy.countNotNull("artist", "spotifyId"),
        )

    // Before anything is dropped: if this fails, the importer fails too and the user keeps the original.
    val matches = meld.spotifyMatchDao()
    manual.forEach { if (matches.get(it.spotifyId)?.isManualOverride != true) matches.saveManualOverride(it) }
    meld.qobuzMatchDao().insertIfAbsent(qobuz)

    legacy.execSQL("BEGIN IMMEDIATE TRANSACTION")
    try {
        legacy.execSQL("DROP TABLE spotify_match")
        legacy.execSQL("DROP TABLE IF EXISTS qobuz_match")
        legacy.execSQL(PODCAST_TABLE)
        legacy.execSQL("PRAGMA user_version = $UPSTREAM_LEGACY_VERSION")
        legacy.execSQL("COMMIT")
    } catch (error: Throwable) {
        runCatching { legacy.execSQL("ROLLBACK") }.onFailure(error::addSuppressed)
        throw error
    }
    // The importer replaces this file with its result, so nothing may be left behind in a write-ahead log.
    checkpointBackupDatabase(legacy)
    log(
        "Old Meld database (schema $version) prepared for import: kept ${found.manualMatches} manual Spotify matches and " +
            "${found.qobuzMatches} Qobuz matches; dropped ${found.automaticMatches} automatic Spotify matches, " +
            "${found.localSongs} local file paths, ${found.songIsrcs} song ISRCs and ${found.spotifyArtists} Spotify artist ids",
    )
    return found
}

/** Empty when the table does not exist. */
private fun SQLiteConnection.columns(table: String): Set<String> = rows("PRAGMA table_info(`$table`)") { it.getText(1) }.toSet()

private fun SQLiteConnection.count(sql: String): Int = rows(sql) { it.getLong(0).toInt() }.single()

private fun SQLiteConnection.countNotNull(
    table: String,
    column: String,
): Int = if (column in columns(table)) count("SELECT COUNT(*) FROM `$table` WHERE `$column` IS NOT NULL") else 0

private fun <T> SQLiteConnection.rows(
    sql: String,
    read: (SQLiteStatement) -> T,
): List<T> = prepare(sql).use { statement -> buildList { while (statement.step()) add(read(statement)) } }
