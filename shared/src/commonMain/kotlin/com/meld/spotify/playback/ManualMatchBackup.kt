package com.meld.spotify.playback

import com.meld.db.SpotifyMatchDao
import com.meld.db.SpotifyMatchEntity
import com.meld.spotify.match.MatchConfidence
import com.meld.spotify.match.TrackMatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One "this track plays that song" the user chose, as it travels in a backup. */
@Serializable
internal data class ManualMatch(
    val spotifyId: String,
    val youtubeId: String,
    val title: String = "",
    val artists: List<String> = emptyList(),
    val album: String? = null,
    val durationMs: Long? = null,
)

/**
 * Keeps the user's own choices of a YouTube version in the app's settings as well as in `meld.db`.
 *
 * Upstream's backup carries the settings, on every platform and with its own staging and rollback; it does
 * not know `meld.db`. Everything else in that database rebuilds itself after a restore, a choice does not.
 * [load] and [save] are the setting. The database stays the truth while the app runs: the setting is
 * rewritten after every change, and read only at start, to bring in what a restore put there.
 */
class ManualMatchBackup(
    private val dao: SpotifyMatchDao,
    private val load: () -> String,
    private val save: (String) -> Unit,
    private val now: () -> Long,
) {
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    /** At start: choices the setting holds and the database does not are stored, then the setting is brought up to date. */
    suspend fun restore() =
        lock.withLock {
            val known = dao.manualOverrides().mapTo(hashSetOf()) { it.spotifyId }
            read().filter { it.spotifyId !in known }.forEach { match ->
                val time = now()
                dao.saveManualOverride(
                    SpotifyMatchEntity(
                        spotifyId = match.spotifyId,
                        youtubeId = match.youtubeId,
                        score = 1.0,
                        confidence = MatchConfidence.HIGH,
                        isManualOverride = true,
                        matcherVersion = TrackMatcher.VERSION,
                        createdAt = time,
                        updatedAt = time,
                        title = match.title,
                        artists = match.artists,
                        album = match.album,
                        durationMs = match.durationMs,
                        explicit = null,
                        isrc = null,
                    ),
                )
            }
            write()
        }

    /** After the user made or dropped a choice. */
    suspend fun changed() = lock.withLock { write() }

    private fun read(): List<ManualMatch> =
        runCatching { json.decodeFromString<List<ManualMatch>>(load().ifBlank { "[]" }) }.getOrDefault(emptyList())

    private suspend fun write() {
        val matches =
            dao.manualOverrides().mapNotNull { row ->
                row.youtubeId?.let { ManualMatch(row.spotifyId, it, row.title, row.artists, row.album, row.durationMs) }
            }
        val text = if (matches.isEmpty()) "" else json.encodeToString(matches.sortedBy { it.spotifyId })
        if (text != load()) save(text)
    }
}
