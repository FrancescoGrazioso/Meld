package com.meld.spotify.likes

import com.meld.db.SpotifyLikeDao
import com.meld.db.SpotifyLikeOutboxEntity
import com.meld.db.SpotifyLikedTrackEntity
import com.meld.db.SpotifyMatchDao
import com.meld.db.SpotifyMatchEntity
import com.meld.spotify.SpotifyAlbum
import com.meld.spotify.SpotifyArtist
import com.meld.spotify.SpotifyConnection
import com.meld.spotify.SpotifyConnectionState
import com.meld.spotify.SpotifyError
import com.meld.spotify.SpotifyTrack
import com.meld.spotify.match.CandidateKind
import com.meld.spotify.match.MatchCandidate
import com.meld.spotify.match.MatchConfidence
import com.meld.spotify.match.MatchQuery
import com.meld.spotify.match.TrackMatcher
import com.meld.spotify.match.buildSearchPlan
import com.meld.spotify.playback.SpotifyResolution
import com.meld.spotify.playback.SpotifyTrackResolver
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** What the last "sync now" came to. */
sealed interface SpotifyLikeSyncResult {
    /** Every direction that is switched on went through; [sent] likes and un-likes reached Spotify. */
    data class Done(
        val sent: Int,
    ) : SpotifyLikeSyncResult

    data class Failed(
        val error: SpotifyError,
    ) : SpotifyLikeSyncResult
}

/**
 * [importTotal] is 0 unless Spotify likes are being looked up on YouTube. [syncedAt] is when every direction
 * that is switched on last went through, 0 before the first time. [result] is null until "sync now" has run
 * and while it runs.
 */
data class SpotifyLikeSyncStatus(
    val pending: Int = 0,
    val notFound: Int = 0,
    val syncedAt: Long = 0,
    val syncing: Boolean = false,
    val importDone: Int = 0,
    val importTotal: Int = 0,
    val result: SpotifyLikeSyncResult? = null,
)

/**
 * Keeps the app's liked songs and the Spotify Liked Songs of the connected account in step.
 *
 * To Spotify: every like or un-like the user makes is written to the outbox table first and leaves it
 * only when Spotify has confirmed it, so being offline, rate limited, signed out or killed loses nothing.
 *
 * From Spotify: a three-way comparison against the tracks remembered from the last look. A track new
 * on Spotify is liked here, a remembered track gone from Spotify is un-liked here, and everything else
 * is left alone. So a song is never un-liked because Spotify does not list it, only because it stopped
 * listing it, and only after a complete read of the list.
 *
 * Both tables belong to one account: they are emptied on sign-out and when another account signs in.
 */
class SpotifyLikeSync(
    private val dao: SpotifyLikeDao,
    private val matches: SpotifyMatchDao,
    private val remote: SpotifyLikesRemote,
    private val resolver: SpotifyTrackResolver,
    private val local: LocalLikes,
    private val connection: SpotifyConnection,
    private val settings: () -> Settings,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val onSpotifyChanged: suspend (added: List<SpotifyTrack>, removedIds: Set<String>) -> Unit = { _, _ -> },
    private val logger: (String) -> Unit = {},
) {
    // Held for one outbox entry at a time, so a new intent never waits for more than one request.
    private val outboxLock = Mutex()
    private val pullLock = Mutex()
    private val manualLock = Mutex()
    private val intents = Channel<Pair<String, Boolean>>(Channel.UNLIMITED)
    private val flushRequests = Channel<Unit>(Channel.CONFLATED)
    private val mutableStatus = MutableStateFlow(SpotifyLikeSyncStatus())
    private val mutableLikedIds = MutableStateFlow(emptySet<String>())

    // The songs being un-liked because Spotify said so. Upstream reports those un-likes back as votes; taken as the
    // user's, one could wait in the outbox and later remove a track the user has liked again on Spotify.
    @Volatile
    private var removing = emptySet<String>()

    // The likes and un-likes Spotify has taken since the app started.
    @Volatile
    private var sent = 0

    val status: StateFlow<SpotifyLikeSyncStatus> = mutableStatus.asStateFlow()

    /** The Spotify ids of the tracks known to be in the Liked Songs. */
    val likedIds: StateFlow<Set<String>> = mutableLikedIds.asStateFlow()

    /** Everything started here is idle between events: suspended on a channel, a flow or a timer. */
    fun start() {
        LocalVotes.listener = { id, liked -> if (liked || id !in removing) intents.trySend(id to liked) }
        scope.launch {
            for ((id, liked) in intents) {
                guarded { record(id, liked) }
                flushRequests.trySend(Unit)
            }
        }
        scope.launch { flushWithRetries() }
        scope.launch { connection.state.collectLatest { state -> guarded { follow(state) } } }
    }

    /** The user's "sync now", also called when a switch is turned on. */
    fun syncNow() {
        scope.launch { guarded { sync() } }
    }

    /**
     * Runs the directions that are switched on, and no other. To Spotify: the songs it did not have are looked
     * for again and everything waiting is sent. From Spotify: the Liked Songs are read again. The status is
     * [SpotifyLikeSyncStatus.syncing] meanwhile and holds the outcome afterwards.
     */
    internal suspend fun sync() {
        if (!manualLock.tryLock()) return
        var result: SpotifyLikeSyncResult? = null
        mutableStatus.update { it.copy(syncing = true, result = null) }
        try {
            val sentBefore = sent
            val push = canPush()
            val pull = canPull()
            var error: SpotifyError? = null
            if (push) {
                dao.retryNotFound()
                error = flush()
                // What is left is the background sender's, with its waits between attempts.
                if (error != null) flushRequests.trySend(Unit)
            }
            if (pull) pull(force = true) { error = error ?: it }
            if (!push && !pull) return
            val failure = error
            if (failure == null) settings().meldSpotifyLikesSyncedAt = now()
            result = failure?.let(SpotifyLikeSyncResult::Failed) ?: SpotifyLikeSyncResult.Done(sent - sentBefore)
        } finally {
            manualLock.unlock()
            val syncedAt = settings().meldSpotifyLikesSyncedAt
            mutableStatus.update { it.copy(syncing = false, result = result, syncedAt = syncedAt) }
        }
    }

    private suspend fun follow(state: SpotifyConnectionState) {
        if (!adopt(state)) {
            if (state is SpotifyConnectionState.Connected) awaitProfile()
            return
        }
        flushRequests.trySend(Unit)
        while (true) {
            guarded { pull() }
            delay(PULL_INTERVAL)
        }
    }

    /**
     * Makes the stored data that of the account in [state]: what belonged to another account, or to the one
     * that signed out, is dropped first. Likes in the app are never touched. Returns true when there is an
     * account to sync with.
     */
    internal suspend fun adopt(state: SpotifyConnectionState): Boolean {
        val prefs = settings()
        val id = (state as? SpotifyConnectionState.Connected)?.id
        val leaves = state == SpotifyConnectionState.Disconnected || (id != null && id != prefs.meldSpotifyLikesAccount)
        if (leaves && prefs.meldSpotifyLikesAccount.isNotBlank()) forgetAccount()
        if (id != null) prefs.meldSpotifyLikesAccount = id
        // Someone who never connected Spotify does not even open the database.
        if (prefs.meldSpotifyLikesAccount.isNotBlank()) publish()
        return id != null
    }

    /** A session from before the id was stored, or a profile that could not be read: asked for until the state changes. */
    private suspend fun awaitProfile(): Nothing {
        while (true) {
            connection.refresh()
            delay(PROFILE_RETRY)
        }
    }

    private suspend fun forgetAccount() {
        outboxLock.withLock { dao.clear() }
        settings().run {
            meldSpotifyLikesAccount = ""
            meldSpotifyLikesFingerprint = ""
            meldSpotifyLikesSyncedAt = 0
        }
        mutableStatus.update { it.copy(result = null) }
        publish()
    }

    /** The connected account, once this class has taken it over; null while its data may still be someone else's. */
    private fun activeAccount(): String? =
        (connection.state.value as? SpotifyConnectionState.Connected)?.id?.takeIf { it == settings().meldSpotifyLikesAccount }

    private fun canPush() = settings().meldSpotifyLikesPush && activeAccount() != null

    private fun canPull() = settings().meldSpotifyLikesPull && activeAccount() != null

    /**
     * Notes that [youtubeId] should be liked, or not, on Spotify. Recorded while the session is expired
     * too, as the same account may sign in again; not without an account.
     */
    internal suspend fun record(
        youtubeId: String,
        liked: Boolean,
    ) {
        val prefs = settings()
        if (!prefs.meldSpotifyLikesPush || prefs.meldSpotifyLikesAccount.isBlank()) return
        outboxLock.withLock {
            val existing = dao.outboxEntry(youtubeId)
            if (existing?.liked == true && !liked && likedTargets(youtubeId).isEmpty()) {
                // Liked and un-liked before anything was sent, and Spotify does not have it: nothing to send.
                dao.deleteOutboxEntry(youtubeId)
            } else if (existing == null || existing.liked != liked || existing.notFound) {
                dao.putOutboxEntry(SpotifyLikeOutboxEntity(youtubeId, liked, notFound = false, createdAt = now()))
            }
        }
        publish()
    }

    /** Sends what can be sent. Returns the error that stopped it; the entry it stopped at, and those after it, stay. */
    internal suspend fun flush(): SpotifyError? {
        val error =
            try {
                while (outboxLock.withLock { sendNext() }) publish()
                null
            } catch (error: SpotifyError) {
                logger("Liked songs not sent: ${error.message}")
                error
            }
        publish()
        return error
    }

    private suspend fun sendNext(): Boolean {
        if (!canPush()) return false
        val entry = dao.nextOutboxEntry() ?: return false
        val youtubeId = entry.youtubeId
        if (entry.liked) {
            val known = matches.getByYoutubeId(youtubeId).filter { it.isTrusted }
            val alreadyLiked = dao.likedTracksOf(youtubeId).isNotEmpty() || known.any { dao.likedTrack(it.spotifyId) != null }
            // A song no longer liked here has nothing to send: its un-like replaced this entry or was never the user's.
            val song = local.song(youtubeId)?.takeUnless { it.isLocal }
            if (song != null && !alreadyLiked) {
                val track = known.firstOrNull()?.toTrack() ?: findOnSpotify(song)
                if (track == null) {
                    dao.markNotFound(youtubeId)
                    return true
                }
                remote.addToLibrary(listOf(track.uri))
                sent++
                dao.putLikedTracks(listOf(SpotifyLikedTrackEntity(track.id, youtubeId, pending = false)))
                onSpotifyChanged(listOf(track), emptySet())
            }
        } else {
            val targets = likedTargets(youtubeId)
            if (targets.isNotEmpty()) {
                remote.removeFromLibrary(targets.map { TRACK_URI + it })
                sent++
                dao.deleteLikedTracks(targets)
                onSpotifyChanged(emptyList(), targets.toSet())
            }
        }
        dao.deleteOutboxEntry(youtubeId)
        return true
    }

    /**
     * The Spotify tracks liked for [youtubeId], as far as Meld knows. Single, album and deluxe releases can
     * share one video, so there may be several; an un-like removes them all, or the next read would like the song again.
     */
    private suspend fun likedTargets(youtubeId: String): List<String> {
        val matched = matches.getByYoutubeId(youtubeId).filter { it.isTrusted && dao.likedTrack(it.spotifyId) != null }
        return (dao.likedTracksOf(youtubeId).map { it.spotifyId } + matched.map { it.spotifyId }).distinct()
    }

    /**
     * The Spotify track [song] stands for, for anything that writes it to the user's Spotify account: the match
     * already trusted for it, else a search. Null when Spotify has no confident match.
     */
    suspend fun spotifyTrackFor(song: SongItem): SpotifyTrack? =
        matches.getByYoutubeId(song.id).firstOrNull { it.isTrusted }?.toTrack() ?: findOnSpotify(song)

    /**
     * The matcher run the other way round: the YouTube song is the query and Spotify's search results are
     * the candidates. Only a HIGH match is accepted, because a wrong one lands in the user's Spotify library.
     */
    private suspend fun findOnSpotify(song: SongItem): SpotifyTrack? {
        val query =
            MatchQuery(
                title = song.title,
                artists = song.artists.map { it.name },
                album = song.album?.name,
                durationMs = song.duration?.times(1000),
                explicit = true.takeIf { song.isExplicit },
            )
        val text = buildSearchPlan(query, includeIsrc = false).firstOrNull()?.text ?: return null
        val tracks = remote.searchTracks(text).filter { it.playable != false }
        val candidates =
            tracks.map {
                MatchCandidate(
                    id = it.id,
                    title = it.name,
                    artists = it.artists.map(SpotifyArtist::name),
                    album = it.album?.name,
                    durationMs = it.durationMs,
                    explicit = it.explicit,
                    kind = CandidateKind.SONG,
                )
            }
        val best = TrackMatcher.best(query, candidates)?.takeIf { it.score.confidence == MatchConfidence.HIGH } ?: return null
        val track = tracks.first { it.id == best.candidate.id }
        val time = now()
        // Never replaces the video chosen for this track when it was played, nor a manual override.
        matches.insertIfAbsent(
            SpotifyMatchEntity(
                spotifyId = track.id,
                youtubeId = song.id,
                score = best.score.total,
                confidence = MatchConfidence.HIGH,
                isManualOverride = false,
                matcherVersion = TrackMatcher.VERSION,
                createdAt = time,
                updatedAt = time,
                title = track.name,
                artists = track.artists.map { it.name },
                album = track.album?.name,
                durationMs = track.durationMs.takeIf { it > 0 },
                explicit = true.takeIf { track.explicit },
                isrc = track.isrc,
            ),
        )
        return track
    }

    /**
     * Reads the Liked Songs and applies what changed there. Returns false when it did not run or Spotify
     * could not be read to the end; then nothing was un-liked. Without [force], a list whose size and
     * first page are those of the last complete read is not read further, and a read already running is
     * not waited for. [onError] gets what Spotify answered when it could not be read.
     */
    internal suspend fun pull(
        force: Boolean = false,
        onError: (SpotifyError) -> Unit = {},
    ): Boolean {
        if (!canPull()) return false
        if (force) {
            pullLock.lock()
        } else if (!pullLock.tryLock()) {
            return false
        }
        mutableStatus.update { it.copy(syncing = true) }
        try {
            val prefs = settings()
            if (force) dao.retryUnmatchedLikedTracks()
            var page = remote.likedSongs(0)
            val total = page.total
            val fingerprint = "$total:" + page.items.joinToString(",") { it.uri.orEmpty() }
            if (force || fingerprint != prefs.meldSpotifyLikesFingerprint || dao.pendingLikedTrackCount() > 0) {
                val items = page.items.toMutableList()
                while (true) {
                    page = remote.likedSongs(page.nextOffset ?: break)
                    items += page.items
                }
                // In Spotify's order, newest first. A null track is an entry that cannot be played.
                val tracks = LinkedHashMap<String, SpotifyTrack?>()
                var identified = true
                items.forEach { item ->
                    val uri = item.uri ?: item.track?.uri
                    when {
                        uri == null -> identified = false
                        uri.startsWith(TRACK_URI) -> tracks[uri.removePrefix(TRACK_URI)] = item.track
                    }
                }
                val remembered = dao.likedTracks()
                // Whole means every entry Spotify counts was read and named, and the list did not change meanwhile.
                // An empty answer against a remembered library is taken for a failure, not for a clear-out.
                val whole =
                    identified && total != null && page.total == total && items.size == total &&
                        (tracks.isNotEmpty() || remembered.isEmpty())
                val rememberedIds = remembered.mapTo(hashSetOf()) { it.spotifyId }
                dao.insertLikedTracks(tracks.keys.filter { it !in rememberedIds }.map { SpotifyLikedTrackEntity(it, null, pending = true) })
                if (whole) unlikeRemoved(remembered.filter { it.spotifyId !in tracks })
                publish()
                val imported = importPending(tracks)
                if (whole && imported) prefs.meldSpotifyLikesFingerprint = fingerprint
            }
            prefs.meldSpotifyLikesSyncedAt = now()
            return true
        } catch (error: SpotifyError) {
            logger("Liked songs not read: ${error.message}")
            onError(error)
            return false
        } finally {
            pullLock.unlock()
            val syncedAt = settings().meldSpotifyLikesSyncedAt
            mutableStatus.update { it.copy(syncing = false, importDone = 0, importTotal = 0, syncedAt = syncedAt) }
        }
    }

    /** [gone] were in the Liked Songs at the last look and are not any more: the user removed them on Spotify. */
    private suspend fun unlikeRemoved(gone: List<SpotifyLikedTrackEntity>) {
        if (gone.isEmpty()) return
        outboxLock.withLock {
            dao.deleteLikedTracks(gone.map { it.spotifyId })
            // Not while another liked Spotify track plays the same song, and never against an intent still to be sent.
            val keep = dao.likedTracks().mapNotNullTo(hashSetOf()) { it.youtubeId } + dao.outboxIds()
            val unlike = gone.mapNotNull { it.youtubeId }.filterNot { it in keep }.distinct()
            if (unlike.isEmpty()) return
            removing = unlike.toSet()
            try {
                local.remove(unlike)
            } finally {
                removing = emptySet()
            }
        }
    }

    /**
     * Finds the tracks not looked up yet on YouTube and likes the songs, a few at a time and oldest first:
     * upstream lists each batch it is given above the likes it already has when sorting by date added, so
     * the newest must arrive last. Each batch is stored when done, so a stop continues where it was.
     * Returns false when tracks are left.
     */
    private suspend fun importPending(tracks: Map<String, SpotifyTrack?>): Boolean {
        val pending = dao.likedTracks().filter { it.pending }.mapTo(hashSetOf()) { it.spotifyId }
        val todo = tracks.entries.filter { it.key in pending }
        if (todo.isEmpty()) return true
        mutableStatus.update { it.copy(importDone = 0, importTotal = todo.size) }
        var failed = false
        for (batch in todo.chunked(IMPORT_BATCH).asReversed()) {
            currentCoroutineContext().ensureActive()
            if (!canPull()) return false
            val playable = batch.mapNotNull { (_, track) -> track?.takeIf { it.playable != false } }
            val resolved = playable.map { it.id }.zip(resolver.resolveAll(playable)).toMap()
            val failures = resolved.values.count { it is SpotifyResolution.Failed }
            if (failures > 0) failed = true
            // YouTube cannot be reached: the rest waits for the next run.
            if (failures == playable.size && playable.isNotEmpty()) return false
            val songs = mutableListOf<SongItem>()
            val rows = mutableListOf<SpotifyLikedTrackEntity>()
            batch.forEach { (id, _) ->
                val resolution = resolved[id]
                if (resolution is SpotifyResolution.Failed) return@forEach
                // A match that is merely playable is not sure enough to be liked in the user's name.
                val song = (resolution as? SpotifyResolution.Matched)?.takeUnless { it.lowConfidence }?.song
                if (song != null) songs += song
                rows += SpotifyLikedTrackEntity(id, song?.id, pending = false)
            }
            outboxLock.withLock {
                // An intent still to be sent says what the user wants for that song; a disliked song stays disliked.
                val skip = dao.outboxIds().toSet()
                local.add(songs.filterNot { it.id in skip || local.isDisliked(it.id) })
                dao.putLikedTracks(rows)
            }
            mutableStatus.update { it.copy(importDone = it.importDone + batch.size) }
            mutableLikedIds.value = dao.likedTrackIds().toHashSet()
        }
        return !failed
    }

    private suspend fun publish() {
        val pending = dao.outboxCount(notFound = false)
        val notFound = dao.outboxCount(notFound = true)
        mutableStatus.update { it.copy(pending = pending, notFound = notFound, syncedAt = settings().meldSpotifyLikesSyncedAt) }
        mutableLikedIds.value = dao.likedTrackIds().toHashSet()
    }

    private suspend fun flushWithRetries() {
        var failures = 0
        var retry: Job? = null
        for (request in flushRequests) {
            retry?.cancel()
            var error: SpotifyError? = null
            guarded {
                error = flush()
                // With nothing to bring in from Spotify, an emptied outbox is all there is to a sync.
                if (error == null && canPush() && !settings().meldSpotifyLikesPull) {
                    settings().meldSpotifyLikesSyncedAt = now()
                    publish()
                }
            }
            val wait =
                when (val stopped = error) {
                    null -> null.also { failures = 0 }

                    // Nothing to wait for: signing in again asks for a flush.
                    is SpotifyError.SessionExpired, is SpotifyError.NotLoggedIn -> null

                    is SpotifyError.RateLimited -> maxOf(stopped.retryAfterSeconds.seconds, backoff(++failures))

                    else -> backoff(++failures)
                }
            if (wait != null) {
                retry =
                    scope.launch {
                        delay(wait)
                        flushRequests.trySend(Unit)
                    }
            }
        }
    }

    /** A background loop must outlive one bad run: an error here would otherwise end it, or the app. */
    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            logger("Like sync failed: $error")
        }
    }

    private companion object {
        const val TRACK_URI = "spotify:track:"
        const val IMPORT_BATCH = 10
        val PULL_INTERVAL = 1.hours
        val PROFILE_RETRY = 5.minutes

        fun backoff(failures: Int): Duration = minOf(30.seconds * (1 shl (failures - 1).coerceAtMost(6)), 30.minutes)
    }
}

/** A row good enough to act on in the user's Spotify library: the user's own choice or a confident match. */
private val SpotifyMatchEntity.isTrusted: Boolean
    get() = youtubeId != null && (isManualOverride || confidence == MatchConfidence.HIGH)

/** Enough of the track to show it in a list; the artwork arrives with the next read from Spotify. */
private fun SpotifyMatchEntity.toTrack() =
    SpotifyTrack(
        id = spotifyId,
        uri = "spotify:track:$spotifyId",
        name = title,
        artists = artists.map { SpotifyArtist("", "", it) },
        album = album?.let { SpotifyAlbum("", "", it) },
        durationMs = durationMs ?: 0,
        explicit = explicit == true,
        isrc = isrc,
    )
