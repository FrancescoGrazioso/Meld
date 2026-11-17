package com.meld.spotify

import com.meld.db.MeldDatabase
import com.meld.db.SpotifyLikeDao
import com.meld.db.SpotifyMatchDao
import com.meld.db.buildMeldDatabase
import com.meld.spotify.library.SessionSpotifyLibrary
import com.meld.spotify.library.SpotifyInLibrary
import com.meld.spotify.library.SpotifyLibrary
import com.meld.spotify.library.SpotifyLibraryStore
import com.meld.spotify.likes.SessionSpotifyLikes
import com.meld.spotify.likes.SongLikeStateLikes
import com.meld.spotify.likes.SpotifyLikeSync
import com.meld.spotify.match.SearchScope
import com.meld.spotify.playback.SpotifyPlayback
import com.meld.spotify.playback.SpotifyQueuePlayback
import com.meld.spotify.playback.SpotifyQueueTarget
import com.meld.spotify.playback.SpotifyTrackResolver
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.network.createHttpClient
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.youtube.logging.YtLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
 * platform drop Spotify clients it keeps alive outside the session owner.
 */
fun meldModule(
    queueTarget: Scope.(CoroutineScope) -> SpotifyQueueTarget,
    onLogout: () -> Unit = {},
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
            )
        }
        single<SpotifyLibrary> { SessionSpotifyLibrary(get()) }
        single { SpotifyLibraryStore(get(), get(), CoroutineScope(SupervisorJob() + Dispatchers.Default)) }
        single { SpotifyInLibrary(CoroutineScope(SupervisorJob() + Dispatchers.Default), get(), get()) }
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
            )
        }
        // The like sync runs from app start on every platform; it is built off the main thread, as it opens the session and the database.
        single(named("meldLikeSyncStart"), createdAtStart = true) {
            CoroutineScope(Dispatchers.Default).launch { get<SpotifyLikeSync>().start() }
        }
        // Created on first use, so nothing here touches the player until a Spotify track list is opened.
        // The feeder and its target share one main-thread scope: that is what keeps their queue edits in order.
        single {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            SpotifyQueuePlayback(get(), queueTarget(scope), scope)
        } bind SpotifyPlayback::class
    }

/** Where upstream's playlist import saves the shared session instead of writing the setting itself. */
fun saveSpotifyGuestSession(value: String) = KoinPlatform.getKoin().get<SpotifySessionOwner>().saveFromGuest(value)
