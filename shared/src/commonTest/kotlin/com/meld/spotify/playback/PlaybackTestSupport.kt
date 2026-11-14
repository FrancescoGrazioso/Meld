package com.meld.spotify.playback

import com.meld.db.SpotifyMatchDao
import com.meld.db.SpotifyMatchEntity
import com.meld.spotify.SpotifyArtist
import com.meld.spotify.SpotifyTrack
import com.meld.spotify.match.MatchConfidence
import com.meld.spotify.match.SearchScope
import com.meld.spotify.match.SearchStep
import com.meld.spotify.match.TrackMatcher
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.MusicQueue
import com.metrolist.shared.playback.QUEUE_SOURCE_AUTO_MIX
import com.metrolist.shared.playback.isRecommendationGeneratedQueueSource
import kotlinx.coroutines.CompletableDeferred

internal fun track(
    n: Int,
    isrc: String? = null,
) = SpotifyTrack(
    id = "sp$n",
    uri = "spotify:track:sp$n",
    name = "Song $n",
    artists = listOf(SpotifyArtist("ar", "spotify:artist:ar", "Artist")),
    album = null,
    durationMs = 200_000,
    explicit = false,
    isrc = isrc,
)

internal fun tracks(range: IntRange) = range.map { track(it) }

internal fun ytSong(
    id: String,
    title: String,
) = SongItem(id, title, null, listOf(ArtistTiny(null, "Artist")), null, 200)

internal fun storedMatch(
    n: Int,
    youtubeId: String? = "yt$n",
    manual: Boolean = false,
    version: Int = TrackMatcher.VERSION,
    updatedAt: Long = 0,
) = SpotifyMatchEntity(
    spotifyId = "sp$n",
    youtubeId = youtubeId,
    score = if (youtubeId == null) 0.0 else 1.0,
    confidence = if (youtubeId == null) MatchConfidence.NONE else MatchConfidence.HIGH,
    isManualOverride = manual,
    matcherVersion = version,
    createdAt = updatedAt,
    updatedAt = updatedAt,
    title = "Song $n",
    artists = listOf("Artist"),
    album = null,
    durationMs = 200_000,
    explicit = null,
    isrc = null,
)

internal class FakeMatchDao : SpotifyMatchDao {
    val rows = mutableMapOf<String, SpotifyMatchEntity>()

    override suspend fun get(spotifyId: String) = rows[spotifyId]

    override suspend fun getChunk(spotifyIds: List<String>) = spotifyIds.mapNotNull(rows::get)

    override suspend fun getByYoutubeId(youtubeId: String) = rows.values.filter { it.youtubeId == youtubeId }

    override suspend fun insertIfAbsent(match: SpotifyMatchEntity): Long {
        if (match.spotifyId in rows) return -1
        rows[match.spotifyId] = match
        return 1
    }

    override suspend fun deleteAutomatic(spotifyId: String) {
        if (rows[spotifyId]?.isManualOverride == false) rows.remove(spotifyId)
    }

    override suspend fun delete(spotifyId: String) {
        rows.remove(spotifyId)
    }

    override suspend fun deleteAutomaticOlderThan(matcherVersion: Int) = 0
}

/** "Song N" by "Artist" is found as the song "ytN", unless the test says otherwise. */
internal class FakeYouTube {
    val missing = mutableSetOf<String>()
    val failing = mutableSetOf<String>()
    val unloadable = mutableSetOf<String>()
    val results = mutableMapOf<String, List<SongItem>>()
    val calls = mutableListOf<SearchStep>()
    val ungated = mutableSetOf<String>()
    var gate: CompletableDeferred<Unit>? = null
    var active = 0
    var maxActive = 0
    var metadataRequests = 0

    suspend fun search(step: SearchStep): List<SongItem> {
        calls += step
        val title = step.text.removePrefix("Artist ")
        active++
        maxActive = maxOf(maxActive, active)
        try {
            if (title !in ungated) gate?.await()
            if (title in failing) throw IllegalStateException("offline")
            if (step.scope != SearchScope.SONGS || title in missing) return emptyList()
            return results[title] ?: listOf(ytSong("yt${title.substringAfter(' ')}", title))
        } finally {
            active--
        }
    }

    fun songsById(ids: List<String>): List<SongItem> {
        metadataRequests++
        return ids.filterNot { it in unloadable }.map { ytSong(it, "Song ${it.removePrefix("yt")}") }
    }
}

/** The queue as the Android player drives it, recommendations included. */
internal class FakeTarget : SpotifyQueueTarget {
    val queue = MusicQueue()
    val titles = mutableListOf<String?>()

    // The app's default.
    var legacyShuffle = true

    override val queueState get() = queue.state

    override val shuffleEnabled get() = queue.shuffleEnabled

    override fun start(
        songs: List<SongItem>,
        title: String?,
        shuffle: Boolean,
    ) {
        titles += title
        queue.setQueue(songs)
        if (shuffle) queue.toggleShuffle(preserveHistory = false, legacyShuffle = legacyShuffle)
    }

    // As the player's enqueueSongs: in front of the first upcoming recommendation.
    override fun addToQueue(songs: List<SongItem>) {
        val items = queue.items.value
        val firstRecommendation = items.indices.firstOrNull { it > queue.currentIndex.value && items[it].isRecommendation }
        queue.addToQueue(songs, insertIndex = firstRecommendation)
    }

    override fun playNext(songs: List<SongItem>) = queue.playNext(songs)

    override fun reorder(entryKeys: List<Long>): Boolean {
        queue.reorder(queue.state.value.indicesOf(entryKeys))
        return true
    }

    /** The queue screen's shuffle button. */
    fun toggleShuffle() = queue.toggleShuffle(legacyShuffle = legacyShuffle)

    /** The player's continuation, which it holds back while [spotifySourcePending]; [force] adds it regardless. */
    fun continuation(
        ids: List<String>,
        force: Boolean = false,
    ): Boolean {
        if (!force && spotifySourcePending()) return false
        queue.addToQueue(ids.map { ytSong(it, it).copy(queueSourceTitle = QUEUE_SOURCE_AUTO_MIX) })
        return true
    }

    val ids get() = queue.items.value.map { it.id }

    /** True when no track still to play comes after a recommendation. */
    val recommendationsAreLast: Boolean
        get() =
            queue.items.value
                .drop(queue.currentIndex.value + 1)
                .dropWhile { !it.isRecommendation }
                .all { it.isRecommendation }
}

private val SongItem.isRecommendation get() = queueSourceTitle.isRecommendationGeneratedQueueSource()
