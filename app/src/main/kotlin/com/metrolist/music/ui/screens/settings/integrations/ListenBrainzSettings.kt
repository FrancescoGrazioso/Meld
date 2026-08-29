/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * ListenBrainz integration settings screen (issue #283).
 */

package com.metrolist.music.ui.screens.settings.integrations

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavController
import com.metrolist.listenbrainz.ListenBrainz
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.EnableListenBrainzScrobblingKey
import com.metrolist.music.constants.ListenBrainzTokenKey
import com.metrolist.music.constants.ListenBrainzUsernameKey
import com.metrolist.music.constants.ListenBrainzUseNowPlaying
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListenBrainzSettings(
    navController: NavController
) {
    val coroutineScope = rememberCoroutineScope()

    var lbToken by rememberPreference(ListenBrainzTokenKey, "")
    var lbUsername by rememberPreference(ListenBrainzUsernameKey, "")

    val isLoggedIn = remember(lbToken) { lbToken != "" }

    val (useNowPlaying, onUseNowPlayingChange) = rememberPreference(
        key = ListenBrainzUseNowPlaying,
        defaultValue = false
    )

    val (lbScrobbling, onLbScrobblingChange) = rememberPreference(
        key = EnableListenBrainzScrobblingKey,
        defaultValue = false
    )

    var showTokenDialog by rememberSaveable { mutableStateOf(false) }
    var isValidating by rememberSaveable { mutableStateOf(false) }
    var validationError by rememberSaveable { mutableStateOf<String?>(null) }

    val tokenInvalidMessage = stringResource(R.string.listenbrainz_token_invalid)

    if (showTokenDialog) {
        var tempToken by rememberSaveable { mutableStateOf("") }

        AlertDialog(
            properties = DialogProperties(usePlatformDefaultWidth = false),
            onDismissRequest = {
                if (!isValidating) {
                    showTokenDialog = false
                    validationError = null
                }
            },
            title = { Text(stringResource(R.string.login)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.listenbrainz_token_hint),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    OutlinedTextField(
                        value = tempToken,
                        onValueChange = { tempToken = it },
                        label = { Text(stringResource(R.string.listenbrainz_token)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        isError = validationError != null,
                        supportingText = {
                            validationError?.let {
                                Text(it, color = MaterialTheme.colorScheme.error)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (isValidating) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.height(16.dp))
                            Text(
                                text = stringResource(R.string.logging_in),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (tempToken.isBlank()) return@TextButton
                        isValidating = true
                        validationError = null
                        coroutineScope.launch(Dispatchers.IO) {
                            val result = ListenBrainz.validateToken(tempToken.trim())
                            isValidating = false
                            result.onSuccess { response ->
                                if (response.valid) {
                                    lbToken = tempToken.trim()
                                    lbUsername = response.userName ?: ""
                                    showTokenDialog = false
                                } else {
                                    validationError = response.message.ifBlank { tokenInvalidMessage }
                                }
                            }.onFailure { e ->
                                validationError = e.message ?: tokenInvalidMessage
                            }
                        }
                    },
                    enabled = tempToken.isNotBlank() && !isValidating
                ) {
                    Text(stringResource(R.string.login))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showTokenDialog = false
                    validationError = null
                }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Column(
        Modifier
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Top
                )
            )
            .verticalScroll(rememberScrollState())
    ) {
        TopAppBar(
            title = { Text(stringResource(R.string.listenbrainz_integration)) },
            navigationIcon = {
                IconButton(
                    onClick = navController::backToMain,
                    onLongClick = navController::backToMain
                ) {
                    Icon(
                        painterResource(R.drawable.arrow_back),
                        contentDescription = null
                    )
                }
            }
        )

        Material3SettingsGroup(
            title = stringResource(R.string.account),
            items = listOf(
                Material3SettingsItem(
                    title = {
                        Text(
                            text = if (isLoggedIn) lbUsername else stringResource(R.string.not_logged_in),
                            modifier = Modifier.alpha(if (isLoggedIn) 1f else 0.5f),
                        )
                    },
                    trailingContent = {
                        if (isLoggedIn) {
                            OutlinedButton(onClick = {
                                lbToken = ""
                                lbUsername = ""
                                onLbScrobblingChange(false)
                            }) {
                                Text(stringResource(R.string.logout))
                            }
                        } else {
                            OutlinedButton(onClick = { showTokenDialog = true }) {
                                Text(stringResource(R.string.login))
                            }
                        }
                    },
                    icon = painterResource(R.drawable.music_note)
                ),
            )
        )

        Spacer(Modifier.height(8.dp))

        Material3SettingsGroup(
            title = stringResource(R.string.scrobbling_configuration),
            items = listOf(
                Material3SettingsItem(
                    title = { Text(stringResource(R.string.enable_scrobbling)) },
                    trailingContent = {
                        Switch(
                            checked = lbScrobbling && isLoggedIn,
                            onCheckedChange = { checked ->
                                if (checked && !isLoggedIn) {
                                    showTokenDialog = true
                                } else {
                                    onLbScrobblingChange(checked)
                                }
                            },
                            enabled = isLoggedIn || !lbScrobbling
                        )
                    },
                    icon = painterResource(R.drawable.sync)
                ),
                Material3SettingsItem(
                    title = { Text(stringResource(R.string.lastfm_now_playing)) },
                    trailingContent = {
                        Switch(
                            checked = useNowPlaying && lbScrobbling && isLoggedIn,
                            onCheckedChange = { onUseNowPlayingChange(it) },
                            enabled = isLoggedIn && lbScrobbling
                        )
                    },
                    icon = painterResource(R.drawable.sync)
                ),
            )
        )
    }
}