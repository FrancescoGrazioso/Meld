package com.meld.branding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.meld_easter_egg_message
import com.metrolist.shared.generated.resources.meld_easter_egg_title
import com.metrolist.shared.generated.resources.ok
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.components.AlertDialog
import org.jetbrains.compose.resources.stringResource

/** Counts taps on the app version; every [required]th tap completes a round. */
class MeldVersionTaps(
    private val required: Int = 7,
) {
    private var taps = 0

    fun tap(): Boolean {
        if (++taps < required) return false
        taps = 0
        return true
    }
}

/** Wraps the version shown in About: seven taps unlock the Easter Egg icon and say so. */
@Composable
fun MeldVersionEasterEgg(content: @Composable () -> Unit) {
    val taps = remember { MeldVersionTaps() }
    var found by remember { mutableStateOf(false) }
    Box(
        // No ripple: nothing should hint that the version is tappable.
        Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
            if (taps.tap()) {
                Settings.shared.meldEasterEggUnlocked = true
                found = true
            }
        },
    ) { content() }
    if (found) {
        AlertDialog(
            onDismissRequest = { found = false },
            confirmButton = { TextButton(onClick = { found = false }) { Text(stringResource(Res.string.ok)) } },
            title = { Text(stringResource(Res.string.meld_easter_egg_title)) },
            text = { Text(stringResource(Res.string.meld_easter_egg_message)) },
        )
    }
}
