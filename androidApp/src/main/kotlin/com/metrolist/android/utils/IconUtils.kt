/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.android.utils

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.meld.branding.MeldAppIcon
import com.meld.branding.meldAppIcon
import com.metrolist.shared.settings.Settings

private const val MANIFEST_PACKAGE = "com.metrolist.android"

// Aliases of icons that left the set. The manifest still declares them, disabled, so that an
// install which had one enabled keeps a launcher entry until the first sync moves it to Default.
internal val LEGACY_ALIAS_NAMES =
    listOf(
        "MainActivityDefault",
        "MainActivityDynamic",
        "MainActivityDynamicBg",
        "MainActivityArcticHaze",
        "MainActivityLavenderLeap",
        "MainActivityMetromallow",
        "MainActivityPrideSpecial",
        "MainActivityKayeStatic",
        "MainActivityZenithBlack",
        "MainActivityZenithWhite",
        "MainActivityGradient",
    )

// Default keeps upstream's always-enabled alias, so existing home-screen shortcuts survive.
internal val MeldAppIcon.aliasName: String
    get() =
        when (this) {
            MeldAppIcon.DEFAULT -> "MainActivityOriginal"
            MeldAppIcon.AMBER -> "MainActivityAmber"
            MeldAppIcon.CRIMSON -> "MainActivityCrimson"
            MeldAppIcon.MONOCHROME -> "MainActivityMonochrome"
            MeldAppIcon.RAINBOWS -> "MainActivityRainbows"
            MeldAppIcon.VIOLET -> "MainActivityViolet"
            MeldAppIcon.LEGACY -> "MainActivityLegacy"
            MeldAppIcon.EASTER_EGG -> "MainActivityEasterEgg"
        }

private val MeldAppIcon.enabledByDefault: Boolean
    get() = this == MeldAppIcon.DEFAULT

object IconUtils {
    @Synchronized
    fun syncWithPreference(context: Context) = setIcon(context, Settings.shared.meldAppIcon)

    @Synchronized
    fun setIcon(
        context: Context,
        icon: MeldAppIcon,
    ) {
        val pm = context.packageManager
        val selectedComponent = icon.componentName(context)

        // Enable before disabling, so the app is never left without a launcher entry.
        if (!pm.isEnabled(selectedComponent, icon.enabledByDefault)) {
            pm.setComponentEnabledSetting(
                selectedComponent,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
        }

        MeldAppIcon.entries.filterNot { it == icon }.forEach { other ->
            val component = other.componentName(context)
            if (pm.isEnabled(component, other.enabledByDefault)) {
                pm.setComponentEnabledSetting(
                    component,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
        }

        LEGACY_ALIAS_NAMES.forEach { aliasName ->
            val component = ComponentName(context, "$MANIFEST_PACKAGE.$aliasName")
            if (pm.getComponentEnabledSetting(component) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                pm.setComponentEnabledSetting(
                    component,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
        }
    }

    private fun MeldAppIcon.componentName(context: Context) = ComponentName(context, "$MANIFEST_PACKAGE.$aliasName")

    private fun PackageManager.isEnabled(
        component: ComponentName,
        enabledByDefault: Boolean,
    ): Boolean =
        when (getComponentEnabledSetting(component)) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> enabledByDefault
            else -> false
        }
}
