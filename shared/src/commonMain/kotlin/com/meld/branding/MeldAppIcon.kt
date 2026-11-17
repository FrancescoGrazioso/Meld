package com.meld.branding

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.meld_app_icon_amber
import com.metrolist.shared.generated.resources.meld_app_icon_crimson
import com.metrolist.shared.generated.resources.meld_app_icon_default
import com.metrolist.shared.generated.resources.meld_app_icon_easter_egg
import com.metrolist.shared.generated.resources.meld_app_icon_legacy
import com.metrolist.shared.generated.resources.meld_app_icon_monochrome
import com.metrolist.shared.generated.resources.meld_app_icon_rainbows
import com.metrolist.shared.generated.resources.meld_app_icon_violet
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.settings.SettingsChoice
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** The selectable app icons. The enum name is the value saved in the `appIcon` setting. */
enum class MeldAppIcon(
    val label: StringResource,
    val artwork: DrawableResource,
) {
    DEFAULT(Res.string.meld_app_icon_default, Res.drawable.meld_app_icon_default),
    AMBER(Res.string.meld_app_icon_amber, Res.drawable.meld_app_icon_amber),
    CRIMSON(Res.string.meld_app_icon_crimson, Res.drawable.meld_app_icon_crimson),
    MONOCHROME(Res.string.meld_app_icon_monochrome, Res.drawable.meld_app_icon_monochrome),
    RAINBOWS(Res.string.meld_app_icon_rainbows, Res.drawable.meld_app_icon_rainbows),
    VIOLET(Res.string.meld_app_icon_violet, Res.drawable.meld_app_icon_violet),
    LEGACY(Res.string.meld_app_icon_legacy, Res.drawable.meld_app_icon_legacy),
    EASTER_EGG(Res.string.meld_app_icon_easter_egg, Res.drawable.meld_app_icon_easter_egg),
    ;

    companion object {
        fun available(easterEggUnlocked: Boolean): List<MeldAppIcon> = entries.filter { it != EASTER_EGG || easterEggUnlocked }

        /** Any other value, such as an icon that left the set or a locked Easter Egg, is Default. */
        fun fromPreference(
            value: String,
            easterEggUnlocked: Boolean,
        ): MeldAppIcon = available(easterEggUnlocked).firstOrNull { it.name == value } ?: DEFAULT
    }
}

val Settings.meldAppIcon: MeldAppIcon
    get() = MeldAppIcon.fromPreference(appIcon, meldEasterEggUnlocked)

@Composable
fun meldAppIconChoices(): List<SettingsChoice> =
    MeldAppIcon.available(Settings.shared.meldEasterEggUnlocked).map { icon ->
        SettingsChoice(icon.name, stringResource(icon.label)) {
            Image(painterResource(icon.artwork), contentDescription = null, modifier = Modifier.size(44.dp))
        }
    }
