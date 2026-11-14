package com.meld.spotify.playback

import com.meld.db.SpotifyMatchDao
import com.meld.db.SpotifyMatchEntity
import com.meld.spotify.SpotifyTrack
import com.meld.spotify.match.MatchConfidence
import com.meld.spotify.match.MatchQuery
import com.meld.spotify.match.ScoredCandidate
import com.meld.spotify.match.SearchStep
import com.meld.spotify.match.TrackMatcher
import com.meld.spotify.match.buildSearchPlan
import com.meld.spotify.match.toMatchCandidate
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

sealed interface SpotifyResolution {
    data class Matched(
        val song: SongItem,
        val lowConfidence: Boolean,
    ) : SpotifyResolution

    data object NoMatch : SpotifyResolution

    /** YouTube could not be asked. Nothing was stored, so the next attempt asks again. */
    data class Failed(
        val cause: Throwable,
    ) : SpotifyResolution
}

/**
 * Finds the YouTube song for a Spotify track: stored match first, then YouTube search scored by
 * [TrackMatcher]. One instance must serve every caller, because it holds the only limit on
 * concurrent YouTube searches.
 *
 * [search] must throw when YouTube cannot be reached, and must not record search history.
 * [songsById] returns the songs it could load and leaves out the others.
 * [scope] runs the searches, so a search shared by two callers survives one of them cancelling.
 */
class SpotifyTrackResolver(
    private val dao: SpotifyMatchDao,
    private val search: suspend (SearchStep) -> List<SongItem>,
    private val songsById: suspend (List<String>) -> List<SongItem>,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    // Whether YouTube Music resolves an ISRC typed as search text is unmeasured.
    private val includeIsrc: Boolean = false,
    maxConcurrentSearches: Int = 3,
) {
    private class Flight(
        val work: Deferred<SpotifyResolution>,
        var waiters: Int = 0,
    )

    private val searches = Semaphore(maxConcurrentSearches)
    private val flights = mutableMapOf<String, Flight>()
    private val flightsLock = Mutex()

    suspend fun resolve(track: SpotifyTrack): SpotifyResolution = resolveAll(listOf(track)).single()

    /** Results are in the order of [tracks]. Stored matches cost one query and one YouTube request in total. */
    suspend fun resolveAll(tracks: List<SpotifyTrack>): List<SpotifyResolution> =
        coroutineScope {
            val rows =
                dao
                    .getAll(tracks.map { it.id })
                    .filter { it.isManualOverride || it.matcherVersion == TrackMatcher.VERSION }
                    .associateBy { it.spotifyId }
            val songs = loadSongs(rows.values.mapNotNull { it.youtubeId })
            tracks
                .map { track ->
                    val stored = rows[track.id]?.let { fromStored(it, songs) }
                    async { stored ?: searchShared(track) }
                }.awaitAll()
        }

    /** Null when the row cannot answer and YouTube has to be searched. */
    private fun fromStored(
        row: SpotifyMatchEntity,
        songs: Map<String, SongItem>,
    ): SpotifyResolution? {
        val song = row.youtubeId?.let(songs::get)
        return when {
            song != null -> SpotifyResolution.Matched(song, row.confidence == MatchConfidence.LOW)

            row.youtubeId == null && (row.isManualOverride || now() - row.updatedAt < NO_MATCH_TTL_MS) -> SpotifyResolution.NoMatch

            // The user's choice is never replaced by a search, even while its video cannot be loaded.
            row.isManualOverride -> SpotifyResolution.Failed(IllegalStateException("Override video unavailable"))

            else -> null
        }
    }

    private suspend fun loadSongs(ids: List<String>): Map<String, SongItem> {
        if (ids.isEmpty()) return emptyMap()
        return try {
            songsById(ids.distinct()).filterNot { it.isUnavailable }.associateBy { it.id }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** Callers asking for the same track share one search; it is cancelled when the last of them leaves. */
    private suspend fun searchShared(track: SpotifyTrack): SpotifyResolution {
        val flight =
            flightsLock.withLock {
                flights.getOrPut(track.id) { Flight(scope.async { searchAndStore(track) }) }.also { it.waiters++ }
            }
        try {
            return flight.work.await()
        } finally {
            withContext(NonCancellable) {
                flightsLock.withLock {
                    if (--flight.waiters == 0) {
                        flight.work.cancel()
                        flights.remove(track.id)
                    }
                }
            }
        }
    }

    private suspend fun searchAndStore(track: SpotifyTrack): SpotifyResolution {
        val query = track.toMatchQuery()
        val best =
            try {
                findBest(query)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return SpotifyResolution.Failed(e)
            }
        val time = now()
        dao.saveAutomatic(
            SpotifyMatchEntity(
                spotifyId = track.id,
                youtubeId = best?.first?.id,
                score = best?.second?.score?.total ?: 0.0,
                confidence = best?.second?.score?.confidence ?: MatchConfidence.NONE,
                isManualOverride = false,
                matcherVersion = TrackMatcher.VERSION,
                createdAt = time,
                updatedAt = time,
                title = query.title,
                artists = query.artists,
                album = query.album,
                durationMs = query.durationMs,
                explicit = query.explicit,
                isrc = query.isrc,
            ),
        )
        return best?.let { (song, scored) -> SpotifyResolution.Matched(song, scored.score.confidence == MatchConfidence.LOW) }
            ?: SpotifyResolution.NoMatch
    }

    private suspend fun findBest(query: MatchQuery): Pair<SongItem, ScoredCandidate>? {
        for (step in buildSearchPlan(query, includeIsrc)) {
            val songs = searchWithRetry(step).filterNot { it.isUnavailable }
            val best = TrackMatcher.best(query, songs.map { it.toMatchCandidate() }) ?: continue
            return songs.first { it.id == best.candidate.id } to best
        }
        return null
    }

    private suspend fun searchWithRetry(step: SearchStep): List<SongItem> =
        try {
            searches.withPermit { search(step) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            delay(RETRY_DELAY)
            searches.withPermit { search(step) }
        }

    private companion object {
        // A song missing from YouTube today may be uploaded later.
        val NO_MATCH_TTL_MS = 7.days.inWholeMilliseconds
        val RETRY_DELAY = 1.seconds
    }
}

internal fun SpotifyTrack.toMatchQuery(): MatchQuery =
    MatchQuery(
        title = name,
        artists = artists.map { it.name },
        album = album?.name,
        durationMs = durationMs.takeIf { it > 0 },
        // The parsers turn a missing rating into false, so only "explicit" is something Spotify said.
        explicit = true.takeIf { explicit },
        isrc = isrc,
    )
