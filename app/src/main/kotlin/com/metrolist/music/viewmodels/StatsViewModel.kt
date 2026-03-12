/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.YouTube
import com.metrolist.music.constants.HideVideoSongsKey
import com.metrolist.music.constants.LastMonthlyMostPlaylistSyncKey
import com.metrolist.music.constants.LastWeeklyMostPlaylistSyncKey
import com.metrolist.music.constants.StatPeriod
import com.metrolist.music.constants.statToPeriod
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.PlaylistSongMap
import com.metrolist.music.ui.screens.OptionStats
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.reportException
import androidx.datastore.preferences.core.edit
import com.metrolist.music.R
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import javax.inject.Inject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StatsViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    val database: MusicDatabase,
) : ViewModel() {
    private val periodicMostPlaylistSyncMutex = Mutex()

    val selectedOption = MutableStateFlow(OptionStats.CONTINUOUS)
    val indexChips = MutableStateFlow(0)

    val mostPlayedSongsStats =
        combine(
            selectedOption,
            indexChips,
            context.dataStore.data.map { it[HideVideoSongsKey] ?: false }.distinctUntilChanged()
        ) { first, second, third -> Triple(first, second, third) }
            .flatMapLatest { (selection, t, hideVideoSongs) ->
                database
                    .mostPlayedSongsStats(
                        fromTimeStamp = statToPeriod(selection, t),
                        limit = -1,
                        toTimeStamp =
                        if (selection == OptionStats.CONTINUOUS || t == 0) {
                            LocalDateTime
                                .now()
                                .toInstant(
                                    ZoneOffset.UTC,
                                ).toEpochMilli()
                        } else {
                            statToPeriod(selection, t - 1)
                        },
                    ).map { songs ->
                        if (hideVideoSongs) songs.filter { !it.isVideo } else songs
                    }
            }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val mostPlayedSongs =
        combine(
            selectedOption,
            indexChips,
            context.dataStore.data.map { it[HideVideoSongsKey] ?: false }.distinctUntilChanged()
        ) { first, second, third -> Triple(first, second, third) }
            .flatMapLatest { (selection, t, hideVideoSongs) ->
                database
                    .mostPlayedSongs(
                        fromTimeStamp = statToPeriod(selection, t),
                        limit = -1,
                        toTimeStamp =
                        if (selection == OptionStats.CONTINUOUS || t == 0) {
                            LocalDateTime
                                .now()
                                .toInstant(
                                    ZoneOffset.UTC,
                                ).toEpochMilli()
                        } else {
                            statToPeriod(selection, t - 1)
                        },
                    ).map { songs ->
                        if (hideVideoSongs) songs.filter { !it.song.isVideo } else songs
                    }
            }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val mostPlayedArtists =
        combine(
            selectedOption,
            indexChips,
        ) { first, second -> Pair(first, second) }
            .flatMapLatest { (selection, t) ->
                database
                    .mostPlayedArtists(
                        statToPeriod(selection, t),
                        limit = -1,
                        toTimeStamp =
                        if (selection == OptionStats.CONTINUOUS || t == 0) {
                            LocalDateTime
                                .now()
                                .toInstant(
                                    ZoneOffset.UTC,
                                ).toEpochMilli()
                        } else {
                            statToPeriod(selection, t - 1)
                        },
                    ).map { artists ->
                        artists.filter { it.artist.isYouTubeArtist }
                    }
            }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val mostPlayedAlbums =
        combine(
            selectedOption,
            indexChips,
        ) { first, second -> Pair(first, second) }
            .flatMapLatest { (selection, t) ->
                database.mostPlayedAlbums(
                    statToPeriod(selection, t),
                    limit = -1,
                    toTimeStamp =
                    if (selection == OptionStats.CONTINUOUS || t == 0) {
                        LocalDateTime
                            .now()
                            .toInstant(
                                ZoneOffset.UTC,
                            ).toEpochMilli()
                    } else {
                        statToPeriod(selection, t - 1)
                    },
                )
            }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val firstEvent =
        database
            .firstEvent()
            .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val weeklyMostPlaylist =
        database
            .playlist(PlaylistEntity.WEEKLY_MOST_PLAYLIST_ID)
            .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val monthlyMostPlaylist =
        database
            .playlist(PlaylistEntity.MONTHLY_MOST_PLAYLIST_ID)
            .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val recapPlaylists =
        database
            .playlistsByNameAsc()
            .map { playlists ->
                playlists.filter { playlist ->
                    playlist.playlist.browseId != null &&
                        playlist.playlist.name.contains("recap", ignoreCase = true)
                }
            }.distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun syncMostPlaylistsIfNeeded() {
        viewModelScope.launch(Dispatchers.IO) {
            periodicMostPlaylistSyncMutex.withLock {
                val now = LocalDateTime.now(ZoneOffset.UTC)
                val nowEpochMillis = now.toInstant(ZoneOffset.UTC).toEpochMilli()
                val preferences = context.dataStore.data.first()
                val hideVideoSongs = preferences[HideVideoSongsKey] ?: false

                val weeklyPlaylistExists =
                    database.playlist(PlaylistEntity.WEEKLY_MOST_PLAYLIST_ID).first() != null
                val monthlyPlaylistExists =
                    database.playlist(PlaylistEntity.MONTHLY_MOST_PLAYLIST_ID).first() != null

                val shouldSyncWeekly =
                    !weeklyPlaylistExists || isWeeklySyncDue(
                        lastSyncMillis = preferences[LastWeeklyMostPlaylistSyncKey],
                        now = now,
                    )
                val shouldSyncMonthly =
                    !monthlyPlaylistExists || isMonthlySyncDue(
                        lastSyncMillis = preferences[LastMonthlyMostPlaylistSyncKey],
                        now = now,
                    )

                if (!shouldSyncWeekly && !shouldSyncMonthly) {
                    return@withLock
                }

                if (shouldSyncWeekly) {
                    syncMostPlaylist(
                        playlistId = PlaylistEntity.WEEKLY_MOST_PLAYLIST_ID,
                        playlistName = context.getString(R.string.weekly_most_playlist_name),
                        fromTimeStamp = StatPeriod.WEEK_1.toTimeMillis(),
                        hideVideoSongs = hideVideoSongs,
                        now = now,
                    )
                }

                if (shouldSyncMonthly) {
                    syncMostPlaylist(
                        playlistId = PlaylistEntity.MONTHLY_MOST_PLAYLIST_ID,
                        playlistName = context.getString(R.string.monthly_most_playlist_name),
                        fromTimeStamp = StatPeriod.MONTH_1.toTimeMillis(),
                        hideVideoSongs = hideVideoSongs,
                        now = now,
                    )
                }

                context.dataStore.edit { settings ->
                    if (shouldSyncWeekly) {
                        settings[LastWeeklyMostPlaylistSyncKey] = nowEpochMillis
                    }
                    if (shouldSyncMonthly) {
                        settings[LastMonthlyMostPlaylistSyncKey] = nowEpochMillis
                    }
                }
            }
        }
    }

    private fun isWeeklySyncDue(
        lastSyncMillis: Long?,
        now: LocalDateTime,
    ): Boolean {
        if (lastSyncMillis == null || lastSyncMillis <= 0L) return true

        val lastSyncAt = LocalDateTime.ofInstant(Instant.ofEpochMilli(lastSyncMillis), ZoneOffset.UTC)
        return !lastSyncAt.plusWeeks(1).isAfter(now)
    }

    private fun isMonthlySyncDue(
        lastSyncMillis: Long?,
        now: LocalDateTime,
    ): Boolean {
        if (lastSyncMillis == null || lastSyncMillis <= 0L) return true

        val lastSyncAt = LocalDateTime.ofInstant(Instant.ofEpochMilli(lastSyncMillis), ZoneOffset.UTC)
        return !lastSyncAt.plusMonths(1).isAfter(now)
    }

    private suspend fun syncMostPlaylist(
        playlistId: String,
        playlistName: String,
        fromTimeStamp: Long,
        hideVideoSongs: Boolean,
        now: LocalDateTime,
    ) {
        val songs =
            database
                .mostPlayedSongs(
                    fromTimeStamp = fromTimeStamp,
                    limit = -1,
                    toTimeStamp = now.toInstant(ZoneOffset.UTC).toEpochMilli(),
                ).first()
                .let { mostPlayedSongs ->
                    if (hideVideoSongs) {
                        mostPlayedSongs.filter { !it.song.isVideo }
                    } else {
                        mostPlayedSongs
                    }
                }.distinctBy { it.song.id }

        val existingPlaylist = database.playlist(playlistId).first()?.playlist
        val playlistEntity =
            existingPlaylist?.copy(
                name = playlistName,
                isEditable = true,
                bookmarkedAt = existingPlaylist.bookmarkedAt ?: now,
                lastUpdateTime = now,
            ) ?: PlaylistEntity(
                id = playlistId,
                name = playlistName,
                isEditable = true,
                bookmarkedAt = now,
                lastUpdateTime = now,
            )

        if (existingPlaylist == null) {
            database.insert(playlistEntity)
        } else {
            database.update(playlistEntity)
        }

        database.clearPlaylist(playlistId)

        songs.forEachIndexed { position, song ->
            database.insert(
                PlaylistSongMap(
                    songId = song.song.id,
                    playlistId = playlistId,
                    position = position,
                ),
            )
        }
    }

    init {
        viewModelScope.launch {
            mostPlayedArtists.collect { artists ->
                artists
                    .map { it.artist }
                    .filter {
                        it.thumbnailUrl == null || Duration.between(
                            it.lastUpdateTime,
                            LocalDateTime.now()
                        ) > Duration.ofDays(10)
                    }.forEach { artist ->
                        YouTube.artist(artist.id).onSuccess { artistPage ->
                            database.query {
                                update(artist, artistPage)
                            }
                        }
                    }
            }
        }
        viewModelScope.launch {
            mostPlayedAlbums.collect { albums ->
                albums
                    .filter {
                        it.album.songCount == 0
                    }.forEach { album ->
                        YouTube
                            .album(album.id)
                            .onSuccess { albumPage ->
                                database.query {
                                    update(album.album, albumPage, album.artists)
                                }
                            }.onFailure {
                                reportException(it)
                                if (it.message?.contains("NOT_FOUND") == true) {
                                    database.query {
                                        delete(album.album)
                                    }
                                }
                            }
                    }
            }
        }
    }
}
