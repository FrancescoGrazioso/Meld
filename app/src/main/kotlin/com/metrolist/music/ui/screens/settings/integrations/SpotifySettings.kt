/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings.integrations

import android.webkit.CookieManager
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.EnableQobuzKey
import com.metrolist.music.constants.EnableSpotifyKey
import com.metrolist.music.constants.QobuzAudioQuality
import com.metrolist.music.constants.QobuzAudioQualityKey
import com.metrolist.music.constants.QobuzBackend
import com.metrolist.music.constants.QobuzBackendKey
import com.metrolist.music.constants.QobuzCountryKey
import com.metrolist.music.constants.SpotifyAccessTokenKey
import com.metrolist.music.constants.SpotifySpDcKey
import com.metrolist.music.constants.SpotifySpKeyKey
import com.metrolist.music.constants.HideYtmLikedSongsKey
import com.metrolist.music.constants.SpotifySyncLikesKey
import com.metrolist.music.constants.SpotifyTokenExpiryKey
import com.metrolist.music.constants.SpotifyUserIdKey
import com.metrolist.music.constants.SpotifyUsernameKey
import com.metrolist.music.constants.SpotifyHomeOnlyKey
import com.metrolist.music.constants.UseSpotifyHomeKey
import com.metrolist.music.constants.UseSpotifySearchKey
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.playback.SpotifyMetadataRegistry
import com.metrolist.music.playback.SpotifyProfileCache
import com.metrolist.music.playback.SpotifyYouTubeMapper
import com.metrolist.music.qobuz.QobuzBackendHealthChecker
import com.metrolist.music.ui.component.EnumDialog
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.ListDialog
import com.metrolist.music.ui.component.PreferenceEntry
import com.metrolist.music.ui.component.PreferenceGroupTitle
import com.metrolist.music.ui.component.SwitchPreference
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference
import com.metrolist.spotify.Spotify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.LocalDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifySettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    var spotifyUsername by rememberPreference(SpotifyUsernameKey, "")
    var spotifyAccessToken by rememberPreference(SpotifyAccessTokenKey, "")
    var spotifySpDc by rememberPreference(SpotifySpDcKey, "")
    var spotifySpKey by rememberPreference(SpotifySpKeyKey, "")
    var spotifyTokenExpiry by rememberPreference(SpotifyTokenExpiryKey, 0L)
    var spotifyUserId by rememberPreference(SpotifyUserIdKey, "")

    val (enableSpotify, onEnableSpotifyChange) = rememberPreference(
        key = EnableSpotifyKey,
        defaultValue = false,
    )

    val isLoggedIn = remember(spotifyAccessToken, spotifySpDc) {
        spotifyAccessToken.isNotEmpty() && spotifySpDc.isNotEmpty()
    }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    Column(
        Modifier
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                ),
            )
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(
            Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top),
            ),
        )

        PreferenceGroupTitle(
            title = stringResource(R.string.account),
        )

        PreferenceEntry(
            title = {
                Text(
                    text = if (isLoggedIn) spotifyUsername else stringResource(R.string.spotify_not_logged_in),
                    modifier = Modifier.alpha(if (isLoggedIn) 1f else 0.5f),
                )
            },
            description = null,
            icon = { Icon(painterResource(R.drawable.spotify), null) },
            trailingContent = {
                if (isLoggedIn) {
                    OutlinedButton(onClick = {
                        spotifyAccessToken = ""
                        spotifySpDc = ""
                        spotifySpKey = ""
                        spotifyTokenExpiry = 0L
                        spotifyUsername = ""
                        spotifyUserId = ""
                        onEnableSpotifyChange(false)
                        CookieManager.getInstance().removeAllCookies(null)
                        // Clear all user-specific Spotify caches so a subsequent login
                        // (possibly a different account) never surfaces stale data.
                        SpotifyMetadataRegistry.clearAll()
                        coroutineScope.launch { SpotifyProfileCache.clearAllPersisted(context) }
                    }) {
                        Text(stringResource(R.string.action_logout))
                    }
                } else {
                    OutlinedButton(
                        onClick = { navController.navigate("settings/spotify/login") },
                    ) {
                        Text(stringResource(R.string.action_login))
                    }
                }
            },
        )

        PreferenceGroupTitle(
            title = stringResource(R.string.options),
        )

        SwitchPreference(
            title = { Text(stringResource(R.string.spotify_enable)) },
            description = stringResource(R.string.spotify_enable_description),
            checked = enableSpotify,
            onCheckedChange = onEnableSpotifyChange,
            isEnabled = isLoggedIn,
        )

        if (isLoggedIn && enableSpotify) {
            val (useSpotifySearch, onUseSpotifySearchChange) = rememberPreference(
                key = UseSpotifySearchKey,
                defaultValue = false,
            )
            val (useSpotifyHome, onUseSpotifyHomeChange) = rememberPreference(
                key = UseSpotifyHomeKey,
                defaultValue = false,
            )
            val (spotifyHomeOnly, onSpotifyHomeOnlyChange) = rememberPreference(
                key = SpotifyHomeOnlyKey,
                defaultValue = false,
            )

            SwitchPreference(
                title = { Text(stringResource(R.string.spotify_use_for_search)) },
                description = stringResource(R.string.spotify_use_for_search_description),
                checked = useSpotifySearch,
                onCheckedChange = onUseSpotifySearchChange,
            )

            SwitchPreference(
                title = { Text(stringResource(R.string.spotify_use_for_home)) },
                description = stringResource(R.string.spotify_use_for_home_description),
                checked = useSpotifyHome,
                onCheckedChange = onUseSpotifyHomeChange,
            )

            if (useSpotifyHome) {
                SwitchPreference(
                    title = { Text(stringResource(R.string.spotify_home_only)) },
                    description = stringResource(R.string.spotify_home_only_description),
                    checked = spotifyHomeOnly,
                    onCheckedChange = onSpotifyHomeOnlyChange,
                )
            }

            val (syncLikes, onSyncLikesChange) = rememberPreference(
                key = SpotifySyncLikesKey,
                defaultValue = false,
            )

            val database = LocalDatabase.current
            val syncProgress by SpotifyLikeSyncState.progress.collectAsState()
            val isSyncing by SpotifyLikeSyncState.isSyncing.collectAsState()

            SwitchPreference(
                title = { Text(stringResource(R.string.spotify_sync_likes)) },
                description = stringResource(R.string.spotify_sync_likes_description),
                checked = syncLikes,
                onCheckedChange = { enabled ->
                    onSyncLikesChange(enabled)
                    if (enabled) {
                        coroutineScope.launch {
                            runInitialSpotifyLikeSync(
                                database = database,
                                onComplete = { count ->
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.spotify_sync_likes_complete, count),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                                onError = {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.spotify_sync_likes_failed),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                            )
                        }
                    }
                },
                isEnabled = !isSyncing,
            )

            if (isSyncing) {
                PreferenceEntry(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.size(12.dp))
                            Text(
                                text = stringResource(
                                    R.string.spotify_sync_likes_syncing,
                                    syncProgress.first,
                                    syncProgress.second,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    },
                    description = null,
                    icon = {},
                )
            }

            if (syncLikes) {
                val (hideYtmLikedSongs, onHideYtmLikedSongsChange) = rememberPreference(
                    key = HideYtmLikedSongsKey,
                    defaultValue = true,
                )
                SwitchPreference(
                    title = { Text(stringResource(R.string.hide_ytm_liked_songs)) },
                    description = stringResource(R.string.hide_ytm_liked_songs_description),
                    checked = hideYtmLikedSongs,
                    onCheckedChange = onHideYtmLikedSongsChange,
                )
            }

            PreferenceGroupTitle(
                title = stringResource(R.string.spotify_preload_songs),
            )

            PreferenceEntry(
                title = { Text(stringResource(R.string.spotify_preload_songs)) },
                description = stringResource(R.string.spotify_preload_songs_description),
                icon = {
                    Icon(
                        painterResource(R.drawable.spotify),
                        contentDescription = null,
                    )
                },
                trailingContent = {
                    OutlinedButton(
                        onClick = { navController.navigate("settings/integrations/spotify/preload") },
                    ) {
                        Text(stringResource(R.string.spotify_preload_start))
                    }
                },
            )

            PreferenceGroupTitle(
                title = stringResource(R.string.qobuz_audio_quality_section),
            )

            val (enableQobuz, onEnableQobuzChange) = rememberPreference(
                key = EnableQobuzKey,
                defaultValue = false,
            )

            SwitchPreference(
                title = { Text(stringResource(R.string.qobuz_enable)) },
                description = stringResource(R.string.qobuz_enable_description),
                checked = enableQobuz,
                onCheckedChange = onEnableQobuzChange,
            )

            if (enableQobuz) {
                var qobuzQuality by rememberEnumPreference(
                    QobuzAudioQualityKey,
                    defaultValue = QobuzAudioQuality.CD_QUALITY,
                )
                var qobuzBackend by rememberEnumPreference(
                    QobuzBackendKey,
                    defaultValue = QobuzBackend.MONOKENNY,
                )
                var qobuzCountry by rememberPreference(QobuzCountryKey, "US")

                var showQobuzQualityDialog by remember { mutableStateOf(false) }
                var showQobuzBackendDialog by remember { mutableStateOf(false) }
                var showQobuzCountryDialog by remember { mutableStateOf(false) }

                if (showQobuzQualityDialog) {
                    EnumDialog(
                        onDismiss = { showQobuzQualityDialog = false },
                        onSelect = {
                            qobuzQuality = it
                            showQobuzQualityDialog = false
                        },
                        title = stringResource(R.string.qobuz_quality),
                        current = qobuzQuality,
                        values = QobuzAudioQuality.entries.toList(),
                        valueText = {
                            when (it) {
                                QobuzAudioQuality.AAC_320 -> stringResource(R.string.qobuz_quality_aac_320)
                                QobuzAudioQuality.CD_QUALITY -> stringResource(R.string.qobuz_quality_cd)
                                QobuzAudioQuality.HI_RES_LOSSLESS -> stringResource(R.string.qobuz_quality_hires)
                            }
                        },
                        valueDescription = {
                            when (it) {
                                QobuzAudioQuality.AAC_320 -> stringResource(R.string.qobuz_quality_aac_320_desc)
                                QobuzAudioQuality.CD_QUALITY -> stringResource(R.string.qobuz_quality_cd_desc)
                                QobuzAudioQuality.HI_RES_LOSSLESS -> stringResource(R.string.qobuz_quality_hires_desc)
                            }
                        },
                    )
                }

                PreferenceEntry(
                    title = { Text(stringResource(R.string.qobuz_quality)) },
                    description = when (qobuzQuality) {
                        QobuzAudioQuality.AAC_320 -> stringResource(R.string.qobuz_quality_aac_320)
                        QobuzAudioQuality.CD_QUALITY -> stringResource(R.string.qobuz_quality_cd)
                        QobuzAudioQuality.HI_RES_LOSSLESS -> stringResource(R.string.qobuz_quality_hires)
                    },
                    onClick = { showQobuzQualityDialog = true },
                )

                if (showQobuzBackendDialog) {
                    EnumDialog(
                        onDismiss = { showQobuzBackendDialog = false },
                        onSelect = {
                            qobuzBackend = it
                            showQobuzBackendDialog = false
                        },
                        title = stringResource(R.string.qobuz_backend),
                        current = qobuzBackend,
                        values = QobuzBackend.entries.toList(),
                        valueText = {
                            when (it) {
                                QobuzBackend.MONOKENNY -> stringResource(R.string.qobuz_backend_monokenny)
                                QobuzBackend.JUMO -> stringResource(R.string.qobuz_backend_jumo)
                                QobuzBackend.SQUID -> stringResource(R.string.qobuz_backend_squid)
                                QobuzBackend.TRYPT -> stringResource(R.string.qobuz_backend_trypt)
                            }
                        },
                    )
                }

                PreferenceEntry(
                    title = { Text(stringResource(R.string.qobuz_backend)) },
                    description = when (qobuzBackend) {
                        QobuzBackend.MONOKENNY -> stringResource(R.string.qobuz_backend_monokenny)
                        QobuzBackend.JUMO -> stringResource(R.string.qobuz_backend_jumo)
                        QobuzBackend.SQUID -> stringResource(R.string.qobuz_backend_squid)
                        QobuzBackend.TRYPT -> stringResource(R.string.qobuz_backend_trypt)
                    },
                    onClick = { showQobuzBackendDialog = true },
                )

                if (showQobuzCountryDialog) {
                    val countries = listOf(
                        "US", "IT", "FR", "GB", "DE", "ES", "NL", "NZ", "JP", "BR",
                    )
                    ListDialog(onDismiss = { showQobuzCountryDialog = false }) {
                        items(countries) { code ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        qobuzCountry = code
                                        showQobuzCountryDialog = false
                                    }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                            ) {
                                RadioButton(
                                    selected = code == qobuzCountry,
                                    onClick = null,
                                )
                                Text(
                                    text = code,
                                    modifier = Modifier.padding(start = 16.dp),
                                )
                            }
                        }
                    }
                }

                PreferenceEntry(
                    title = { Text(stringResource(R.string.qobuz_country)) },
                    description = "$qobuzCountry — ${stringResource(R.string.qobuz_country_description)}",
                    onClick = { showQobuzCountryDialog = true },
                )

                QobuzBackendHealthSection(currentBackend = qobuzBackend)
            }

            PreferenceGroupTitle(
                title = stringResource(R.string.information),
            )

            PreferenceEntry(
                title = {
                    Text(
                        text = stringResource(R.string.spotify_mapping_info),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                description = null,
                icon = {
                    Icon(
                        painterResource(R.drawable.info),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }
    }

    TopAppBar(
        title = { Text(stringResource(R.string.spotify_integration)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                )
            }
        },
    )
}

/**
 * Live reachability panel for the four Qobuz resolver backends. Triggers a
 * fan-out ping on mount and on user refresh. The user's currently selected
 * backend is visually highlighted so they can immediately see if the active
 * proxy is the one that's down.
 */
@Composable
private fun QobuzBackendHealthSection(currentBackend: QobuzBackend) {
    val coroutineScope = rememberCoroutineScope()
    var results by remember { mutableStateOf<List<QobuzBackendHealthChecker.Result>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    val refresh: () -> Unit = {
        if (!loading) {
            loading = true
            coroutineScope.launch {
                results = QobuzBackendHealthChecker.checkAll()
                loading = false
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) { refresh() }

    PreferenceGroupTitle(title = stringResource(R.string.qobuz_provider_health))

    PreferenceEntry(
        title = {
            Text(
                text = stringResource(R.string.qobuz_provider_health_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        description = null,
        trailingContent = {
            OutlinedButton(
                onClick = refresh,
                enabled = !loading,
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(stringResource(R.string.qobuz_provider_health_refresh))
                }
            }
        },
    )

    val activeResolverBackend = when (currentBackend) {
        QobuzBackend.MONOKENNY -> com.metrolist.music.qobuz.QobuzAudioProvider.ResolverBackend.MONOKENNY
        QobuzBackend.JUMO -> com.metrolist.music.qobuz.QobuzAudioProvider.ResolverBackend.JUMO
        QobuzBackend.SQUID -> com.metrolist.music.qobuz.QobuzAudioProvider.ResolverBackend.SQUID
        QobuzBackend.TRYPT -> com.metrolist.music.qobuz.QobuzAudioProvider.ResolverBackend.TRYPT
    }

    if (results.isEmpty() && loading) {
        PreferenceEntry(
            title = { Text(stringResource(R.string.qobuz_provider_health_checking)) },
            description = null,
            icon = { CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) },
        )
    } else {
        results.forEach { result ->
            val color = when (result.status) {
                QobuzBackendHealthChecker.Status.ONLINE -> Color(0xFF2ECC71)
                QobuzBackendHealthChecker.Status.REACHABLE -> Color(0xFFF1C40F)
                QobuzBackendHealthChecker.Status.OFFLINE -> Color(0xFFE74C3C)
            }
            val statusLabel = when (result.status) {
                QobuzBackendHealthChecker.Status.ONLINE -> stringResource(R.string.qobuz_provider_health_online)
                QobuzBackendHealthChecker.Status.REACHABLE -> stringResource(R.string.qobuz_provider_health_reachable)
                QobuzBackendHealthChecker.Status.OFFLINE -> stringResource(R.string.qobuz_provider_health_offline)
            }
            val isActive = result.target.backend == activeResolverBackend
            val nameSuffix = if (isActive) "  •" else ""
            val latency = result.latencyMs?.let {
                stringResource(R.string.qobuz_provider_health_latency, it.toInt())
            }
            PreferenceEntry(
                title = {
                    Text(
                        text = result.target.name + nameSuffix,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                },
                description = listOfNotNull(statusLabel, latency, result.message)
                    .joinToString(" • "),
                icon = {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(color = color, shape = CircleShape),
                    )
                },
            )
        }
    }
}

/**
 * Holds observable state for the initial Spotify→local like sync so the
 * settings UI can show a progress indicator.
 */
internal object SpotifyLikeSyncState {
    /** (current, total) */
    val progress = MutableStateFlow(0 to 0)
    val isSyncing = MutableStateFlow(false)
}

/**
 * Pulls all liked songs from Spotify, resolves each to a YouTube equivalent
 * via [SpotifyYouTubeMapper], and marks them as liked in Room.
 */
internal suspend fun runInitialSpotifyLikeSync(
    database: com.metrolist.music.db.MusicDatabase,
    onComplete: (Int) -> Unit,
    onError: (Throwable) -> Unit,
) {
    if (SpotifyLikeSyncState.isSyncing.value) return
    SpotifyLikeSyncState.isSyncing.value = true
    SpotifyLikeSyncState.progress.value = 0 to 0

    try {
        withContext(Dispatchers.IO) {
            val mapper = SpotifyYouTubeMapper(database)
            val allTracks = mutableListOf<com.metrolist.spotify.models.SpotifyTrack>()

            // Paginated fetch of all Spotify liked songs
            var offset = 0
            val pageSize = 50
            while (true) {
                val page = Spotify.likedSongs(limit = pageSize, offset = offset).getOrThrow()
                allTracks.addAll(page.items.mapNotNull { it.track })
                SpotifyLikeSyncState.progress.value = allTracks.size to page.total
                if (allTracks.size >= page.total || page.items.isEmpty()) break
                offset += pageSize
            }

            Timber.d("SpotifyLikeSync: fetched ${allTracks.size} liked songs from Spotify")

            val now = LocalDateTime.now()
            var synced = 0

            data class ResolvedTrack(
                val metadata: MediaMetadata,
                val index: Int,
            )

            val resolved = mutableListOf<ResolvedTrack>()
            allTracks.forEachIndexed { index, spotifyTrack ->
                try {
                    val metadata = mapper.mapToYouTube(spotifyTrack)
                    if (metadata != null) {
                        resolved.add(ResolvedTrack(metadata, index))
                    }
                    SpotifyLikeSyncState.progress.value = (index + 1) to allTracks.size
                } catch (e: Exception) {
                    Timber.w(e, "SpotifyLikeSync: failed to resolve ${spotifyTrack.id}")
                }
            }

            Timber.d("SpotifyLikeSync: resolved ${resolved.size}/${allTracks.size} tracks, writing to DB")

            database.withTransaction {
                resolved.forEach { (metadata, index) ->
                    try {
                        val existingSong = getSongByIdBlocking(metadata.id)
                        if (existingSong == null) {
                            insert(metadata) {
                                it.copy(liked = true, likedDate = now.minusSeconds(index.toLong()))
                            }
                        } else if (!existingSong.song.liked) {
                            update(existingSong.song.copy(liked = true, likedDate = now.minusSeconds(index.toLong())))
                        }
                        synced++
                    } catch (e: Exception) {
                        Timber.w(e, "SpotifyLikeSync: failed to insert ${metadata.id}")
                    }
                }
            }

            Timber.d("SpotifyLikeSync: synced $synced/${allTracks.size} songs to local DB")
            withContext(Dispatchers.Main) { onComplete(synced) }
        }
    } catch (e: Exception) {
        Timber.e(e, "SpotifyLikeSync: initial sync failed")
        withContext(Dispatchers.Main) { onError(e) }
    } finally {
        SpotifyLikeSyncState.isSyncing.value = false
    }
}
