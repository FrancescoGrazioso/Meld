/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings.integrations

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.soundcloud.SoundCloudAuth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundCloudSettings(navController: NavController) {
    var clientId by remember { mutableStateOf(SoundCloudAuth.clientId ?: "") }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("SoundCloud Integration") },
            navigationIcon = {
                // IconButton(icon = R.drawable.chevron_back, onClick = { navController.popBackStack() })
            }
        )

        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Configure your SoundCloud client ID.",
                style = MaterialTheme.typography.bodyMedium
            )

            OutlinedTextField(
                value = clientId,
                onValueChange = { clientId = it },
                label = { Text("Client ID") },
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
            )

            Button(
                onClick = {
                    SoundCloudAuth.clientId = clientId.takeIf { it.isNotBlank() }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save Client ID")
            }

            if (clientId.isNotBlank()) {
                 Text(
                    "Status: Connected",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        }
    }
}
