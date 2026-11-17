package com.metrolist.shared.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.meld.branding.MeldAppIcon
import com.meld.branding.meldAppIconChoices
import com.metrolist.shared.settings.Settings
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.alternateIconName
import platform.UIKit.setAlternateIconName
import platform.UIKit.supportsAlternateIcons

private val MeldAppIcon.assetName: String? get() = name.takeUnless { this == MeldAppIcon.DEFAULT }

private object IosAppIconSelection {
    private var changing = false

    fun sync() {
        val actualName = UIApplication.sharedApplication.alternateIconName
        MeldAppIcon.entries.firstOrNull { it.assetName == actualName }?.let {
            Settings.shared.appIcon = it.name
        }
    }

    fun select(
        name: String,
        onError: () -> Unit,
    ) {
        val icon = MeldAppIcon.entries.firstOrNull { it.name == name } ?: return
        val application = UIApplication.sharedApplication
        if (changing || !application.supportsAlternateIcons) return
        if (application.alternateIconName == icon.assetName) {
            sync()
            return
        }
        changing = true
        application.setAlternateIconName(icon.assetName) { error ->
            // UIKit may invoke completion away from the main thread.
            NSOperationQueue.mainQueue.addOperationWithBlock {
                changing = false
                sync()
                if (error != null) onError()
            }
        }
    }
}

@Composable
actual fun rememberIosAppIconActions(onError: () -> Unit): IosAppIconActions {
    val latestError = rememberUpdatedState(onError)
    val supported = remember { UIApplication.sharedApplication.supportsAlternateIcons }
    DisposableEffect(Unit) {
        IosAppIconSelection.sync()
        val observer =
            NSNotificationCenter.defaultCenter.addObserverForName(
                UIApplicationDidBecomeActiveNotification,
                null,
                NSOperationQueue.mainQueue,
            ) { IosAppIconSelection.sync() }
        onDispose { NSNotificationCenter.defaultCenter.removeObserver(observer) }
    }
    val choices = if (supported) meldAppIconChoices() else emptyList()
    return IosAppIconActions(choices) { IosAppIconSelection.select(it) { latestError.value() } }
}
