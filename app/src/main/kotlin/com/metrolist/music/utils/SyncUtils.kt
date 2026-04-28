/**
 * Metrolist Project (C) 2026
 * OuterTune Project Copyright (C) 2025
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.PodcastItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.utils.completed
import com.metrolist.innertube.utils.parseCookieString
import com.metrolist.lastfm.LastFM
import com.metrolist.music.constants.InnerTubeCookieKey
import com.metrolist.music.constants.LastFMUseSendLikes
import com.metrolist.music.constants.LastFullSyncKey
import com.metrolist.music.constants.SYNC_COOLDOWN
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.PlaylistSongMap
import com.metrolist.music.db.entities.PodcastEntity
import com.metrolist.music.db.entities.SetVideoIdEntity
import com.metrolist.music.db.entities.SongEntity
import com.metrolist.music.extensions.collectLatest
import com.metrolist.music.extensions.isInternetConnected
import com.metrolist.music.extensions.isSyncEnabled
import com.metrolist.music.models.toMediaMetadata
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.LocalDateTime
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger

sealed class SyncOperation {
    data object FullSync : SyncOperation()
    data object LikedSongs : SyncOperation()
    data object LibrarySongs : SyncOperation()
    data object UploadedSongs : SyncOperation()
    data object LikedAlbums : SyncOperation()
    data object UploadedAlbums : SyncOperation()
    data object ArtistsSubscriptions : SyncOperation()
    data object PodcastSubscriptions : SyncOperation()
    data object EpisodesForLater : SyncOperation()
    data object SavedPlaylists : SyncOperation()
    data object AutoSyncPlaylists : SyncOperation()
    data class SinglePlaylist(val browseId: String, val playlistId: String) : SyncOperation()
    data class LikeSong(val song: SongEntity) : SyncOperation()
    data class SubscribeChannel(val channelId: String, val subscribe: Boolean) : SyncOperation()
    data class SavePodcast(val podcastId: String, val save: Boolean) : SyncOperation()
    data class SaveEpisode(val episodeId: String, val save: Boolean, val setVideoId: String?) : SyncOperation()
    data object SpotifyFollowedArtists : SyncOperation()
    data object SpotifyLikedSongs : SyncOperation()
    data object CleanupDuplicates : SyncOperation()
    data object ClearAllSynced : SyncOperation()
    data object ClearPodcastData : SyncOperation()
}

sealed class SyncStatus {
    data object Idle : SyncStatus()
    data object Syncing : SyncStatus()
    data class Error(val message: String) : SyncStatus()
    data object Completed : SyncStatus()
}

data class SyncState(
    val overallStatus: SyncStatus = SyncStatus.Idle,
    val likedSongs: SyncStatus = SyncStatus.Idle,
    val librarySongs: SyncStatus = SyncStatus.Idle,
    val uploadedSongs: SyncStatus = SyncStatus.Idle,
    val likedAlbums: SyncStatus = SyncStatus.Idle,
    val uploadedAlbums: SyncStatus = SyncStatus.Idle,
    val artists: SyncStatus = SyncStatus.Idle,
    val playlists: SyncStatus = SyncStatus.Idle,
    val currentOperation: String = ""
)

@Singleton
class SyncUtils @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MusicDatabase,
) {
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        if (throwable !is CancellationException) {
            Timber.e(throwable, "Sync coroutine exception")
        }
    }

    private val syncJob = SupervisorJob()
    private val syncScope = CoroutineScope(Dispatchers.IO + syncJob + exceptionHandler)

    private val syncChannel = Channel<SyncOperation>(Channel.BUFFERED)
    private var processingJob: Job? = null

    private val _syncState = MutableStateFlow(SyncState())
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private var lastfmSendLikes = false
    private var spotifySyncLikes = false
    private val spotifyMapper by lazy { com.metrolist.music.playback.SpotifyYouTubeMapper(database) }
    private val playlistsBeingModified = ConcurrentHashMap<String, AtomicInteger>()
    // Tracks songs currently being added to YouTube — browseId → set of songIds
    private val pendingYouTubeAdds = ConcurrentHashMap<String, MutableSet<String>>()
    private val pendingRemovals = ConcurrentHashMap<String, MutableSet<Triple<String, String, String>>>()

    companion object {
        private const val MAX_RETRIES = 3
        private const val INITIAL_RETRY_DELAY_MS = 1000L
        private const val DB_OPERATION_DELAY_MS = 50L
    }
    private fun markPlaylistModifying(playlistId: String) {
        playlistsBeingModified.getOrPut(playlistId) { AtomicInteger(0) }.incrementAndGet()
    }

    private fun unmarkPlaylistModifying(playlistId: String) {
        playlistsBeingModified[playlistId]?.let { counter ->
            if (counter.decrementAndGet() <= 0) playlistsBeingModified.remove(playlistId)
        }
    }

    private fun isPlaylistBeingModified(playlistId: String): Boolean =
        (playlistsBeingModified[playlistId]?.get() ?: 0) > 0 ||
                pendingRemovals.any { (_, set) -> set.any { it.third == playlistId } }
    init {
        context.dataStore.data
            .map { it[LastFMUseSendLikes] ?: false }
            .distinctUntilChanged()
            .collectLatest(syncScope) {
                lastfmSendLikes = it
            }

        context.dataStore.data
            .map { it[com.metrolist.music.constants.SpotifySyncLikesKey] ?: false }
            .distinctUntilChanged()
            .collectLatest(syncScope) {
                spotifySyncLikes = it
            }

        startProcessingQueue()
    }

    private fun startProcessingQueue() {
        processingJob = syncScope.launch {
            for (operation in syncChannel) {
                try {
                    processOperation(operation)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Error processing sync operation: $operation")
                }
            }
        }
    }

    private suspend fun processOperation(operation: SyncOperation) {
        when (operation) {
            is SyncOperation.FullSync -> executeFullSync()
            is SyncOperation.LikedSongs -> executeSyncLikedSongs()
            is SyncOperation.LibrarySongs -> executeSyncLibrarySongs()
            is SyncOperation.UploadedSongs -> executeSyncUploadedSongs()
            is SyncOperation.LikedAlbums -> executeSyncLikedAlbums()
            is SyncOperation.UploadedAlbums -> executeSyncUploadedAlbums()
            is SyncOperation.ArtistsSubscriptions -> executeSyncArtistsSubscriptions()
            is SyncOperation.PodcastSubscriptions -> executeSyncPodcastSubscriptions()
            is SyncOperation.EpisodesForLater -> executeSyncEpisodesForLater()
            is SyncOperation.SavedPlaylists -> executeSyncSavedPlaylists()
            is SyncOperation.AutoSyncPlaylists -> executeSyncAutoSyncPlaylists()
            is SyncOperation.SinglePlaylist -> executeSyncPlaylist(operation.browseId, operation.playlistId)
            is SyncOperation.LikeSong -> executeLikeSong(operation.song)
            is SyncOperation.SubscribeChannel -> executeSubscribeChannel(operation.channelId, operation.subscribe)
            is SyncOperation.SavePodcast -> executeSavePodcast(operation.podcastId, operation.save)
            is SyncOperation.SaveEpisode -> executeSaveEpisode(operation.episodeId, operation.save, operation.setVideoId)
            is SyncOperation.SpotifyFollowedArtists -> executeSyncSpotifyFollowedArtists()
            is SyncOperation.SpotifyLikedSongs -> executeSyncSpotifyLikedSongs()
            is SyncOperation.CleanupDuplicates -> executeCleanupDuplicatePlaylists()
            is SyncOperation.ClearAllSynced -> executeClearAllSyncedContent()
            is SyncOperation.ClearPodcastData -> executeClearPodcastData()
        }
    }

    private suspend fun isLoggedIn(): Boolean {
        return try {
            val cookie = context.dataStore.data
                .map { it[InnerTubeCookieKey] }
                .first()
            cookie?.let { "SAPISID" in parseCookieString(it) } ?: false
        } catch (e: Exception) {
            Timber.e(e, "Error checking login status")
            false
        }
    }

    private suspend fun <T> withRetry(
        maxRetries: Int = MAX_RETRIES,
        initialDelay: Long = INITIAL_RETRY_DELAY_MS,
        block: suspend () -> T
    ): Result<T> {
        var currentDelay = initialDelay
        repeat(maxRetries) { attempt ->
            try {
                return Result.success(block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Attempt ${attempt + 1}/$maxRetries failed")
                if (attempt == maxRetries - 1) {
                    return Result.failure(e)
                }
                delay(currentDelay)
                currentDelay *= 2
            }
        }
        return Result.failure(Exception("Max retries exceeded"))
    }

    private fun updateState(update: SyncState.() -> SyncState) {
        _syncState.value = _syncState.value.update()
    }

    // Public API methods - Queue operations

    fun performFullSync() {
        syncScope.launch {
            syncChannel.send(SyncOperation.FullSync)
        }
    }

    suspend fun performFullSyncSuspend() {
        if (!isLoggedIn()) {
            Timber.w("Skipping full sync - user not logged in")
            return
        }
        executeFullSync()
    }

    fun tryAutoSync() {
        syncScope.launch {
            if (!isLoggedIn()) {
                Timber.d("Skipping auto sync - user not logged in")
                return@launch
            }

            if (!context.isSyncEnabled() || !context.isInternetConnected()) {
                return@launch
            }

            val lastSync = context.dataStore.get(LastFullSyncKey, 0L)
            val currentTime = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC)
            if (lastSync > 0 && (currentTime - lastSync) < SYNC_COOLDOWN) {
                return@launch
            }

            syncChannel.send(SyncOperation.FullSync)

            context.dataStore.edit { settings ->
                settings[LastFullSyncKey] = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC)
            }
        }
    }

    fun runAllSyncs() {
        performFullSync()
    }

    fun likeSong(s: SongEntity) {
        syncScope.launch {
            syncChannel.send(SyncOperation.LikeSong(s))
        }
    }

    fun subscribeChannel(channelId: String, subscribe: Boolean) {
        syncScope.launch {
            syncChannel.send(SyncOperation.SubscribeChannel(channelId, subscribe))
        }
    }

    fun savePodcast(podcastId: String, save: Boolean) {
        Timber.d("[PODCAST_TOGGLE] SyncUtils.savePodcast called: podcastId=$podcastId, save=$save")
        syncScope.launch {
            Timber.d("[PODCAST_TOGGLE] Sending SavePodcast operation to channel")
            syncChannel.send(SyncOperation.SavePodcast(podcastId, save))
        }
    }

    fun saveEpisode(episodeId: String, save: Boolean, setVideoId: String? = null) {
        syncScope.launch {
            syncChannel.send(SyncOperation.SaveEpisode(episodeId, save, setVideoId))
        }
    }

    fun syncLikedSongs() {
        syncScope.launch {
            syncChannel.send(SyncOperation.LikedSongs)
        }
    }

    fun syncLibrarySongs() {
        syncScope.launch {
            syncChannel.send(SyncOperation.LibrarySongs)
        }
    }

    fun syncUploadedSongs() {
        syncScope.launch {
            syncChannel.send(SyncOperation.UploadedSongs)
        }
    }

    fun syncLikedAlbums() {
        syncScope.launch {
            syncChannel.send(SyncOperation.LikedAlbums)
        }
    }

    fun syncUploadedAlbums() {
        syncScope.launch {
            syncChannel.send(SyncOperation.UploadedAlbums)
        }
    }

    fun syncArtistsSubscriptions() {
        syncScope.launch {
            syncChannel.send(SyncOperation.ArtistsSubscriptions)
        }
    }

    fun syncSavedPlaylists() {
        syncScope.launch {
            syncChannel.send(SyncOperation.SavedPlaylists)
        }
    }

    fun syncAutoSyncPlaylists() {
        syncScope.launch {
            syncChannel.send(SyncOperation.AutoSyncPlaylists)
        }
    }

    fun syncAllAlbums() {
        syncScope.launch {
            syncChannel.send(SyncOperation.LikedAlbums)
            syncChannel.send(SyncOperation.UploadedAlbums)
        }
    }

    fun syncAllArtists() {
        syncScope.launch {
            syncChannel.send(SyncOperation.ArtistsSubscriptions)
            syncChannel.send(SyncOperation.SpotifyFollowedArtists)
        }
    }

    fun syncSpotifyFollowedArtists() {
        syncScope.launch {
            syncChannel.send(SyncOperation.SpotifyFollowedArtists)
        }
    }

    fun syncSpotifyLikedSongs() {
        syncScope.launch {
            syncChannel.send(SyncOperation.SpotifyLikedSongs)
        }
    }

    fun syncPodcastSubscriptions() {
        syncScope.launch {
            syncChannel.send(SyncOperation.PodcastSubscriptions)
        }
    }

    fun syncEpisodesForLater() {
        syncScope.launch {
            syncChannel.send(SyncOperation.EpisodesForLater)
        }
    }

    fun cleanupDuplicatePlaylists() {
        syncScope.launch {
            syncChannel.send(SyncOperation.CleanupDuplicates)
        }
    }

    fun clearAllSyncedContent() {
        syncScope.launch {
            syncChannel.send(SyncOperation.ClearAllSynced)
        }
    }

    fun clearPodcastData() {
        syncScope.launch {
            syncChannel.send(SyncOperation.ClearPodcastData)
        }
    }

    // Suspend versions for direct calls

    suspend fun syncLikedSongsSuspend() = executeSyncLikedSongs()
    suspend fun syncLibrarySongsSuspend() = executeSyncLibrarySongs()
    suspend fun syncUploadedSongsSuspend() = executeSyncUploadedSongs()
    suspend fun syncLikedAlbumsSuspend() = executeSyncLikedAlbums()
    suspend fun syncUploadedAlbumsSuspend() = executeSyncUploadedAlbums()
    suspend fun syncArtistsSubscriptionsSuspend() = executeSyncArtistsSubscriptions()
    suspend fun syncSpotifyFollowedArtistsSuspend() = executeSyncSpotifyFollowedArtists()
    suspend fun syncSpotifyLikedSongsSuspend() = executeSyncSpotifyLikedSongs()
    suspend fun syncPodcastSubscriptionsSuspend() = executeSyncPodcastSubscriptions()
    suspend fun syncEpisodesForLaterSuspend() = executeSyncEpisodesForLater()
    suspend fun syncSavedPlaylistsSuspend() = executeSyncSavedPlaylists()
    suspend fun syncAutoSyncPlaylistsSuspend() = executeSyncAutoSyncPlaylists()
    suspend fun cleanupDuplicatePlaylistsSuspend() = executeCleanupDuplicatePlaylists()
    suspend fun clearAllSyncedContentSuspend() = executeClearAllSyncedContent()

    suspend fun clearAllLibraryData() = withContext(Dispatchers.IO) {
        Timber.d("[LOGOUT_CLEAR] Starting complete library data cleanup")
        try {
            // Clear podcast data first
            Timber.d("[LOGOUT_CLEAR] Clearing podcast data")
            executeClearPodcastData()

            // Clear history
            Timber.d("[LOGOUT_CLEAR] Clearing listen history and search history")
            database.clearListenHistory()
            database.clearSearchHistory()

            // Get all user tables from the database (auto-detect)
            val allTables = getAllUserTables()
            Timber.d("[LOGOUT_CLEAR] Found ${allTables.size} tables: $allTables")

            // Tables to skip (system tables and tables we handle specially)
            val skipTables = setOf(
                "android_metadata",
                "room_master_table",
                "sqlite_sequence",
                "search_history",  // Already cleared above
                "listen_history"   // Already cleared above
            )

            // Tables with foreign key references - delete these first (mapping tables)
            val mappingTables = listOf(
                "playlist_song_map",
                "song_album_map",
                "song_artist_map",
                "album_artist_map",
                "related_song_map"
            )

            // Delete mapping tables first
            Timber.d("[LOGOUT_CLEAR] Deleting mapping tables")
            for (table in mappingTables) {
                if (table in allTables) {
                    safeDeleteTable(table)
                }
            }

            // Delete all other tables except song (handled specially to keep downloads)
            Timber.d("[LOGOUT_CLEAR] Deleting remaining tables")
            for (table in allTables) {
                if (table in skipTables || table in mappingTables || table == "song") {
                    continue
                }
                safeDeleteTable(table)
            }

            // Finally, delete songs but keep downloaded ones
            if ("song" in allTables) {
                Timber.d("[LOGOUT_CLEAR] Deleting songs (keeping downloaded)")
                safeRawQuery("DELETE FROM song WHERE dateDownload IS NULL")
            }

            Timber.d("[LOGOUT_CLEAR] All library data cleared successfully")
        } catch (e: Exception) {
            Timber.e(e, "[LOGOUT_CLEAR] Error clearing library data")
            throw e
        }
    }

    private fun getAllUserTables(): List<String> {
        val tables = mutableListOf<String>()
        try {
            database.openHelper.writableDatabase.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    tables.add(cursor.getString(0))
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "[LOGOUT_CLEAR] Error getting table list")
        }
        return tables
    }

    private fun safeDeleteTable(tableName: String) {
        try {
            database.raw(androidx.sqlite.db.SimpleSQLiteQuery("DELETE FROM $tableName"))
            Timber.d("[LOGOUT_CLEAR] Cleared table: $tableName")
        } catch (e: Exception) {
            Timber.w("[LOGOUT_CLEAR] Table $tableName error: ${e.message}")
        }
    }

    private fun safeRawQuery(query: String) {
        try {
            database.raw(androidx.sqlite.db.SimpleSQLiteQuery(query))
            Timber.d("[LOGOUT_CLEAR] Executed: $query")
        } catch (e: Exception) {
            Timber.w("[LOGOUT_CLEAR] Query failed: $query - ${e.message}")
        }
    }

    suspend fun syncAllAlbumsSuspend() {
        executeSyncLikedAlbums()
        executeSyncUploadedAlbums()
    }

    suspend fun syncAllArtistsSuspend() {
        executeSyncArtistsSubscriptions()
        executeSyncSpotifyFollowedArtists()
    }

    // Private execution methods

    private suspend fun executeFullSync() = withContext(Dispatchers.IO) {
        if (!isLoggedIn()) {
            Timber.w("Skipping full sync - user not logged in")
            return@withContext
        }

        updateState { copy(overallStatus = SyncStatus.Syncing, currentOperation = "Starting full sync") }

        try {
            // Sync in sequence to avoid overwhelming the API and database
            executeSyncLikedSongs()
            delay(DB_OPERATION_DELAY_MS)

            // Pull Spotify liked songs into the local DB so the heart icon
            // reflects Spotify-side likes too. Skipped internally if the
            // sync setting is off or Spotify isn't authenticated.
            executeSyncSpotifyLikedSongs()
            delay(DB_OPERATION_DELAY_MS)

            executeSyncLibrarySongs()
            delay(DB_OPERATION_DELAY_MS)

            executeSyncUploadedSongs()
            delay(DB_OPERATION_DELAY_MS)

            executeSyncLikedAlbums()
            delay(DB_OPERATION_DELAY_MS)

            executeSyncUploadedAlbums()
            delay(DB_OPERATION_DELAY_MS)

            executeSyncArtistsSubscriptions()
            delay(DB_OPERATION_DELAY_MS)

            executeSyncSpotifyFollowedArtists()
            delay(DB_OPERATION_DELAY_MS)

            executeSyncPodcastSubscriptions()
            delay(DB_OPERATION_DELAY_MS)

            executeSyncEpisodesForLater()
            delay(DB_OPERATION_DELAY_MS)

            executeSyncSavedPlaylists()
            delay(DB_OPERATION_DELAY_MS)

            executeSyncAutoSyncPlaylists()

            updateState { copy(overallStatus = SyncStatus.Completed, currentOperation = "") }
            Timber.d("Full sync completed successfully")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Error during full sync")
            updateState { copy(overallStatus = SyncStatus.Error(e.message ?: "Unknown error"), currentOperation = "") }
        }
    }

    private suspend fun executeLikeSong(s: SongEntity) = withContext(Dispatchers.IO) {
        if (isLoggedIn()) {
            withRetry {
                YouTube.likeVideo(s.id, s.liked)
            }.onFailure { e ->
                Timber.e(e, "Failed to like song on YouTube: ${s.id}")
            }
        } else {
            Timber.d("YouTube not logged in, skipping YouTube like sync for ${s.id}")
        }

        if (lastfmSendLikes) {
            try {
                val dbSong = database.song(s.id).firstOrNull()
                LastFM.setLoveStatus(
                    artist = dbSong?.artists?.joinToString { a -> a.name } ?: "",
                    track = s.title,
                    love = s.liked
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to update LastFM love status")
            }
        }

        if (spotifySyncLikes) {
            try {
                val dbSong = database.song(s.id).firstOrNull()
                val artist = dbSong?.artists?.firstOrNull()?.name ?: ""
                val durationSec = s.duration
                val spotifyUri = spotifyMapper.resolveToSpotifyUri(s.id, s.title, artist, durationSec)
                if (spotifyUri != null) {
                    if (s.liked) {
                        com.metrolist.spotify.Spotify.addToLibrary(listOf(spotifyUri))
                            .onSuccess { Timber.d("Synced like to Spotify: $spotifyUri") }
                            .onFailure { e -> Timber.e(e, "Failed to sync like to Spotify: $spotifyUri") }
                    } else {
                        com.metrolist.spotify.Spotify.removeFromLibrary(listOf(spotifyUri))
                            .onSuccess { Timber.d("Synced unlike to Spotify: $spotifyUri") }
                            .onFailure { e -> Timber.e(e, "Failed to sync unlike to Spotify: $spotifyUri") }
                    }
                } else {
                    Timber.w("Spotify like sync: no Spotify match found for ${s.id}")
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to sync like to Spotify for ${s.id}")
            }
        }
    }

    private suspend fun executeSubscribeChannel(channelId: String, subscribe: Boolean) = withContext(Dispatchers.IO) {
        Timber.d("[CHANNEL_TOGGLE] executeSubscribeChannel called: channelId=$channelId, subscribe=$subscribe")
        if (!isLoggedIn()) {
            Timber.d("[CHANNEL_TOGGLE] Skipping subscribeChannel - user not logged in")
            return@withContext
        }

        Timber.d("[CHANNEL_TOGGLE] User is logged in, calling YouTube.subscribeChannel")
        withRetry {
            YouTube.subscribeChannel(channelId, subscribe)
        }.onSuccess {
            Timber.d("[CHANNEL_TOGGLE] Successfully subscribed/unsubscribed channel: $channelId")
            PodcastRefreshTrigger.triggerRefresh()
        }.onFailure { e ->
            Timber.e(e, "[CHANNEL_TOGGLE] Failed to subscribe/unsubscribe channel: $channelId")
        }
    }

    private suspend fun executeSavePodcast(podcastId: String, save: Boolean) = withContext(Dispatchers.IO) {
        Timber.d("[PODCAST_TOGGLE] executeSavePodcast called: podcastId=$podcastId, save=$save")
        if (!isLoggedIn()) {
            Timber.d("[PODCAST_TOGGLE] Skipping savePodcast - user not logged in")
            return@withContext
        }

        Timber.d("[PODCAST_TOGGLE] User is logged in, calling YouTube.savePodcast")
        withRetry {
            YouTube.savePodcast(podcastId, save)
        }.onSuccess {
            Timber.d("[PODCAST_TOGGLE] Successfully saved/unsaved podcast: $podcastId")
        }.onFailure { e ->
            Timber.e(e, "[PODCAST_TOGGLE] Failed to save/unsave podcast: $podcastId")
        }
    }

    private suspend fun executeSaveEpisode(episodeId: String, save: Boolean, setVideoId: String?) = withContext(Dispatchers.IO) {
        if (!isLoggedIn()) {
            Timber.d("Skipping saveEpisode - user not logged in")
            return@withContext
        }

        if (save) {
            withRetry {
                YouTube.addEpisodeToSavedEpisodes(episodeId)
            }.onFailure { e ->
                Timber.e(e, "Failed to save episode: $episodeId")
            }
        } else {
            if (setVideoId != null) {
                withRetry {
                    YouTube.removeEpisodeFromSavedEpisodes(episodeId, setVideoId)
                }.onFailure { e ->
                    Timber.e(e, "Failed to remove episode: $episodeId")
                }
            }
        }
    }

    private suspend fun executeSyncLikedSongs() = withContext(Dispatchers.IO) {
        if (!isLoggedIn()) {
            Timber.w("Skipping syncLikedSongs - user not logged in")
            return@withContext
        }

        updateState { copy(likedSongs = SyncStatus.Syncing, currentOperation = "Syncing liked songs") }

        withRetry {
            YouTube.playlist("LM").completed()
        }.onSuccess { result ->
            result.onSuccess { page ->
                try {
                    val remoteSongs = page.songs
                    val remoteIds = remoteSongs.map { it.id }.toSet()
                    val localSongs = database.likedSongsByNameAsc().first()
                    val now = LocalDateTime.now()

                    // Pre-fetch all existing songs in a single batch query
                    // so we don't need per-song reads inside the transaction
                    val existingSongs = database.getSongsByIds(remoteSongs.map { it.id })
                        .associateBy { it.id }

                    // Wrap all DB writes in a single transaction so Room emits
                    // only one Flow update with the final state, preventing UI
                    // flickering from intermediate states during sync
                    database.withTransaction {
                        localSongs.filterNot { it.id in remoteIds }.forEach { song ->
                            try {
                                update(song.song.localToggleLike())
                            } catch (e: Exception) {
                                Timber.e(e, "Failed to update song: ${song.id}")
                            }
                        }

                        remoteSongs.forEachIndexed { index, song ->
                            try {
                                val dbSong = existingSongs[song.id]
                                val timestamp = now.minusSeconds(index.toLong())
                                val isVideoSong = song.isVideoSong

                                if (dbSong == null) {
                                    insert(song.toMediaMetadata()) {
                                        it.copy(liked = true, likedDate = timestamp, isVideo = isVideoSong)
                                    }
                                } else if (!dbSong.song.liked || dbSong.song.likedDate != timestamp || dbSong.song.isVideo != isVideoSong) {
                                    update(dbSong.song.copy(liked = true, likedDate = timestamp, isVideo = isVideoSong))
                                }
                            } catch (e: Exception) {
                                Timber.e(e, "Failed to process song: ${song.id}")
                            }
                        }
                    }

                    updateState { copy(likedSongs = SyncStatus.Completed) }
                    Timber.d("Synced ${remoteSongs.size} liked songs")
                } catch (e: Exception) {
                    Timber.e(e, "Error processing liked songs")
                    updateState { copy(likedSongs = SyncStatus.Error(e.message ?: "Unknown error")) }
                }
            }.onFailure { e ->
                Timber.e(e, "Failed to fetch liked songs from YouTube")
                updateState { copy(likedSongs = SyncStatus.Error(e.message ?: "Unknown error")) }
            }
        }.onFailure { e ->
            Timber.e(e, "Failed to sync liked songs after retries")
            updateState { copy(likedSongs = SyncStatus.Error(e.message ?: "Unknown error")) }
        }
    }

    private suspend fun executeSyncLibrarySongs() = withContext(Dispatchers.IO) {
        if (!isLoggedIn()) {
            Timber.w("Skipping syncLibrarySongs - user not logged in")
            return@withContext
        }

        updateState { copy(librarySongs = SyncStatus.Syncing, currentOperation = "Syncing library songs") }

        withRetry {
            YouTube.library("FEmusic_liked_videos").completed()
        }.onSuccess { result ->
            result.onSuccess { page ->
                try {
                    val remoteSongs = page.items.filterIsInstance<SongItem>().reversed()
                    val remoteIds = remoteSongs.map { it.id }.toSet()
                    val localSongs = database.songsByNameAsc().first()

                    val existingSongs = database.getSongsByIds(remoteSongs.map { it.id })
                        .associateBy { it.id }

                    database.withTransaction {
                        localSongs.filterNot { it.id in remoteIds }.forEach { song ->
                            try {
                                update(song.song.toggleLibrary())
                            } catch (e: Exception) {
                                Timber.e(e, "Failed to update song: ${song.id}")
                            }
                        }

                        remoteSongs.forEach { song ->
                            try {
                                val dbSong = existingSongs[song.id]
                                if (dbSong == null) {
                                    insert(song.toMediaMetadata()) { it.toggleLibrary() }
                                } else if (dbSong.song.inLibrary == null) {
                                    update(dbSong.song.toggleLibrary())
                                }
                            } catch (e: Exception) {
                                Timber.e(e, "Failed to process song: ${song.id}")
                            }
                        }
                    }

                    updateState { copy(librarySongs = SyncStatus.Completed) }
                    Timber.d("Synced ${remoteSongs.size} library songs")
                } catch (e: Exception) {
                    Timber.e(e, "Error processing library songs")
                    updateState { copy(librarySongs = SyncStatus.Error(e.message ?: "Unknown error")) }
                }
            }.onFailure { e ->
                Timber.e(e, "Failed to fetch library songs from YouTube")
                updateState { copy(librarySongs = SyncStatus.Error(e.message ?: "Unknown error")) }
            }
        }.onFailure { e ->
            Timber.e(e, "Failed to sync library songs after retries")
            updateState { copy(librarySongs = SyncStatus.Error(e.message ?: "Unknown error")) }
        }
    }

    private suspend fun executeSyncUploadedSongs() = withContext(Dispatchers.IO) {
        if (!isLoggedIn()) {
            Timber.w("Skipping syncUploadedSongs - user not logged in")
            return@withContext
        }

        updateState { copy(uploadedSongs = SyncStatus.Syncing, currentOperation = "Syncing uploaded songs") }

        withRetry {
            // Uploaded songs are in Tab 1 ("Uploads"), not Tab 0 ("Library")
            YouTube.library("FEmusic_library_privately_owned_tracks", tabIndex = 1).completed()
        }.onSuccess { result ->
            result.onSuccess { page ->
                try {
                    val remoteSongs = page.items.filterIsInstance<SongItem>().reversed()
                    val remoteIds = remoteSongs.map { it.id }.toSet()
                    val localSongs = database.uploadedSongsByNameAsc().first()

                    val songsToRemove = localSongs.filterNot { it.id in remoteIds }
                    val existingSongs = database.getSongsByIds(remoteSongs.map { it.id })
                        .associateBy { it.id }

                    database.withTransaction {
                        songsToRemove.forEach { song ->
                            try {
                                update(song.song.toggleUploaded())
                            } catch (e: Exception) {
                                Timber.e(e, "Failed to update song: ${song.id}")
                            }
                        }

                        remoteSongs.forEach { song ->
                            try {
                                val dbSong = existingSongs[song.id]
                                if (dbSong == null) {
                                    insert(song.toMediaMetadata()) { it.toggleUploaded() }
                                } else if (!dbSong.song.isUploaded) {
                                    update(dbSong.song.copy(isUploaded = true, uploadEntityId = song.uploadEntityId))
                                } else if (dbSong.song.uploadEntityId != song.uploadEntityId && song.uploadEntityId != null) {
                                    // Update uploadEntityId if it differs from remote
                                    update(dbSong.song.copy(uploadEntityId = song.uploadEntityId))
                                }
                            } catch (e: Exception) {
                                Timber.e(e, "Failed to process song: ${song.id}")
                            }
                        }
                    }

                    updateState { copy(uploadedSongs = SyncStatus.Completed) }
                    Timber.d("Synced ${remoteSongs.size} uploaded songs")
                } catch (e: Exception) {
                    Timber.e(e, "Error processing uploaded songs")
                    updateState { copy(uploadedSongs = SyncStatus.Error(e.message ?: "Unknown error")) }
                }
            }.onFailure { e ->
                Timber.e(e, "Failed to fetch uploaded songs from YouTube")
                updateState { copy(uploadedSongs = SyncStatus.Error(e.message ?: "Unknown error")) }
            }
        }.onFailure { e ->
            Timber.e(e, "Failed to sync uploaded songs after retries")
            updateState { copy(uploadedSongs = SyncStatus.Error(e.message ?: "Unknown error")) }
        }
    }

    private suspend fun executeSyncLikedAlbums() = withContext(Dispatchers.IO) {
        if (!isLoggedIn()) {
            Timber.w("Skipping syncLikedAlbums - user not logged in")
            return@withContext
        }

        updateState { copy(likedAlbums = SyncStatus.Syncing, currentOperation = "Syncing liked albums") }

        withRetry {
            YouTube.library("FEmusic_liked_albums").completed()
        }.onSuccess { result ->
            result.onSuccess { page ->
                try {
                    val remoteAlbums = page.items.filterIsInstance<AlbumItem>().reversed()
                    val remoteIds = remoteAlbums.map { it.id }.toSet()
                    val localAlbums = database.albumsLikedByNameAsc().first()

                    localAlbums.filterNot { it.id in remoteIds }.forEach { album ->
                        try {
                            database.update(album.album.localToggleLike())
                            delay(DB_OPERATION_DELAY_MS)
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to update album: ${album.id}")
                        }
                    }

                    remoteAlbums.forEach { album ->
                        try {
                            val dbAlbum = database.album(album.id).firstOrNull()
                            YouTube.album(album.browseId).onSuccess { albumPage ->
                                if (dbAlbum == null) {
                                    database.insert(albumPage)
                                    database.album(album.id).firstOrNull()?.let { newDbAlbum ->
                                        database.update(newDbAlbum.album.localToggleLike())
                                    }
                                } else if (dbAlbum.album.bookmarkedAt == null) {
                                    database.update(dbAlbum.album.localToggleLike())
                                }
                            }
                            delay(DB_OPERATION_DELAY_MS)
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to process album: ${album.id}")
                        }
                    }

                    updateState { copy(likedAlbums = SyncStatus.Completed) }
                    Timber.d("Synced ${remoteAlbums.size} liked albums")
                } catch (e: Exception) {
                    Timber.e(e, "Error processing liked albums")
                    updateState { copy(likedAlbums = SyncStatus.Error(e.message ?: "Unknown error")) }
                }
            }.onFailure { e ->
                Timber.e(e, "Failed to fetch liked albums from YouTube")
                updateState { copy(likedAlbums = SyncStatus.Error(e.message ?: "Unknown error")) }
            }
        }.onFailure { e ->
            Timber.e(e, "Failed to sync liked albums after retries")
            updateState { copy(likedAlbums = SyncStatus.Error(e.message ?: "Unknown error")) }
        }
    }

    private suspend fun executeSyncUploadedAlbums() = withContext(Dispatchers.IO) {
        if (!isLoggedIn()) {
            Timber.w("Skipping syncUploadedAlbums - user not logged in")
            return@withContext
        }

        updateState { copy(uploadedAlbums = SyncStatus.Syncing, currentOperation = "Syncing uploaded albums") }

        withRetry {
            YouTube.library("FEmusic_library_privately_owned_releases").completed()
        }.onSuccess { result ->
            result.onSuccess { page ->
                try {
                    val remoteAlbums = page.items.filterIsInstance<AlbumItem>().reversed()
                    val remoteIds = remoteAlbums.map { it.id }.toSet()
                    val localAlbums = database.albumsUploadedByNameAsc().first()

                    localAlbums.filterNot { it.id in remoteIds }.forEach { album ->
                        try {
                            database.update(album.album.toggleUploaded())
                            delay(DB_OPERATION_DELAY_MS)
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to update album: ${album.id}")
                        }
                    }

                    remoteAlbums.forEach { album ->
                        try {
                            val dbAlbum = database.album(album.id).firstOrNull()
                            YouTube.album(album.browseId).onSuccess { albumPage ->
                                if (dbAlbum == null) {
                                    database.insert(albumPage)
                                    database.album(album.id).firstOrNull()?.let { newDbAlbum ->
                                        database.update(newDbAlbum.album.toggleUploaded())
                                    }
                                } else if (!dbAlbum.album.isUploaded) {
                                    database.update(dbAlbum.album.toggleUploaded())
                                }
                            }.onFailure { reportException(it) }
                            delay(DB_OPERATION_DELAY_MS)
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to process album: ${album.id}")
                        }
                    }

                    updateState { copy(uploadedAlbums = SyncStatus.Completed) }
                    Timber.d("Synced ${remoteAlbums.size} uploaded albums")
                } catch (e: Exception) {
                    Timber.e(e, "Error processing uploaded albums")
                    updateState { copy(uploadedAlbums = SyncStatus.Error(e.message ?: "Unknown error")) }
                }
            }.onFailure { e ->
                Timber.e(e, "Failed to fetch uploaded albums from YouTube")
                updateState { copy(uploadedAlbums = SyncStatus.Error(e.message ?: "Unknown error")) }
            }
        }.onFailure { e ->
            Timber.e(e, "Failed to sync uploaded albums after retries")
            updateState { copy(uploadedAlbums = SyncStatus.Error(e.message ?: "Unknown error")) }
        }
    }

    private suspend fun executeSyncArtistsSubscriptions() = withContext(Dispatchers.IO) {
        if (!isLoggedIn()) {
            Timber.w("Skipping syncArtistsSubscriptions - user not logged in")
            return@withContext
        }

        updateState { copy(artists = SyncStatus.Syncing, currentOperation = "Syncing artist subscriptions") }

        withRetry {
            YouTube.library("FEmusic_library_corpus_artists").completed()
        }.onSuccess { result ->
            result.onSuccess { page ->
                try {
                    val remoteArtists = page.items.filterIsInstance<ArtistItem>()
                    val remoteIds = remoteArtists.map { it.id }.toSet()
                    val localArtists = database.artistsBookmarkedByNameAsc().first()

                    localArtists.filterNot { it.id in remoteIds }.forEach { artist ->
                        try {
                            database.update(artist.artist.localToggleLike())
                            delay(DB_OPERATION_DELAY_MS)
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to update artist: ${artist.id}")
                        }
                    }

                    remoteArtists.forEach { artist ->
                        try {
                            val dbArtist = database.artist(artist.id).firstOrNull()
                            val channelId = artist.channelId ?: if (artist.id.startsWith("UC")) {
                                try {
                                    YouTube.getChannelId(artist.id).takeIf { it.isNotEmpty() }
                                } catch (e: Exception) {
                                    null
                                }
                            } else null

                            database.transaction {
                                if (dbArtist == null) {
                                    insert(
                                        ArtistEntity(
                                            id = artist.id,
                                            name = artist.title,
                                            thumbnailUrl = artist.thumbnail,
                                            channelId = channelId,
                                            bookmarkedAt = LocalDateTime.now()
                                        )
                                    )
                                } else {
                                    val existing = dbArtist.artist
                                    val needsChannelIdUpdate = existing.channelId == null && channelId != null
                                    if (existing.bookmarkedAt == null || needsChannelIdUpdate ||
                                        existing.name != artist.title || existing.thumbnailUrl != artist.thumbnail) {
                                        update(
                                            existing.copy(
                                                name = artist.title,
                                                thumbnailUrl = artist.thumbnail,
                                                channelId = channelId ?: existing.channelId,
                                                bookmarkedAt = existing.bookmarkedAt ?: LocalDateTime.now(),
                                                lastUpdateTime = LocalDateTime.now()
                                            )
                                        )
                                    }
                                }
                            }
                            delay(DB_OPERATION_DELAY_MS)
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to process artist: ${artist.id}")
                        }
                    }

                    updateState { copy(artists = SyncStatus.Completed) }
                    Timber.d("Synced ${remoteArtists.size} artist subscriptions")
                } catch (e: Exception) {
                    Timber.e(e, "Error processing artist subscriptions")
                    updateState { copy(artists = SyncStatus.Error(e.message ?: "Unknown error")) }
                }
            }.onFailure { e ->
                Timber.e(e, "Failed to fetch artist subscriptions from YouTube")
                updateState { copy(artists = SyncStatus.Error(e.message ?: "Unknown error")) }
            }
        }.onFailure { e ->
            Timber.e(e, "Failed to sync artist subscriptions after retries")
            updateState { copy(artists = SyncStatus.Error(e.message ?: "Unknown error")) }
        }
    }

    private suspend fun executeSyncSpotifyFollowedArtists() = withContext(Dispatchers.IO) {
        if (!com.metrolist.spotify.Spotify.isAuthenticated()) {
            Timber.d("Skipping Spotify followed artists sync - not authenticated with Spotify")
            return@withContext
        }

        updateState { copy(currentOperation = "Syncing Spotify followed artists") }

        try {
            val followedArtists = com.metrolist.music.playback.SpotifyProfileCache
                .getFollowedArtists(context, database, limit = 500)

            if (followedArtists.isEmpty()) {
                Timber.d("No Spotify followed artists found")
                return@withContext
            }

            var synced = 0
            for (spotifyArtist in followedArtists) {
                try {
                    val spotifyId = spotifyArtist.id
                    if (spotifyId.isBlank()) continue

                    database.transaction {
                        // First check if we already have an artist linked to this spotifyId
                        val existingBySpotifyId = artistBySpotifyId(spotifyId)
                        if (existingBySpotifyId != null) {
                            // Update name/thumbnail if changed
                            val imageUrl = spotifyArtist.images.firstOrNull()?.url
                            if (existingBySpotifyId.name != spotifyArtist.name ||
                                (imageUrl != null && existingBySpotifyId.thumbnailUrl != imageUrl)
                            ) {
                                update(
                                    existingBySpotifyId.copy(
                                        name = spotifyArtist.name,
                                        thumbnailUrl = imageUrl ?: existingBySpotifyId.thumbnailUrl,
                                        lastUpdateTime = LocalDateTime.now()
                                    )
                                )
                            }
                            synced++
                            return@transaction
                        }

                        // Try to match by name to an existing artist
                        val existingByName = artistByName(spotifyArtist.name)
                        if (existingByName != null) {
                            // Link existing artist to Spotify
                            val imageUrl = spotifyArtist.images.firstOrNull()?.url
                            update(
                                existingByName.copy(
                                    spotifyId = spotifyId,
                                    thumbnailUrl = imageUrl ?: existingByName.thumbnailUrl,
                                    bookmarkedAt = existingByName.bookmarkedAt ?: LocalDateTime.now(),
                                    lastUpdateTime = LocalDateTime.now()
                                )
                            )
                            synced++
                            return@transaction
                        }

                        // Create a new artist entry
                        val imageUrl = spotifyArtist.images.firstOrNull()?.url
                        insert(
                            ArtistEntity(
                                id = "SP_$spotifyId",
                                name = spotifyArtist.name,
                                thumbnailUrl = imageUrl,
                                spotifyId = spotifyId,
                                bookmarkedAt = LocalDateTime.now()
                            )
                        )
                        synced++
                    }
                    delay(DB_OPERATION_DELAY_MS)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to sync Spotify artist: ${spotifyArtist.name}")
                }
            }

            Timber.d("Synced $synced/${followedArtists.size} Spotify followed artists")
        } catch (e: Exception) {
            Timber.e(e, "Error syncing Spotify followed artists")
        }
    }

    /**
     * Pulls the user's Spotify-side liked tracks into the local DB so the heart
     * icon stays consistent across both services. Only runs when bidirectional
     * sync is enabled and Spotify is authenticated.
     *
     * Strategy:
     *  1. Page through all Spotify likes (capped to keep first-sync bounded).
     *  2. Resolve each Spotify track to a YouTube ID via [spotifyMapper] using
     *     bounded parallelism (4 concurrent searches) — cache hits are free,
     *     misses cost one YouTube search each.
     *  3. In a single Room transaction:
     *     a. Insert/update each resolved track with `liked = true`.
     *     b. Unlike local songs whose mapped `spotifyId` is no longer present
     *        in the remote set. To avoid wiping unmapped likes (Meld-only or
     *        not-yet-resolved tracks), the unlike branch only fires for songs
     *        with an existing [SpotifyMatchEntity] for their YouTube id.
     */
    private suspend fun executeSyncSpotifyLikedSongs() = withContext(Dispatchers.IO) {
        if (!spotifySyncLikes) {
            Timber.d("Skipping Spotify liked songs sync - bidirectional sync disabled")
            return@withContext
        }
        if (!SpotifyTokenManager.ensureAuthenticated()) {
            Timber.d("Skipping Spotify liked songs sync - Spotify not authenticated")
            return@withContext
        }

        updateState { copy(likedSongs = SyncStatus.Syncing, currentOperation = "Syncing Spotify liked songs") }

        try {
            // 1. Paginate through Spotify likes
            val pageSize = 50
            val maxPages = 60 // hard cap at 3000 tracks to bound first-sync cost
            val remoteTracks = mutableListOf<com.metrolist.spotify.models.SpotifyTrack>()
            var offset = 0
            var pages = 0
            var total = -1
            while (true) {
                val pageResult = com.metrolist.spotify.Spotify.likedSongs(limit = pageSize, offset = offset)
                val page = pageResult.getOrElse { e ->
                    Timber.e(e, "Failed fetching Spotify liked songs at offset $offset")
                    updateState { copy(likedSongs = SyncStatus.Error(e.message ?: "Unknown error")) }
                    return@withContext
                }
                remoteTracks += page.items.map { it.track }
                total = page.total
                offset += page.items.size
                pages++
                if (page.items.size < pageSize || offset >= total) break
                if (pages >= maxPages) {
                    Timber.w("Spotify likes sync truncated at $offset of $total")
                    break
                }
            }
            Timber.d("Spotify likes: fetched ${remoteTracks.size} of $total tracks")

            // 2. Resolve to YouTube via the mapper, in parallel but bounded so
            //    we don't flood YouTube search with hundreds of concurrent calls.
            data class Resolved(
                val track: com.metrolist.spotify.models.SpotifyTrack,
                val metadata: com.metrolist.music.models.MediaMetadata,
            )

            val sem = Semaphore(4)
            val resolved: List<Resolved> = coroutineScope {
                remoteTracks.map { track ->
                    async {
                        sem.withPermit {
                            try {
                                spotifyMapper.mapToYouTube(track)?.let { Resolved(track, it) }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Timber.w(e, "Failed mapping Spotify track ${track.id}")
                                null
                            }
                        }
                    }
                }.awaitAll().filterNotNull()
            }

            val resolvedSpotifyIds = resolved.map { it.track.id }.toSet()
            Timber.d("Spotify likes: resolved ${resolved.size}/${remoteTracks.size} to YouTube ids")

            // 3. Read local liked songs once, outside the transaction (Flow.first
            //    is suspending; the list is then immutable and safe to iterate
            //    inside the transaction).
            val localLiked = database.likedSongsByNameAsc().first()
            val resolvedYtIds = resolved.map { it.metadata.id }.toSet()
            val existingByYtId = database.getSongsByIds(resolvedYtIds.toList()).associateBy { it.id }

            val now = LocalDateTime.now()

            database.withTransaction {
                // 3a. Mark resolved tracks as liked (insert if missing, update otherwise)
                resolved.forEachIndexed { index, r ->
                    try {
                        val timestamp = now.minusSeconds(index.toLong())
                        val existing = existingByYtId[r.metadata.id]
                        if (existing == null) {
                            insert(r.metadata) { it.copy(liked = true, likedDate = timestamp) }
                        } else if (!existing.song.liked) {
                            update(existing.song.copy(liked = true, likedDate = timestamp))
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to apply Spotify like for ${r.track.id}")
                    }
                }

                // 3b. Unlike locally only when we have a confirmed Spotify mapping
                //     for the song *and* that mapping points to a track that is
                //     no longer liked on Spotify. Songs with no Spotify match are
                //     left alone — they may simply have never been resolved.
                localLiked.forEach { song ->
                    if (song.id in resolvedYtIds) return@forEach
                    val match = getSpotifyMatchByYouTubeId(song.id) ?: return@forEach
                    if (match.spotifyId !in resolvedSpotifyIds) {
                        try {
                            update(song.song.copy(liked = false, likedDate = null))
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to unlike song ${song.id} after Spotify removal")
                        }
                    }
                }
            }

            updateState { copy(likedSongs = SyncStatus.Completed) }
            Timber.d("Spotify likes sync completed: ${resolved.size} applied")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Error syncing Spotify liked songs")
            updateState { copy(likedSongs = SyncStatus.Error(e.message ?: "Unknown error")) }
        }
    }

    private suspend fun executeSyncPodcastSubscriptions() = withContext(Dispatchers.IO) {
        Timber.d("[PODCAST_SYNC] executeSyncPodcastSubscriptions() started")
        if (!isLoggedIn()) {
            Timber.w("[PODCAST_SYNC] Skipping syncPodcastSubscriptions - user not logged in")
            return@withContext
        }
        Timber.d("[PODCAST_SYNC] User is logged in, proceeding with sync")

        updateState { copy(currentOperation = "Syncing podcast subscriptions") }

        // Sync saved podcast shows (most common - saved via likePlaylist)
        withRetry {
            Timber.d("[PODCAST_SYNC] Calling YouTube.savedPodcastShows()")
            YouTube.savedPodcastShows()
        }.onSuccess { result ->
            Timber.d("[PODCAST_SYNC] savedPodcastShows succeeded, result isSuccess=${result.isSuccess}")
            result.onSuccess { remotePodcasts ->
                try {
                    Timber.d("[PODCAST_SYNC] Fetched ${remotePodcasts.size} saved podcast shows")

                    remotePodcasts.forEachIndexed { index, podcast ->
                        Timber.d("[PODCAST_SYNC] Remote podcast $index: id=${podcast.id}, title=${podcast.title}, author=${podcast.author?.name}")
                    }

                    // Server-first: YouTube Music is the source of truth
                    // Add/update podcasts from remote
                    remotePodcasts.forEach { podcast ->
                        try {
                            val dbPodcast = database.podcast(podcast.id).firstOrNull()
                            Timber.d("[PODCAST_SYNC] Processing remote podcast ${podcast.id}: exists in db=${dbPodcast != null}, isSubscribed=${dbPodcast?.bookmarkedAt != null}")

                            database.transaction {
                                if (dbPodcast == null) {
                                    // Only add truly new podcasts from server
                                    Timber.d("[PODCAST_SYNC] Inserting new podcast: ${podcast.id}")
                                    insert(
                                        PodcastEntity(
                                            id = podcast.id,
                                            title = podcast.title,
                                            author = podcast.author?.name,
                                            thumbnailUrl = podcast.thumbnail,
                                            channelId = podcast.channelId ?: podcast.author?.id,
                                            bookmarkedAt = LocalDateTime.now(),
                                        )
                                    )
                                } else if (dbPodcast.bookmarkedAt != null) {
                                    // Update metadata for already-saved podcasts, but don't re-bookmark
                                    // ones that user has removed locally (respect local state)
                                    Timber.d("[PODCAST_SYNC] Updating metadata for saved podcast: ${podcast.id}")
                                    update(
                                        dbPodcast.copy(
                                            title = podcast.title,
                                            author = podcast.author?.name,
                                            thumbnailUrl = podcast.thumbnail,
                                            channelId = podcast.channelId ?: podcast.author?.id ?: dbPodcast.channelId,
                                            lastUpdateTime = LocalDateTime.now(),
                                        )
                                    )
                                } else {
                                    // Podcast exists locally but is unbookmarked - user removed it
                                    // Don't re-add; the server removal is likely still pending
                                    Timber.d("[PODCAST_SYNC] Skipping unbookmarked podcast: ${podcast.id}")
                                }
                            }
                            delay(DB_OPERATION_DELAY_MS)
                        } catch (e: Exception) {
                            Timber.e(e, "[PODCAST_SYNC] Failed to process podcast: ${podcast.id}")
                        }
                    }

                    Timber.d("[PODCAST_SYNC] Synced ${remotePodcasts.size} saved podcast shows successfully")
                } catch (e: Exception) {
                    Timber.e(e, "[PODCAST_SYNC] Error processing saved podcast shows")
                }
            }.onFailure { e ->
                Timber.e(e, "[PODCAST_SYNC] Failed to fetch saved podcast shows from YouTube")
            }
        }.onFailure { e ->
            Timber.e(e, "[PODCAST_SYNC] Failed to sync saved podcast shows after retries")
        }

        // Also sync subscribed podcast channels (subscribed via subscribeChannel API)
        withRetry {
            Timber.d("[PODCAST_SYNC] Calling YouTube.libraryPodcastChannels()")
            YouTube.libraryPodcastChannels()
        }.onSuccess { result ->
            Timber.d("[PODCAST_SYNC] libraryPodcastChannels succeeded, result isSuccess=${result.isSuccess}")
            result.onSuccess { page ->
                try {
                    val remotePodcasts = page.items.filterIsInstance<PodcastItem>()
                    Timber.d("[PODCAST_SYNC] Fetched ${remotePodcasts.size} subscribed podcast channels")

                    // Add/update podcasts from remote channels
                    remotePodcasts.forEach { podcast ->
                        try {
                            val dbPodcast = database.podcast(podcast.id).firstOrNull()
                            Timber.d("[PODCAST_SYNC] Processing subscribed channel ${podcast.id}: exists in db=${dbPodcast != null}")

                            database.transaction {
                                if (dbPodcast == null) {
                                    // Only add truly new podcasts from server
                                    Timber.d("[PODCAST_SYNC] Inserting new subscribed channel: ${podcast.id}")
                                    insert(
                                        PodcastEntity(
                                            id = podcast.id,
                                            title = podcast.title,
                                            author = podcast.author?.name,
                                            thumbnailUrl = podcast.thumbnail,
                                            channelId = podcast.channelId ?: podcast.author?.id,
                                            bookmarkedAt = LocalDateTime.now(),
                                        )
                                    )
                                } else if (dbPodcast.bookmarkedAt != null) {
                                    // Update metadata for already-saved podcasts
                                    Timber.d("[PODCAST_SYNC] Updating metadata for subscribed channel: ${podcast.id}")
                                    update(
                                        dbPodcast.copy(
                                            title = podcast.title,
                                            author = podcast.author?.name,
                                            thumbnailUrl = podcast.thumbnail,
                                            channelId = podcast.channelId ?: podcast.author?.id ?: dbPodcast.channelId,
                                            lastUpdateTime = LocalDateTime.now(),
                                        )
                                    )
                                } else {
                                    // Podcast exists locally but is unbookmarked - don't re-add
                                    Timber.d("[PODCAST_SYNC] Skipping unbookmarked channel: ${podcast.id}")
                                }
                            }
                            delay(DB_OPERATION_DELAY_MS)
                        } catch (e: Exception) {
                            Timber.e(e, "[PODCAST_SYNC] Failed to process subscribed channel: ${podcast.id}")
                        }
                    }

                    Timber.d("[PODCAST_SYNC] Synced ${remotePodcasts.size} subscribed podcast channels successfully")
                } catch (e: Exception) {
                    Timber.e(e, "[PODCAST_SYNC] Error processing subscribed podcast channels")
                }
            }.onFailure { e ->
                Timber.e(e, "[PODCAST_SYNC] Failed to fetch subscribed podcast channels from YouTube")
            }
        }.onFailure { e ->
            Timber.e(e, "[PODCAST_SYNC] Failed to sync subscribed podcast channels after retries")
        }

        // Cleanup: Remove local podcasts that are no longer subscribed on YouTube Music
        try {
            val allRemoteIds = mutableSetOf<String>()

            // Collect all remote podcast IDs
            YouTube.savedPodcastShows().onSuccess { podcasts ->
                allRemoteIds.addAll(podcasts.map { it.id })
            }
            YouTube.libraryPodcastChannels().onSuccess { page ->
                allRemoteIds.addAll(page.items.filterIsInstance<PodcastItem>().map { it.id })
            }

            if (allRemoteIds.isNotEmpty()) {
                val localPodcasts = database.subscribedPodcasts().first()
                val localOnlyPodcasts = localPodcasts.filterNot { it.id in allRemoteIds }
                Timber.d("[PODCAST_SYNC] Cleanup: removing ${localOnlyPodcasts.size} podcasts not on YTM")

                localOnlyPodcasts.forEach { podcast ->
                    try {
                        // Remove subscription (set bookmarkedAt to null)
                        database.transaction {
                            update(podcast.copy(bookmarkedAt = null))
                        }
                        Timber.d("[PODCAST_SYNC] Unsubscribed from local podcast: ${podcast.id}")
                    } catch (e: Exception) {
                        Timber.e(e, "[PODCAST_SYNC] Failed to cleanup podcast: ${podcast.id}")
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "[PODCAST_SYNC] Error during cleanup")
        }
    }

    private suspend fun executeSyncEpisodesForLater() = withContext(Dispatchers.IO) {
        Timber.d("[EPISODES_SYNC] executeSyncEpisodesForLater() started")
        if (!isLoggedIn()) {
            Timber.w("[EPISODES_SYNC] Skipping syncEpisodesForLater - user not logged in")
            return@withContext
        }
        Timber.d("[EPISODES_SYNC] User is logged in, proceeding with sync")

        updateState { copy(currentOperation = "Syncing episodes for later") }

        withRetry {
            Timber.d("[EPISODES_SYNC] Calling YouTube.episodesForLater() (VLSE playlist)")
            YouTube.episodesForLater()
        }.onSuccess { result ->
            result.onSuccess { remoteEpisodes ->
                try {
                    Timber.d("[EPISODES_SYNC] Fetched ${remoteEpisodes.size} episodes from VLSE playlist")
                    val remoteIds = remoteEpisodes.map { it.id }.toSet()

                    // Get local episodes that are saved (for cleanup later)
                    val localSavedEpisodes = database.podcastEpisodesByCreateDateAsc().first()
                        .filter { it.song.inLibrary != null }
                    Timber.d("[EPISODES_SYNC] Local saved episodes: ${localSavedEpisodes.size}")

                    // Server-first: YouTube Music is the source of truth
                    // Sync remote episodes to local database
                    remoteEpisodes.forEach { episode ->
                        try {
                            val dbSong = database.song(episode.id).firstOrNull()
                            Timber.d("[EPISODES_SYNC] Processing remote episode ${episode.id}: exists in db=${dbSong != null}")

                            database.transaction {
                                if (dbSong == null) {
                                    Timber.d("[EPISODES_SYNC] Inserting new episode: ${episode.id}")
                                    val mediaMetadata = episode.toMediaMetadata()
                                    insert(mediaMetadata.toSongEntity().copy(
                                        inLibrary = LocalDateTime.now(),
                                        isEpisode = true
                                    ))
                                    // Insert artists
                                    mediaMetadata.artists.forEach { artist ->
                                        artist.id?.let { artistId ->
                                            insert(
                                                ArtistEntity(
                                                    id = artistId,
                                                    name = artist.name,
                                                )
                                            )
                                        }
                                    }
                                } else if (!dbSong.song.isEpisode || dbSong.song.inLibrary == null) {
                                    Timber.d("[EPISODES_SYNC] Updating existing song to episode in library: ${episode.id}")
                                    update(
                                        dbSong.song.copy(
                                            isEpisode = true,
                                            inLibrary = dbSong.song.inLibrary ?: LocalDateTime.now(),
                                            libraryAddToken = episode.libraryAddToken ?: dbSong.song.libraryAddToken,
                                            libraryRemoveToken = episode.libraryRemoveToken ?: dbSong.song.libraryRemoveToken,
                                        )
                                    )
                                } else {
                                    // Update tokens if we got new ones
                                    if (episode.libraryAddToken != null || episode.libraryRemoveToken != null) {
                                        update(
                                            dbSong.song.copy(
                                                libraryAddToken = episode.libraryAddToken ?: dbSong.song.libraryAddToken,
                                                libraryRemoveToken = episode.libraryRemoveToken ?: dbSong.song.libraryRemoveToken,
                                            )
                                        )
                                    }
                                    Timber.d("[EPISODES_SYNC] Episode already in library: ${episode.id}")
                                }
                                // Store setVideoId for removal capability
                                episode.setVideoId?.let { svid ->
                                    Timber.d("[EPISODES_SYNC] Storing setVideoId for ${episode.id}: $svid")
                                    insert(SetVideoIdEntity(videoId = episode.id, setVideoId = svid))
                                }
                            }
                            delay(DB_OPERATION_DELAY_MS)
                        } catch (e: Exception) {
                            Timber.e(e, "[EPISODES_SYNC] Failed to process episode: ${episode.id}")
                        }
                    }

                    // Cleanup: Remove local episodes that are no longer in Episodes for Later
                    val localToRemove = localSavedEpisodes.filterNot { it.id in remoteIds }
                    Timber.d("[EPISODES_SYNC] Cleanup: removing ${localToRemove.size} episodes not in VLSE")
                    localToRemove.forEach { song ->
                        try {
                            database.transaction {
                                update(song.song.copy(inLibrary = null))
                            }
                            Timber.d("[EPISODES_SYNC] Removed episode from library: ${song.id}")
                        } catch (e: Exception) {
                            Timber.e(e, "[EPISODES_SYNC] Failed to cleanup episode: ${song.id}")
                        }
                    }

                    Timber.d("[EPISODES_SYNC] Synced ${remoteEpisodes.size} episodes successfully")
                } catch (e: Exception) {
                    Timber.e(e, "[EPISODES_SYNC] Error processing episodes")
                }
            }.onFailure { e ->
                Timber.e(e, "[EPISODES_SYNC] Failed to fetch episodes from YouTube")
            }
        }.onFailure { e ->
            Timber.e(e, "[EPISODES_SYNC] Failed to sync episodes after retries")
        }
    }

    private suspend fun executeSyncSavedPlaylists() = withContext(Dispatchers.IO) {
        if (!isLoggedIn()) {
            Timber.w("Skipping syncSavedPlaylists - user not logged in")
            return@withContext
        }

        updateState { copy(playlists = SyncStatus.Syncing, currentOperation = "Syncing saved playlists") }

        withRetry {
            YouTube.library("FEmusic_liked_playlists").completed()
        }.onSuccess { result ->
            result.onSuccess { page ->
                try {
                    val remotePlaylists = page.items.filterIsInstance<PlaylistItem>()
                        .filterNot { it.id == "LM" || it.id == "SE" }
                        .reversed()
                    val remoteIds = remotePlaylists.map { it.id }.toSet()

                    val localPlaylists = database.playlistsByNameAsc().first()
                    localPlaylists.filterNot { it.playlist.browseId in remoteIds }
                        .filterNot { it.playlist.browseId == null }
                        .forEach { playlist ->
                            try {
                                database.update(playlist.playlist.localToggleLike())
                                delay(DB_OPERATION_DELAY_MS)
                            } catch (e: Exception) {
                                Timber.e(e, "Failed to update playlist: ${playlist.id}")
                            }
                        }

                    for (playlist in remotePlaylists) {
                        try {
                            var playlistEntity = localPlaylists.find { it.playlist.browseId == playlist.id }?.playlist

                            if (playlistEntity == null) {
                                playlistEntity = PlaylistEntity(
                                    name = playlist.title,
                                    browseId = playlist.id,
                                    thumbnailUrl = playlist.thumbnail,
                                    isEditable = playlist.isEditable,
                                    bookmarkedAt = LocalDateTime.now(),
                                    remoteSongCount = playlist.songCountText?.let {
                                        Regex("""\d+""").find(it)?.value?.toIntOrNull()
                                    },
                                    playEndpointParams = playlist.playEndpoint?.params,
                                    shuffleEndpointParams = playlist.shuffleEndpoint?.params,
                                    radioEndpointParams = playlist.radioEndpoint?.params
                                )
                                database.insert(playlistEntity)
                                Timber.d("syncSavedPlaylists: Created new playlist ${playlist.title} (${playlist.id})")
                            } else {
                                database.update(playlistEntity, playlist)
                                Timber.d("syncSavedPlaylists: Updated existing playlist ${playlist.title} (${playlist.id})")
                            }

                            if (!isPlaylistBeingModified(playlistEntity.id)) {
                                executeSyncPlaylist(playlist.id, playlistEntity.id)
                                delay(DB_OPERATION_DELAY_MS)
                            } else {
                                Timber.d("Skipping playlist ${playlist.title} — remove in progress")
                            }
                            delay(DB_OPERATION_DELAY_MS)
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to sync playlist ${playlist.title}")
                        }
                    }

                    updateState { copy(playlists = SyncStatus.Completed) }
                    Timber.d("Synced ${remotePlaylists.size} saved playlists")
                } catch (e: Exception) {
                    Timber.e(e, "Error processing saved playlists")
                    updateState { copy(playlists = SyncStatus.Error(e.message ?: "Unknown error")) }
                }
            }.onFailure { e ->
                Timber.e(e, "syncSavedPlaylists: Failed to fetch playlists from YouTube")
                updateState { copy(playlists = SyncStatus.Error(e.message ?: "Unknown error")) }
            }
        }.onFailure { e ->
            Timber.e(e, "Failed to sync saved playlists after retries")
            updateState { copy(playlists = SyncStatus.Error(e.message ?: "Unknown error")) }
        }
    }

    private suspend fun executeSyncAutoSyncPlaylists() = withContext(Dispatchers.IO) {
        if (!isLoggedIn()) {
            Timber.w("Skipping syncAutoSyncPlaylists - user not logged in")
            return@withContext
        }

        try {
            val autoSyncPlaylists = database.playlistsByNameAsc().first()
                .filter { it.playlist.isAutoSync && it.playlist.browseId != null }

            Timber.d("syncAutoSyncPlaylists: Found ${autoSyncPlaylists.size} playlists to sync")

            autoSyncPlaylists.forEach { playlist ->
                // Skip playlists with a pending remove operation
                if (isPlaylistBeingModified(playlist.playlist.id)) {
                    Timber.d("Skipping playlist ${playlist.playlist.name} — remove in progress")
                    return@forEach
                }
                try {
                    executeSyncPlaylist(playlist.playlist.browseId!!, playlist.playlist.id)
                    delay(DB_OPERATION_DELAY_MS)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to sync playlist ${playlist.playlist.name}")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error syncing auto-sync playlists")
        }
    }

    private suspend fun executeSyncPlaylist(browseId: String, playlistId: String) = withContext(Dispatchers.IO) {
        Timber.d("syncPlaylist: Starting sync for browseId=$browseId, playlistId=$playlistId")

        withRetry {
            YouTube.playlist(browseId).completed()
        }.onSuccess { result ->
            result.onSuccess { page ->
                try {
                    val songs = page.songs.map(SongItem::toMediaMetadata)
                    Timber.d("syncPlaylist: Fetched ${songs.size} songs from remote")

                    if (songs.isEmpty()) {
                        Timber.w("syncPlaylist: Remote playlist is empty, skipping sync")
                        return@onSuccess
                    }

                    val remoteIds = songs.map { it.id }
                    val localIds = database.playlistSongs(playlistId).first()
                        .sortedBy { it.map.position }
                        .map { it.song.id }

                    if (remoteIds == localIds) {
                        Timber.d("syncPlaylist: Local and remote are in sync, no changes needed")
                        return@onSuccess
                    }

                    Timber.d("syncPlaylist: Updating local playlist (remote: ${remoteIds.size}, local: ${localIds.size})")

                    val localSongsBeforeSync = database.playlistSongs(playlistId).first()
                    val downloadedSongIds = localSongsBeforeSync
                        .filter { it.song.song.isDownloaded || it.song.song.dateDownload != null }
                        .map { it.song.id }
                        .toSet()

                    database.withTransaction {
                        database.clearPlaylist(playlistId)
                        songs.forEachIndexed { idx, song ->
                            if (database.getSongByIdBlocking(song.id) == null) {
                                database.insert(song)
                            }
                            if (database.songExistsBlocking(song.id)) {
                                database.insert(
                                    PlaylistSongMap(
                                        songId = song.id,
                                        playlistId = playlistId,
                                        position = idx,
                                        setVideoId = song.setVideoId,
                                    ),
                                )
                            } else {
                                Timber.w("syncPlaylist: skipping song ${song.id} — failed to insert into database")
                            }
                        }

                        downloadedSongIds.forEach { songId ->
                            if (songId !in remoteIds) {
                                val existingSong = database.song(songId).firstOrNull()
                                if (existingSong != null) {
                                    val maxPosition = database.playlistSongs(playlistId).first()
                                        .maxOfOrNull { it.map.position } ?: -1
                                    database.insert(
                                        PlaylistSongMap(
                                            songId = songId,
                                            playlistId = playlistId,
                                            position = maxPosition + 1
                                        )
                                    )
                                    Timber.d("syncPlaylist: Preserved downloaded song $songId in playlist")
                                }
                            }
                        }
                    }
                    Timber.d("syncPlaylist: Successfully synced playlist")
                } catch (e: Exception) {
                    Timber.e(e, "Error processing playlist sync")
                }
            }.onFailure { e ->
                Timber.e(e, "syncPlaylist: Failed to fetch playlist from YouTube")
            }
        }.onFailure { e ->
            Timber.e(e, "syncPlaylist: Failed after retries")
        }
    }

    private suspend fun executeCleanupDuplicatePlaylists() = withContext(Dispatchers.IO) {
        try {
            val allPlaylists = database.playlistsByNameAsc().first()
            val browseIdGroups = allPlaylists
                .filter { it.playlist.browseId != null }
                .groupBy { it.playlist.browseId }

            for ((browseId, playlists) in browseIdGroups) {
                if (playlists.size > 1) {
                    Timber.w("Found ${playlists.size} duplicate playlists for browseId: $browseId")
                    val toKeep = playlists.maxByOrNull { it.songCount } ?: playlists.first()

                    playlists.filter { it.id != toKeep.id }.forEach { duplicate ->
                        try {
                            Timber.d("Removing duplicate playlist: ${duplicate.playlist.name} (${duplicate.id})")
                            database.clearPlaylist(duplicate.id)
                            database.delete(duplicate.playlist)
                            delay(DB_OPERATION_DELAY_MS)
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to remove duplicate playlist: ${duplicate.id}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error cleaning up duplicate playlists")
        }
    }

    private suspend fun executeClearAllSyncedContent() = withContext(Dispatchers.IO) {
        Timber.d("clearAllSyncedContent: Starting cleanup")

        updateState { copy(overallStatus = SyncStatus.Syncing, currentOperation = "Clearing synced content") }

        try {
            database.withTransaction {
                // Clear liked songs
                val likedSongs = database.likedSongsByNameAsc().first()
                likedSongs.forEach {
                    database.update(it.song.copy(liked = false, likedDate = null))
                }

                // Clear library songs
                val librarySongs = database.songsByNameAsc().first()
                librarySongs.forEach {
                    if (it.song.inLibrary != null) {
                        database.update(it.song.copy(inLibrary = null))
                    }
                }

                // Clear liked albums
                val likedAlbums = database.albumsLikedByNameAsc().first()
                likedAlbums.forEach {
                    database.update(it.album.copy(bookmarkedAt = null))
                }

                // Clear subscribed artists
                val subscribedArtists = database.artistsBookmarkedByNameAsc().first()
                subscribedArtists.forEach {
                    database.update(it.artist.copy(bookmarkedAt = null))
                }

                // Delete synced playlists
                val savedPlaylists = database.playlistsByNameAsc().first()
                savedPlaylists.forEach {
                    if (it.playlist.browseId != null) {
                        database.clearPlaylist(it.playlist.id)
                        database.delete(it.playlist)
                    }
                }

                // Clear uploaded songs
                val uploadedSongs = database.uploadedSongsByNameAsc().first()
                uploadedSongs.forEach {
                    database.update(it.song.copy(isUploaded = false, uploadEntityId = null))
                }

                // Clear uploaded albums
                val uploadedAlbums = database.albumsUploadedByCreateDateAsc().first()
                uploadedAlbums.forEach {
                    database.update(it.album.copy(isUploaded = false))
                }
            }

            // Reset sync timestamp
            context.dataStore.edit { settings ->
                settings[LastFullSyncKey] = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC)
            }

            updateState { copy(overallStatus = SyncStatus.Completed, currentOperation = "") }
            Timber.d("clearAllSyncedContent: Cleanup completed successfully")
        } catch (e: Exception) {
            Timber.e(e, "clearAllSyncedContent: Error during cleanup")
            updateState { copy(overallStatus = SyncStatus.Error(e.message ?: "Unknown error"), currentOperation = "") }
        }
    }

    private suspend fun executeClearPodcastData() = withContext(Dispatchers.IO) {
        Timber.d("[PODCAST_CLEAR] Starting podcast data cleanup")

        updateState { copy(overallStatus = SyncStatus.Syncing, currentOperation = "Clearing podcast data") }

        try {
            database.withTransaction {
                // Clear subscribed podcasts
                val subscribedPodcasts = database.subscribedPodcasts().first()
                Timber.d("[PODCAST_CLEAR] Clearing ${subscribedPodcasts.size} subscribed podcasts")
                subscribedPodcasts.forEach { podcast ->
                    database.update(podcast.copy(bookmarkedAt = null))
                }

                // Clear episode library status (inLibrary) for episodes
                val savedEpisodes = database.podcastEpisodesByCreateDateAsc().first()
                    .filter { it.song.inLibrary != null }
                Timber.d("[PODCAST_CLEAR] Clearing ${savedEpisodes.size} saved episodes")
                savedEpisodes.forEach { song ->
                    database.update(song.song.copy(inLibrary = null))
                }
            }

            updateState { copy(overallStatus = SyncStatus.Completed, currentOperation = "") }
            Timber.d("[PODCAST_CLEAR] Podcast data cleared successfully")
        } catch (e: Exception) {
            Timber.e(e, "[PODCAST_CLEAR] Error during cleanup")
            updateState { copy(overallStatus = SyncStatus.Error(e.message ?: "Unknown error"), currentOperation = "") }
        }
    }

    suspend fun removeFromPlaylistAndAwaitSync(
        browseId: String,
        songId: String,
        setVideoId: String,
        playlistId: String
    ) {
        Timber.d("removeFromPlaylistAndAwaitSync: START browseId=$browseId songId=$songId setVideoId=$setVideoId")

        val hasPendingAdd = pendingYouTubeAdds[browseId]?.contains(songId) == true
        Timber.d("removeFromPlaylistAndAwaitSync: hasPendingAdd=$hasPendingAdd")
        if (hasPendingAdd) {
            Timber.d("removeFromPlaylistAndAwaitSync: Deferring remove")
            pendingRemovals.getOrPut(browseId) {
                java.util.concurrent.ConcurrentHashMap.newKeySet()
            }.add(Triple(songId, setVideoId, playlistId))
            return
        }

        markPlaylistModifying(playlistId)
        try {
            withContext(Dispatchers.IO) {
                Timber.d("removeFromPlaylistAndAwaitSync: Calling YouTube.removeFromPlaylist")
                YouTube.removeFromPlaylist(browseId, songId, setVideoId)
                Timber.d("removeFromPlaylistAndAwaitSync: YouTube.removeFromPlaylist returned")

                for (attempt in 0 until 10) {
                    delay(3_000L)
                    val stillPresent = runCatching {
                        YouTube.playlist(browseId).completed().getOrThrow()
                    }.getOrNull()?.songs?.any { it.id == songId } ?: true
                    Timber.d("removeFromPlaylistAndAwaitSync: Poll ${attempt + 1}/10 stillPresent=$stillPresent")

                    if (!stillPresent) return@withContext
                }
                Timber.w("removeFromPlaylistAndAwaitSync: Timeout reached")
            }
        } finally {
            unmarkPlaylistModifying(playlistId)
            Timber.d("removeFromPlaylistAndAwaitSync: END")
        }
    }

    fun registerPendingAdd(browseId: String, songId: String) {
        pendingYouTubeAdds.getOrPut(browseId) {
            ConcurrentHashMap.newKeySet()
        }.add(songId)
        Timber.d("registerPendingAdd: browseId=$browseId songId=$songId")
    }

    fun unregisterPendingAdd(browseId: String, songId: String) {
        syncScope.launch {
            var deferredRemoval: Triple<String, String, String>? = null
            try {
                withContext(Dispatchers.IO) {
                    for (attempt in 0 until 10) {
                        delay(3_000L)
                        val songPresent = runCatching {
                            YouTube.playlist(browseId).completed().getOrThrow()
                        }.getOrNull()?.songs?.any { it.id == songId } ?: false

                        Timber.d("unregisterPendingAdd: Waiting for YouTube to confirm add, attempt ${attempt + 1}/10, found=$songPresent")

                        if (songPresent) {
                            Timber.d("unregisterPendingAdd: Add confirmed on YouTube for songId=$songId")
                            break
                        }
                    }

                    pendingYouTubeAdds[browseId]?.remove(songId)
                    Timber.d("unregisterPendingAdd: Unregistered songId=$songId")

                    deferredRemoval = pendingRemovals[browseId]?.find { it.first == songId }
                    if (deferredRemoval != null) {
                        pendingRemovals[browseId]?.remove(deferredRemoval)
                        Timber.d("unregisterPendingAdd: Captured deferred remove for songId=$songId, routing through removeFromPlaylistAndAwaitSync")
                    }
                }

                deferredRemoval?.let { (sId, setVideoId, playlistId) ->
                    removeFromPlaylistAndAwaitSync(browseId, sId, setVideoId, playlistId)
                }
            } catch (e: Exception) {
                Timber.e(e, "unregisterPendingAdd: Error processing deferred remove for songId=$songId")
            }
        }
    }

    fun scheduleRemoveFromPlaylist(
        browseId: String,
        songId: String,
        playlistId: String,
        getSetVideoId: suspend () -> String?
    ) {
        markPlaylistModifying(playlistId)
        Thread {
            runBlocking {
                try {
                    var setVideoId = getSetVideoId()

                    // setVideoId is only written to DB after first sync.
                    // If not available locally, fetch it directly from YouTube.
                    if (setVideoId == null) {
                        Timber.w("scheduleRemoveFromPlaylist: setVideoId not in DB, fetching from YouTube")
                        setVideoId = runCatching {
                            YouTube.playlist(browseId).completed().getOrThrow()
                                .songs.find { it.id == songId }?.setVideoId
                        }.getOrNull()
                    }

                    if (setVideoId == null) {
                        Timber.w("scheduleRemoveFromPlaylist: setVideoId not found on YouTube either, skipping remove for songId=$songId")
                        return@runBlocking
                    }

                    removeFromPlaylistAndAwaitSync(browseId, songId, setVideoId, playlistId)
                } finally {
                    unmarkPlaylistModifying(playlistId)
                }
            }
        }.start()
    }

    fun cancelAllSyncs() {
        processingJob?.cancel()
        startProcessingQueue()
        updateState { SyncState() }
    }
}
