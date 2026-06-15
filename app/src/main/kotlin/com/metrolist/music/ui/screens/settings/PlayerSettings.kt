/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.music.BuildConfig
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import kotlinx.coroutines.launch
import com.metrolist.music.constants.AudioNormalizationKey
import com.metrolist.music.constants.AudioOffload
import com.metrolist.music.constants.UnifiedAudioQualityKey
import com.metrolist.music.constants.MonochromeBackendKey
import com.metrolist.music.constants.MonochromeCustomUrlKey
import com.metrolist.music.constants.UnifiedAudioQuality
import com.metrolist.music.constants.MonochromeBackend
import com.metrolist.music.monochrome.MonochromeBackendHealthChecker
import com.metrolist.music.ui.component.TextFieldDialog
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import com.metrolist.music.constants.AutoDownloadOnLikeKey
import com.metrolist.music.constants.CrossfadeDurationKey
import com.metrolist.music.constants.CrossfadeEnabledKey
import com.metrolist.music.constants.CrossfadeGaplessKey
import com.metrolist.music.constants.AutoLoadMoreKey
import com.metrolist.music.constants.AutoSkipNextOnErrorKey
import com.metrolist.music.constants.DisableLoadMoreWhenRepeatAllKey
import com.metrolist.music.constants.EnableGoogleCastKey
import com.metrolist.music.constants.HistoryDuration
import com.metrolist.music.constants.KeepScreenOn
import com.metrolist.music.constants.PauseOnMute
import com.metrolist.music.constants.PersistentQueueKey
import com.metrolist.music.constants.PersistentShuffleAcrossQueuesKey
import com.metrolist.music.constants.PreventDuplicateTracksInQueueKey
import com.metrolist.music.constants.RememberShuffleAndRepeatKey
import com.metrolist.music.constants.ResumeOnBluetoothConnectKey
import com.metrolist.music.constants.SeekExtraSeconds
import com.metrolist.music.constants.ShufflePlaylistFirstKey
import com.metrolist.music.constants.SimilarContent
import com.metrolist.music.constants.SkipSilenceInstantKey
import com.metrolist.music.constants.SkipSilenceKey
import com.metrolist.music.constants.StopMusicOnTaskClearKey
import com.metrolist.music.constants.VarispeedKey
import com.metrolist.music.ui.component.DefaultDialog
import com.metrolist.music.ui.component.EnumDialog
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference
import kotlin.math.roundToInt
import com.metrolist.music.ui.component.SleepTimerDialog
import com.metrolist.music.constants.SleepTimerEnabledKey
import com.metrolist.music.constants.SleepTimerRepeatKey
import com.metrolist.music.constants.SleepTimerCustomDaysKey
import com.metrolist.music.constants.SleepTimerEndTimeKey
import com.metrolist.music.constants.SleepTimerStartTimeKey
import com.metrolist.music.constants.SleepTimerDayTimesKey
import com.metrolist.music.ui.component.decodeDayTimes
import com.metrolist.music.ui.component.encodeDayTimes
import com.metrolist.music.constants.SleepTimerFadeOutKey
import com.metrolist.music.constants.SleepTimerStopAfterCurrentSongKey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerSettings(
    navController: NavController
) {
    val (unifiedQuality, onUnifiedQualityChange) = rememberEnumPreference(
        UnifiedAudioQualityKey,
        defaultValue = UnifiedAudioQuality.YT_HIGH
    )
    val (monochromeBackend, onMonochromeBackendChange) = rememberEnumPreference(
        MonochromeBackendKey,
        defaultValue = MonochromeBackend.OFFICIAL
    )
    val (monochromeCustomUrl, onMonochromeCustomUrlChange) = rememberPreference(
        MonochromeCustomUrlKey,
        defaultValue = ""
    )
    val (crossfadeEnabled, onCrossfadeEnabledChange) = rememberPreference(
        CrossfadeEnabledKey,
        defaultValue = false
    )
    val (crossfadeDuration, onCrossfadeDurationChange) = rememberPreference(
        CrossfadeDurationKey,
        defaultValue = 5f
    )
    val (crossfadeGapless, onCrossfadeGaplessChange) = rememberPreference(
        CrossfadeGaplessKey,
        defaultValue = true
    )
    val (persistentQueue, onPersistentQueueChange) = rememberPreference(
        PersistentQueueKey,
        defaultValue = true
    )
    val (skipSilence, onSkipSilenceChange) = rememberPreference(
        SkipSilenceKey,
        defaultValue = false
    )
    val (skipSilenceInstant, onSkipSilenceInstantChange) = rememberPreference(
        SkipSilenceInstantKey,
        defaultValue = false
    )
    val (audioNormalization, onAudioNormalizationChange) = rememberPreference(
        AudioNormalizationKey,
        defaultValue = true
    )

    val (audioOffload, onAudioOffloadChange) = rememberPreference(
        key = AudioOffload,
        defaultValue = false
    )

    val (varispeed, onVarispeedChange) = rememberPreference(
        key = VarispeedKey,
        defaultValue = false
    )

    val (enableGoogleCast, onEnableGoogleCastChange) = rememberPreference(
        key = EnableGoogleCastKey,
        defaultValue = true
    )

    val (seekExtraSeconds, onSeekExtraSeconds) = rememberPreference(
        SeekExtraSeconds,
        defaultValue = false
    )

    val (autoLoadMore, onAutoLoadMoreChange) = rememberPreference(
        AutoLoadMoreKey,
        defaultValue = true
    )
    val (disableLoadMoreWhenRepeatAll, onDisableLoadMoreWhenRepeatAllChange) = rememberPreference(
        DisableLoadMoreWhenRepeatAllKey,
        defaultValue = false
    )
    val (autoDownloadOnLike, onAutoDownloadOnLikeChange) = rememberPreference(
        AutoDownloadOnLikeKey,
        defaultValue = false
    )
    val (similarContentEnabled, similarContentEnabledChange) = rememberPreference(
        key = SimilarContent,
        defaultValue = true
    )
    val (autoSkipNextOnError, onAutoSkipNextOnErrorChange) = rememberPreference(
        AutoSkipNextOnErrorKey,
        defaultValue = false
    )
    val (persistentShuffleAcrossQueues, onPersistentShuffleAcrossQueuesChange) = rememberPreference(
        PersistentShuffleAcrossQueuesKey,
        defaultValue = false
    )
    val (rememberShuffleAndRepeat, onRememberShuffleAndRepeatChange) = rememberPreference(
        RememberShuffleAndRepeatKey,
        defaultValue = true
    )
    val (shufflePlaylistFirst, onShufflePlaylistFirstChange) = rememberPreference(
        ShufflePlaylistFirstKey,
        defaultValue = false
    )
    val (preventDuplicateTracksInQueue, onPreventDuplicateTracksInQueueChange) = rememberPreference(
        PreventDuplicateTracksInQueueKey,
        defaultValue = false
    )
    val (stopMusicOnTaskClear, onStopMusicOnTaskClearChange) = rememberPreference(
        StopMusicOnTaskClearKey,
        defaultValue = false
    )
    val (pauseOnMute, onPauseOnMuteChange) = rememberPreference(
        PauseOnMute,
        defaultValue = false
    )
    val (resumeOnBluetoothConnect, onResumeOnBluetoothConnectChange) = rememberPreference(
        ResumeOnBluetoothConnectKey,
        defaultValue = false
    )
    val (keepScreenOn, onKeepScreenOnChange) = rememberPreference(
        KeepScreenOn,
        defaultValue = false
    )
    val (historyDuration, onHistoryDurationChange) = rememberPreference(
        HistoryDuration,
        defaultValue = 30f
    )

    var showUnifiedQualityDialog by remember {
        mutableStateOf(false)
    }

    if (showUnifiedQualityDialog) {
        EnumDialog(
            onDismiss = { showUnifiedQualityDialog = false },
            onSelect = {
                onUnifiedQualityChange(it)
                showUnifiedQualityDialog = false
            },
            title = stringResource(R.string.monochrome_quality),
            current = unifiedQuality,
            values = UnifiedAudioQuality.values().toList(),
            valueText = {
                when (it) {
                    UnifiedAudioQuality.YT_HIGH -> stringResource(R.string.monochrome_quality_yt_high)
                    UnifiedAudioQuality.KBPS_320 -> stringResource(R.string.monochrome_quality_kbps_320)
                    UnifiedAudioQuality.FLAC -> stringResource(R.string.monochrome_quality_cd)
                    UnifiedAudioQuality.HIRES -> stringResource(R.string.monochrome_quality_hires)
                }
            }
        )
    }

    var showMonochromeBackendDialog by remember {
        mutableStateOf(false)
    }

    if (showMonochromeBackendDialog) {
        EnumDialog(
            onDismiss = { showMonochromeBackendDialog = false },
            onSelect = {
                onMonochromeBackendChange(it)
                showMonochromeBackendDialog = false
            },
            title = stringResource(R.string.monochrome_backend),
            current = monochromeBackend,
            values = MonochromeBackend.values().toList(),
            valueText = {
                when (it) {
                    MonochromeBackend.OFFICIAL -> stringResource(R.string.monochrome_backend_official)
                    MonochromeBackend.SAMIDY -> stringResource(R.string.monochrome_backend_samidy)
                    MonochromeBackend.CUSTOM -> stringResource(R.string.monochrome_backend_custom)
                }
            }
        )
    }

    var showMonochromeCustomUrlDialog by remember {
        mutableStateOf(false)
    }

    if (showMonochromeCustomUrlDialog) {
        TextFieldDialog(
            title = { Text(stringResource(R.string.monochrome_custom_url)) },
            initialTextFieldValue = TextFieldValue(text = monochromeCustomUrl),
            placeholder = { Text(stringResource(R.string.monochrome_custom_url_description)) },
            isInputValid = { true },
            onDone = {
                onMonochromeCustomUrlChange(it)
                showMonochromeCustomUrlDialog = false
            },
            onDismiss = { showMonochromeCustomUrlDialog = false }
        )
    }

    Column(
        Modifier
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                )
            )
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        var showCrossfadeBetaDialog by remember { mutableStateOf(false) }

        if (showCrossfadeBetaDialog) {
            DefaultDialog(
                onDismiss = { showCrossfadeBetaDialog = false },
                title = { Text(stringResource(R.string.crossfade_beta_title)) },
                buttons = {
                    TextButton(onClick = { showCrossfadeBetaDialog = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                    TextButton(onClick = {
                        showCrossfadeBetaDialog = false
                        onCrossfadeEnabledChange(true)
                    }) {
                        Text(stringResource(R.string.enable))
                    }
                }
            ) {
                Text(stringResource(R.string.crossfade_beta_message))
            }
        }

        Spacer(
            Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Top
                )
            )
        )

        Material3SettingsGroup(
            title = stringResource(R.string.player),
            items = buildList {
                add(Material3SettingsItem(
                    icon = painterResource(R.drawable.graphic_eq),
                    title = { Text(stringResource(R.string.monochrome_quality)) },
                    description = {
                        Text(
                            when (unifiedQuality) {
                                UnifiedAudioQuality.YT_HIGH -> stringResource(R.string.monochrome_quality_yt_high)
                                UnifiedAudioQuality.KBPS_320 -> stringResource(R.string.monochrome_quality_kbps_320)
                                UnifiedAudioQuality.FLAC -> stringResource(R.string.monochrome_quality_cd)
                                UnifiedAudioQuality.HIRES -> stringResource(R.string.monochrome_quality_hires)
                            }
                        )
                    },
                    onClick = { showUnifiedQualityDialog = true }
                ))
                if (unifiedQuality != UnifiedAudioQuality.YT_HIGH) {
                    add(Material3SettingsItem(
                        icon = painterResource(R.drawable.cloud),
                        title = { Text(stringResource(R.string.monochrome_backend)) },
                        description = {
                            Text(
                                when (monochromeBackend) {
                                    MonochromeBackend.OFFICIAL -> stringResource(R.string.monochrome_backend_official)
                                    MonochromeBackend.SAMIDY -> stringResource(R.string.monochrome_backend_samidy)
                                    MonochromeBackend.CUSTOM -> stringResource(R.string.monochrome_backend_custom)
                                }
                            )
                        },
                        onClick = { showMonochromeBackendDialog = true }
                    ))
                    if (monochromeBackend == MonochromeBackend.CUSTOM) {
                        add(Material3SettingsItem(
                            icon = painterResource(R.drawable.link),
                            title = { Text(stringResource(R.string.monochrome_custom_url)) },
                            description = {
                                Text(
                                    if (monochromeCustomUrl.isNotBlank()) monochromeCustomUrl
                                    else stringResource(R.string.monochrome_custom_url_description)
                                )
                            },
                            onClick = { showMonochromeCustomUrlDialog = true }
                        ))
                    }
                }
                add(Material3SettingsItem(
                    icon = painterResource(R.drawable.linear_scale),
                    title = { Text(stringResource(R.string.crossfade)) },
                    description = { Text(stringResource(R.string.crossfade_desc)) },
                    showBadge = true,
                    trailingContent = {
                        Switch(
                            checked = crossfadeEnabled,
                            onCheckedChange = {
                                if (!crossfadeEnabled) {
                                    showCrossfadeBetaDialog = true
                                } else {
                                    onCrossfadeEnabledChange(false)
                                }
                            },
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (crossfadeEnabled) R.drawable.check else R.drawable.close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = {
                        if (!crossfadeEnabled) {
                            showCrossfadeBetaDialog = true
                        } else {
                            onCrossfadeEnabledChange(false)
                        }
                    }
                ))
                if (crossfadeEnabled) {
                    add(Material3SettingsItem(
                        icon = painterResource(R.drawable.timer),
                        title = { Text(stringResource(R.string.crossfade_duration)) },
                        description = {
                            Column {
                                Text(pluralStringResource(R.plurals.seconds, crossfadeDuration.toInt(), crossfadeDuration.toInt()))
                                Slider(
                                    value = crossfadeDuration,
                                    onValueChange = onCrossfadeDurationChange,
                                    valueRange = 1f..15f,
                                    steps = 14
                                )
                            }
                        }
                    ))
                    add(Material3SettingsItem(
                        icon = painterResource(R.drawable.album),
                        title = { Text(stringResource(R.string.crossfade_gapless)) },
                        description = { Text(stringResource(R.string.crossfade_gapless_desc)) },
                        trailingContent = {
                            Switch(
                                checked = crossfadeGapless,
                                onCheckedChange = onCrossfadeGaplessChange,
                                thumbContent = {
                                    Icon(
                                        painter = painterResource(
                                            id = if (crossfadeGapless) R.drawable.check else R.drawable.close
                                        ),
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize)
                                    )
                                }
                            )
                        },
                        onClick = { onCrossfadeGaplessChange(!crossfadeGapless) }
                    ))
                }
                add(Material3SettingsItem(
                    icon = painterResource(R.drawable.history),
                    title = { Text(stringResource(R.string.history_duration)) },
                    description = {
                        Column {
                            Text(historyDuration.roundToInt().toString())
                            Slider(
                                value = historyDuration,
                                onValueChange = onHistoryDurationChange,
                                valueRange = 1f..100f
                            )
                        }
                    }
                ))
                add(Material3SettingsItem(
                    icon = painterResource(R.drawable.fast_forward),
                    title = { Text(stringResource(R.string.skip_silence)) },
                    description = { Text(stringResource(R.string.skip_silence_desc)) },
                    trailingContent = {
                        Switch(
                            checked = skipSilence,
                            onCheckedChange = onSkipSilenceChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (skipSilence) R.drawable.check else R.drawable.close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { onSkipSilenceChange(!skipSilence) }
                ))
                add(Material3SettingsItem(
                    icon = painterResource(R.drawable.skip_next),
                    title = { Text(stringResource(R.string.skip_silence_instant)) },
                    description = { Text(stringResource(R.string.skip_silence_instant_desc)) },
                    trailingContent = {
                        Switch(
                            checked = skipSilenceInstant,
                            onCheckedChange = { onSkipSilenceInstantChange(it) },
                            enabled = skipSilence,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (skipSilenceInstant) R.drawable.check else R.drawable.close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { if (skipSilence) onSkipSilenceInstantChange(!skipSilenceInstant) }
                ))
                add(Material3SettingsItem(
                    icon = painterResource(R.drawable.volume_up),
                    title = { Text(stringResource(R.string.audio_normalization)) },
                    trailingContent = {
                        Switch(
                            checked = audioNormalization,
                            onCheckedChange = onAudioNormalizationChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (audioNormalization) R.drawable.check else R.drawable.close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { onAudioNormalizationChange(!audioNormalization) }
                ))
                add(Material3SettingsItem(
                    icon = painterResource(R.drawable.graphic_eq),
                    title = { Text(stringResource(R.string.audio_offload)) },
                    description = {
                        Text(
                            if (crossfadeEnabled) stringResource(R.string.audio_offload_disabled_by_crossfade)
                            else stringResource(R.string.audio_offload_description)
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = if (crossfadeEnabled) false else audioOffload,
                            onCheckedChange = onAudioOffloadChange,
                            enabled = !crossfadeEnabled,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (!crossfadeEnabled && audioOffload) R.drawable.check else R.drawable.close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { if (!crossfadeEnabled) onAudioOffloadChange(!audioOffload) }
                ))
                add(Material3SettingsItem(
                    icon = painterResource(R.drawable.graphic_eq),
                    title = { Text(stringResource(R.string.varispeed)) },
                    description = {
                        Text(
                            stringResource(R.string.varispeed_description)
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = varispeed,
                            onCheckedChange = onVarispeedChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (varispeed) R.drawable.check else R.drawable.close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { onVarispeedChange(!varispeed) }
                ))
                // Only show Cast setting in GMS builds (not in F-Droid/FOSS)
                if (BuildConfig.CAST_AVAILABLE) {
                    add(Material3SettingsItem(
                        icon = painterResource(R.drawable.cast),
                        title = { Text(stringResource(R.string.google_cast)) },
                        description = { Text(stringResource(R.string.google_cast_description)) },
                        trailingContent = {
                            Switch(
                                checked = enableGoogleCast,
                                onCheckedChange = onEnableGoogleCastChange,
                                thumbContent = {
                                    Icon(
                                        painter = painterResource(
                                            id = if (enableGoogleCast) R.drawable.check else R.drawable.close
                                        ),
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize)
                                    )
                                }
                            )
                        },
                        onClick = { onEnableGoogleCastChange(!enableGoogleCast) }
                    ))
                }
                add(Material3SettingsItem(
                    icon = painterResource(R.drawable.arrow_forward),
                    title = { Text(stringResource(R.string.seek_seconds_addup)) },
                    description = { Text(stringResource(R.string.seek_seconds_addup_description)) },
                    trailingContent = {
                        Switch(
                            checked = seekExtraSeconds,
                            onCheckedChange = onSeekExtraSeconds,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (seekExtraSeconds) R.drawable.check else R.drawable.close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { onSeekExtraSeconds(!seekExtraSeconds) }
                ))
            }
        )

        if (unifiedQuality != UnifiedAudioQuality.YT_HIGH) {
            MonochromeBackendHealthSection(
                currentBackend = monochromeBackend,
                customUrl = monochromeCustomUrl
            )
        }

        Spacer(modifier = Modifier.height(27.dp))

        var showSleepTimerDialog by remember { mutableStateOf(false) }

        val (sleepTimerEnabled, onSleepTimerEnabledChange) = rememberPreference(
            SleepTimerEnabledKey,
            defaultValue = false
        )
        val (sleepTimerRepeat, onSleepTimerRepeatChange) = rememberPreference(
            SleepTimerRepeatKey,
            defaultValue = "daily"
        )
        val (sleepTimerStartTime, onSleepTimerStartTimeChange) = rememberPreference(
            SleepTimerStartTimeKey,
            defaultValue = "22:00"
        )
        val (sleepTimerEndTime, onSleepTimerEndTimeChange) = rememberPreference(
            SleepTimerEndTimeKey,
            defaultValue = "06:00"
        )
        val (sleepTimerCustomDays, onSleepTimerCustomDaysChange) = rememberPreference(
            SleepTimerCustomDaysKey,
            defaultValue = "0,1,2,3,4"
        )
        // Per-day time ranges used in custom mode
        val (sleepTimerDayTimes, onSleepTimerDayTimesChange) = rememberPreference(
            SleepTimerDayTimesKey,
            defaultValue = ""
        )

        val (sleepTimerStopAfterCurrentSong, onSleepTimerStopAfterCurrentSongChange) = rememberPreference (
        SleepTimerStopAfterCurrentSongKey,
        defaultValue = false)
        val (sleepTimerFadeOut, onSleepTimerFadeOutChange) = rememberPreference(
            SleepTimerFadeOutKey,
            false
        )

        if (showSleepTimerDialog) {
            val customDays = sleepTimerCustomDays.split(",").mapNotNull { it.toIntOrNull() }
            val dayTimesMap = decodeDayTimes(sleepTimerDayTimes)

            SleepTimerDialog(
                isVisible = true,
                onDismiss = { showSleepTimerDialog = false },
                onConfirm = { repeat, startTime, endTime, days, dayTimes ->
                    onSleepTimerRepeatChange(repeat)
                    onSleepTimerStartTimeChange(startTime)
                    onSleepTimerEndTimeChange(endTime)
                    onSleepTimerCustomDaysChange(days?.joinToString(",") ?: "0,1,2,3,4")
                    onSleepTimerDayTimesChange(encodeDayTimes(dayTimes))
                    showSleepTimerDialog = false
                },
                initialRepeat = sleepTimerRepeat,
                initialStartTime = sleepTimerStartTime,
                initialEndTime = sleepTimerEndTime,
                initialCustomDays = customDays,
                initialDayTimes = dayTimesMap
            )
        }

        Material3SettingsGroup(
            title = stringResource(R.string.sleep_timer),
            items = buildList {
                add(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.time_auto),
                        title = { Text(stringResource(R.string.enable_automatic_sleeptimer)) },
                        description = { Text(stringResource(R.string.sleeptimer_description)) },
                        trailingContent = {
                            Switch(
                                checked = sleepTimerEnabled,
                                onCheckedChange = onSleepTimerEnabledChange,
                                thumbContent = {
                                    Icon(
                                        painter = painterResource(
                                            id = if (sleepTimerEnabled) R.drawable.check else R.drawable.close
                                        ),
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize)
                                    )
                                }
                            )
                        },
                        onClick = { onSleepTimerEnabledChange(!sleepTimerEnabled) }
                    )
                )

                    add(
                        Material3SettingsItem(
                            icon = painterResource(R.drawable.baseline_event_repeat_24),
                            title = { Text(stringResource(R.string.sleep_timer_repeat)) },
                            description = {
                                Text(
                                    stringResource(R.string.sleep_timer_repeat_description)
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = sleepTimerEnabled,
                                    onCheckedChange = {showSleepTimerDialog = true},
                                    thumbContent = {
                                        Icon(
                                            painter = painterResource(
                                                id = if (sleepTimerEnabled) R.drawable.check else R.drawable.close
                                            ),
                                            contentDescription = null,
                                            modifier = Modifier.size(SwitchDefaults.IconSize)
                                        )
                                    }
                                )
                            },
                            onClick = { showSleepTimerDialog = true }
                        )
                    )


                add(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.more_time),
                        title = { Text(stringResource(R.string.sleep_timer_stop_after_current_song_title)) },
                        description = { Text(stringResource(R.string.sleep_timer_stop_after_current_song_description)) },
                        trailingContent = {
                            Switch(
                                checked = sleepTimerStopAfterCurrentSong,
                                onCheckedChange = onSleepTimerStopAfterCurrentSongChange,
                                thumbContent = {
                                    Icon(
                                        painter = painterResource(
                                            id = if (sleepTimerStopAfterCurrentSong) R.drawable.check else R.drawable.close
                                        ),
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize)
                                    )
                                }
                            )
                        },
                        onClick = { onSleepTimerStopAfterCurrentSongChange(!sleepTimerStopAfterCurrentSong) }
                    )
                )

                add(
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.timer_arrow_down),
                        title = { Text(stringResource(R.string.sleep_timer_fade_out_title)) },
                        description = { Text(stringResource(R.string.sleep_timer_fade_out_description)) },
                        trailingContent = {
                            Switch(
                                checked = sleepTimerFadeOut,
                                onCheckedChange = onSleepTimerFadeOutChange,
                                thumbContent = {
                                    Icon(
                                        painter = painterResource(
                                            id = if (sleepTimerFadeOut) R.drawable.check else R.drawable.close
                                        ),
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize)
                                    )
                                }
                            )
                        },
                        onClick = { onSleepTimerFadeOutChange(!sleepTimerFadeOut) }
                    )
                )

            }
        )

        AlarmSettingsSection(showTitle = false)

        Spacer(modifier = Modifier.height(27.dp))

        Material3SettingsGroup(
            title = stringResource(R.string.queue),
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.queue_music),
                    title = { Text(stringResource(R.string.persistent_queue)) },
                    description = { Text(stringResource(R.string.persistent_queue_desc)) },
                    trailingContent = {
                        Switch(
                            checked = persistentQueue,
                            onCheckedChange = onPersistentQueueChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (persistentQueue) R.drawable.check else R.drawable.close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { onPersistentQueueChange(!persistentQueue) }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.playlist_add),
                    title = { Text(stringResource(R.string.auto_load_more)) },
                    description = { Text(stringResource(R.string.auto_load_more_desc)) },
                    trailingContent = {
                        Switch(
                            checked = autoLoadMore,
                            onCheckedChange = onAutoLoadMoreChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (autoLoadMore) R.drawable.check else R.drawable.close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { onAutoLoadMoreChange(!autoLoadMore) }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.repeat),
                    title = { Text(stringResource(R.string.disable_load_more_when_repeat_all)) },
                    description = { Text(stringResource(R.string.disable_load_more_when_repeat_all_desc)) },
                    trailingContent = {
                        Switch(
                            checked = disableLoadMoreWhenRepeatAll,
                            onCheckedChange = onDisableLoadMoreWhenRepeatAllChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (disableLoadMoreWhenRepeatAll) R.drawable.check else R.drawable.close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { onDisableLoadMoreWhenRepeatAllChange(!disableLoadMoreWhenRepeatAll) }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.download),
                    title = { Text(stringResource(R.string.auto_download_on_like)) },
                    description = { Text(stringResource(R.string.auto_download_on_like_desc)) },
                    trailingContent = {
                        Switch(
                            checked = autoDownloadOnLike,
                            onCheckedChange = onAutoDownloadOnLikeChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (autoDownloadOnLike) R.drawable.check else R.drawable.close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { onAutoDownloadOnLikeChange(!autoDownloadOnLike) }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.similar),
                    title = { Text(stringResource(R.string.enable_similar_content)) },
                    description = { Text(stringResource(R.string.similar_content_desc)) },
                    trailingContent = {
                        Switch(
                            checked = similarContentEnabled,
                            onCheckedChange = similarContentEnabledChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (similarContentEnabled) R.drawable.check else R.drawable.close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { similarContentEnabledChange(!similarContentEnabled) }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.shuffle),
                    title = { Text(stringResource(R.string.persistent_shuffle_title)) },
                    description = { Text(stringResource(R.string.persistent_shuffle_desc)) },
                    trailingContent = {
                        Switch(
                            checked = persistentShuffleAcrossQueues,
                            onCheckedChange = onPersistentShuffleAcrossQueuesChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (persistentShuffleAcrossQueues) R.drawable.check else R.drawable.close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { onPersistentShuffleAcrossQueuesChange(!persistentShuffleAcrossQueues) }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.shuffle),
                    title = { Text(stringResource(R.string.remember_shuffle_and_repeat)) },
                    description = { Text(stringResource(R.string.remember_shuffle_and_repeat_desc)) },
                    trailingContent = {
                        Switch(
                            checked = rememberShuffleAndRepeat,
                            onCheckedChange = onRememberShuffleAndRepeatChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (rememberShuffleAndRepeat) R.drawable.check else R.drawable.close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { onRememberShuffleAndRepeatChange(!rememberShuffleAndRepeat) }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.shuffle),
                    title = { Text(stringResource(R.string.shuffle_playlist_first)) },
                    description = { Text(stringResource(R.string.shuffle_playlist_first_desc)) },
                    trailingContent = {
                        Switch(
                            checked = shufflePlaylistFirst,
                            onCheckedChange = onShufflePlaylistFirstChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (shufflePlaylistFirst) R.drawable.check else R.drawable.close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { onShufflePlaylistFirstChange(!shufflePlaylistFirst) }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.queue_music),
                    title = { Text(stringResource(R.string.prevent_duplicate_tracks_in_queue)) },
                    description = { Text(stringResource(R.string.prevent_duplicate_tracks_in_queue_desc)) },
                    trailingContent = {
                        Switch(
                            checked = preventDuplicateTracksInQueue,
                            onCheckedChange = onPreventDuplicateTracksInQueueChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (preventDuplicateTracksInQueue) R.drawable.check else R.drawable.close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { onPreventDuplicateTracksInQueueChange(!preventDuplicateTracksInQueue) }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.skip_next),
                    title = { Text(stringResource(R.string.auto_skip_next_on_error)) },
                    description = { Text(stringResource(R.string.auto_skip_next_on_error_desc)) },
                    trailingContent = {
                        Switch(
                            checked = autoSkipNextOnError,
                            onCheckedChange = onAutoSkipNextOnErrorChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (autoSkipNextOnError) R.drawable.check else R.drawable.close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { onAutoSkipNextOnErrorChange(!autoSkipNextOnError) }
                )
            )
        )

        Spacer(modifier = Modifier.height(27.dp))

        Material3SettingsGroup(
            title = stringResource(R.string.misc),
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.clear_all),
                    title = { Text(stringResource(R.string.stop_music_on_task_clear)) },
                    trailingContent = {
                        Switch(
                            checked = stopMusicOnTaskClear,
                            onCheckedChange = onStopMusicOnTaskClearChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (stopMusicOnTaskClear) R.drawable.check else R.drawable.close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { onStopMusicOnTaskClearChange(!stopMusicOnTaskClear) }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.volume_off_pause),
                    title = { Text(stringResource(R.string.pause_music_when_media_is_muted)) },
                    trailingContent = {
                        Switch(
                            checked = pauseOnMute,
                            onCheckedChange = onPauseOnMuteChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (pauseOnMute) R.drawable.check else R.drawable.close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { onPauseOnMuteChange(!pauseOnMute) }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.bluetooth),
                    title = { Text(stringResource(R.string.resume_on_bluetooth_connect)) },
                    trailingContent = {
                        Switch(
                            checked = resumeOnBluetoothConnect,
                            onCheckedChange = onResumeOnBluetoothConnectChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (resumeOnBluetoothConnect) R.drawable.check else R.drawable.close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { onResumeOnBluetoothConnectChange(!resumeOnBluetoothConnect) }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.screenshot),
                    title = { Text(stringResource(R.string.keep_screen_on_when_player_is_expanded)) },
                    trailingContent = {
                        Switch(
                            checked = keepScreenOn,
                            onCheckedChange = onKeepScreenOnChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (keepScreenOn) R.drawable.check else R.drawable.close
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        )
                    },
                    onClick = { onKeepScreenOnChange(!keepScreenOn) }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.fast_forward),
                    title = { Text(stringResource(R.string.sponsorblock)) },
                    description = { Text(stringResource(R.string.sponsorblock_desc)) },
                    onClick = { navController.navigate("settings/player/sponsorblock") }
                )
            )
        )
        Spacer(modifier = Modifier.height(16.dp))
    }

    TopAppBar(
        title = { Text(stringResource(R.string.player_and_audio)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain
            ) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = null
                )
            }
        }
    )
}

@Composable
private fun MonochromeBackendHealthSection(
    currentBackend: MonochromeBackend,
    customUrl: String?
) {
    val coroutineScope = rememberCoroutineScope()
    var results by remember { mutableStateOf<List<MonochromeBackendHealthChecker.Result>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    val refresh: () -> Unit = {
        if (!loading) {
            loading = true
            coroutineScope.launch {
                results = MonochromeBackendHealthChecker.checkAll(customUrl)
                loading = false
            }
        }
    }

    LaunchedEffect(customUrl) {
        refresh()
    }

    val healthItems = buildList {
        add(
            Material3SettingsItem(
                title = { Text(stringResource(R.string.monochrome_provider_health_description)) },
                trailingContent = {
                    androidx.compose.material3.OutlinedButton(
                        onClick = refresh,
                        enabled = !loading,
                    ) {
                        if (loading) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text(stringResource(R.string.monochrome_provider_health_refresh))
                        }
                    }
                }
            )
        )

        if (results.isEmpty() && loading) {
            add(
                Material3SettingsItem(
                    title = { Text(stringResource(R.string.monochrome_provider_health_checking)) },
                    leadingContent = {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    }
                )
            )
        } else {
            results.forEach { result ->
                val color = when (result.status) {
                    MonochromeBackendHealthChecker.Status.ONLINE -> Color(0xFF2ECC71)
                    MonochromeBackendHealthChecker.Status.REACHABLE -> Color(0xFFF1C40F)
                    MonochromeBackendHealthChecker.Status.OFFLINE -> Color(0xFFE74C3C)
                }
                val statusLabel = when (result.status) {
                    MonochromeBackendHealthChecker.Status.ONLINE -> stringResource(R.string.monochrome_provider_health_online)
                    MonochromeBackendHealthChecker.Status.REACHABLE -> stringResource(R.string.monochrome_provider_health_reachable)
                    MonochromeBackendHealthChecker.Status.OFFLINE -> stringResource(R.string.monochrome_provider_health_offline)
                }
                val isActive = result.target.backend == currentBackend
                val nameSuffix = if (isActive) "  •" else ""
                val latency = result.latencyMs?.let {
                    stringResource(R.string.monochrome_provider_health_latency, it.toInt())
                }
                add(
                    Material3SettingsItem(
                        title = {
                            Text(
                                text = result.target.name + nameSuffix,
                                color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        description = {
                            Text(
                                text = listOfNotNull(statusLabel, latency, result.message)
                                    .joinToString(" • ")
                            )
                        },
                        leadingContent = {
                            androidx.compose.foundation.layout.Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .background(color = color, shape = CircleShape),
                            )
                        }
                    )
                )
            }
        }
    }

    Material3SettingsGroup(
        title = stringResource(R.string.monochrome_provider_health),
        items = healthItems
    )
}
