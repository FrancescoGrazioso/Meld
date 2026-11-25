package com.meld.spotify

import androidx.compose.runtime.snapshotFlow
import com.meld.db.MeldDatabase
import com.meld.db.SpotifyLikeDao
import com.meld.db.SpotifyMatchDao
import com.meld.db.buildMeldDatabase
import com.meld.migration.adoptLegacySpotifySession
import com.meld.spotify.download.SongDownloads
import com.meld.spotify.download.SpotifyDownloads
import com.meld.spotify.download.SpotifyKeptDownloads
import com.meld.spotify.library.SessionSpotifyLibrary
import com.meld.spotify.library.SpotifyCollectionActions
import com.meld.spotify.library.SessionSpotifyPlaylists
import com.meld.spotify.library.SpotifyInHome
import com.meld.spotify.library.SpotifyInLibrary
import com.meld.spotify.library.SpotifyInSearch
import com.meld.spotify.library.SpotifyLibrary
import com.meld.spotify.library.SpotifyLibraryStore
import com.meld.spotify.library.SpotifyPlaylistEditor
import com.meld.spotify.library.SpotifyPlaylistPicker
import com.meld.spotify.library.SpotifyPlaylistRemote
import com.meld.spotify.likes.SessionSpotifyLikes
import com.meld.spotify.likes.SongLikeStateLikes
import com.meld.spotify.likes.SpotifyLikeSync
import com.meld.spotify.likes.SpotifyLikedOrder
import com.meld.spotify.match.SearchScope
import com.meld.spotify.playback.ManualMatchBackup
import com.meld.spotify.playback.SpotifyPlayback
import com.meld.spotify.playback.SpotifyQueuePlayback
import com.meld.spotify.playback.SpotifyQueueTarget
import com.meld.spotify.playback.SpotifyTrackResolver
import com.meld.spotify.playback.SpotifyVersionPicker
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.network.createHttpClient
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.youtube.logging.YtLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.core.scope.Scope
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.mp.KoinPlatform
import kotlin.time.Clock

/**
 * Everything Meld registers, the same on every platform. [queueTarget] is the platform player behind a
 * [com.meld.spotify.playback.PlayerQueueTarget], built on the scope the feeder runs on; [onLogout] lets a
 * platform drop Spotify clients it keeps alive outside the session owner. [songDownloads] is the platform's own
 * download queue; without it Spotify tracks cannot be downloaded there. [downloadedSongIds] are the songs it holds.
 */
fun meldModule(
    queueTarget: Scope.(CoroutineScope) -> SpotifyQueueTarget,
    onLogout: () -> Unit = {},
    songDownloads: (Scope.() -> SongDownloads)? = null,
    downloadedSongIds: (Scope.() -> Flow<Set<String>>)? = null,
): Module =
    module {
        single<MeldDatabase> { buildMeldDatabase() }
        single<SpotifyMatchDao> { get<MeldDatabase>().spotifyMatchDao() }
        single {
            val settings = Settings.shared
            SpotifySessionOwner(
                http = createHttpClient(),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                loadSession = { settings.spotifySession },
                saveSession = { settings.spotifySession = it },
                loadAccount = { settings.meldSpotifyAccount },
                saveAccount = { settings.meldSpotifyAccount = it },
                loadHashes = { settings.meldSpotifyHashes.ifBlank { null } },
                saveHashes = { settings.meldSpotifyHashes = it },
                onLogout = onLogout,
                logger = { YtLogger.d("MeldSpotify", it) },
            )
        } bind SpotifyConnection::class
        single {
            ManualMatchBackup(
                dao = get(),
                load = { Settings.shared.meldManualMatches },
                save = { Settings.shared.meldManualMatches = it },
                now = { Clock.System.now().toEpochMilliseconds() },
            )
        }
        single {
            val backup = get<ManualMatchBackup>()
            SpotifyTrackResolver(
                dao = get(),
                search = { step ->
                    GlobalYouTubeRepository.instance
                        .searchFiltered(
                            step.text,
                            if (step.scope == SearchScope.SONGS) SearchFilter.SONG else SearchFilter.VIDEO,
                            allowOfflineFallback = false,
                        ).items
                        .filterIsInstance<SongItem>()
                },
                songsById = { GlobalYouTubeRepository.instance.getSongsMetadata(it) },
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                now = { Clock.System.now().toEpochMilliseconds() },
                onManualChange = backup::changed,
            )
        }
        single<SpotifyLibrary> { SessionSpotifyLibrary(get()) }
        single { SpotifyLibraryStore(get(), get(), CoroutineScope(SupervisorJob() + Dispatchers.Default)) }
        single { SpotifyInLibrary(CoroutineScope(SupervisorJob() + Dispatchers.Default), get(), get()) }
        single {
            val owner = get<SpotifySessionOwner>()
            SpotifyInHome(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                connection = owner,
                enabled = snapshotFlow { Settings.shared.meldSpotifyOnHome },
                home = { owner.call { home() } },
                newReleases = { owner.call { newReleases().items } },
            )
        }
        single {
            val owner = get<SpotifySessionOwner>()
            SpotifyInSearch(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                connection = owner,
                find = { query, offset -> owner.call { search(query, offset) } },
                playback = getOrNull(),
            )
        }
        single<SpotifyLikeDao> { get<MeldDatabase>().spotifyLikeDao() }
        single {
            val store = get<SpotifyLibraryStore>()
            SpotifyLikeSync(
                dao = get(),
                matches = get(),
                remote = SessionSpotifyLikes(get()),
                resolver = get(),
                local = SongLikeStateLikes,
                connection = get<SpotifySessionOwner>(),
                settings = { Settings.shared },
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                now = { Clock.System.now().toEpochMilliseconds() },
                onSpotifyChanged = store::likedSongsChanged,
                logger = { YtLogger.d("MeldSpotify", it) },
                onLikeDates = SpotifyLikedOrder::update,
            )
        }
        single<SpotifyPlaylistRemote> { SessionSpotifyPlaylists(get()) }
        // On its own scope, so an edit is sent even when the screen that made it has closed.
        single { SpotifyPlaylistEditor(get(), get(), get(), CoroutineScope(SupervisorJob() + Dispatchers.Default)) }
        single {
            SpotifyPlaylistPicker(
                store = get(),
                editor = get(),
                remote = get(),
                connection = get(),
                findOnSpotify = get<SpotifyLikeSync>()::spotifyTrackFor,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            )
        }
        // The like sync runs from app start on every platform; it is built off the main thread, as it opens the session and the database.
        single(named("meldLikeSyncStart"), createdAtStart = true) {
            CoroutineScope(Dispatchers.Default).launch { get<SpotifyLikeSync>().start() }
        }
        // After a restore the settings hold the user's own version choices and meld.db does not: bring them in.
        single(named("meldManualMatchesStart"), createdAtStart = true) {
            CoroutineScope(Dispatchers.Default).launch { get<ManualMatchBackup>().restore() }
        }
        // An update from the pre-KMP app: its cookie waits in the encrypted store until Spotify has answered for it.
        single(named("meldLegacySessionStart"), createdAtStart = true) {
            CoroutineScope(Dispatchers.Default).launch { adoptLegacySpotifySession(get(), Settings.shared) }
        }
        // Created on first use, so nothing here touches the player until a Spotify track list is opened.
        // The feeder and its target share one main-thread scope: that is what keeps their queue edits in order.
        if (songDownloads != null) single { SpotifyDownloads(get(), songDownloads(), downloadedSongIds?.invoke(this) ?: flowOf(emptySet())) }
        if (songDownloads != null) {
            single {
                SpotifyKeptDownloads(
                    store = get(),
                    downloads = get(),
                    connection = get(),
                    settings = { Settings.shared },
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                    logger = { YtLogger.d("MeldSpotify", it) },
                )
            }
            single(named("meldKeptDownloadsStart"), createdAtStart = true) { get<SpotifyKeptDownloads>().start() }
        }
        single { SpotifyVersionPicker(get(), CoroutineScope(SupervisorJob() + Dispatchers.Default)) }
        // The player is driven from the main thread, as from the screens.
        single { SpotifyCollectionActions(get(), getOrNull(), getOrNull(), CoroutineScope(SupervisorJob() + Dispatchers.Main)) }
        single {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            SpotifyQueuePlayback(get(), queueTarget(scope), scope)
        } bind SpotifyPlayback::class
    }

/** Where upstream's playlist import saves the shared session instead of writing the setting itself. */
fun saveSpotifyGuestSession(value: String) = KoinPlatform.getKoin().get<SpotifySessionOwner>().saveFromGuest(value)
