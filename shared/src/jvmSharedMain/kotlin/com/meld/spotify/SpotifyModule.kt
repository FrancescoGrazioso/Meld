package com.meld.spotify

import com.meld.spotify.library.SessionSpotifyLibrary
import com.meld.spotify.library.SpotifyLibrary
import com.meld.spotify.library.SpotifyLibraryStore
import com.meld.spotify.match.SearchScope
import com.meld.spotify.playback.SpotifyTrackResolver
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.network.sharedHttpEngine
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.youtube.logging.YtLogger
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.mp.KoinPlatform

/** [onLogout] lets a platform drop Spotify clients it keeps alive outside the session owner. */
fun spotifyModule(onLogout: () -> Unit = {}): Module =
    module {
        single {
            val settings = Settings.shared
            SpotifySessionOwner(
                engine = sharedHttpEngine,
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
    }

/** Where upstream's playlist import saves the shared session instead of writing the setting itself. */
fun saveSpotifyGuestSession(value: String) = KoinPlatform.getKoin().get<SpotifySessionOwner>().saveFromGuest(value)
