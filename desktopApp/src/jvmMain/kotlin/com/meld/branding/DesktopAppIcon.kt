package com.meld.branding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.window.FrameWindowScope
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.utils.OsUtil
import org.jetbrains.compose.resources.imageResource
import org.jetbrains.compose.resources.painterResource
import java.awt.Image
import java.awt.RenderingHints
import java.awt.Taskbar
import java.awt.image.BufferedImage
import androidx.compose.ui.res.painterResource as classpathPainterResource

private val WINDOW_ICON_SIZES = listOf(16, 20, 24, 32, 40, 48, 64, 128, 256)

/** The chosen app icon, for the window before [MeldWindowIcon] hands the system its sizes. */
@Composable
fun meldWindowIconPainter(): Painter = painterResource(Settings.shared.meldAppIcon.artwork)

/**
 * Keeps the window icon, and with it the taskbar entry of the running app, on the chosen app icon.
 * The icon inside the executable, the installer and the shortcuts is fixed at build time.
 */
@Composable
fun FrameWindowScope.MeldWindowIcon() {
    val artwork = imageResource(Settings.shared.meldAppIcon.artwork)
    LaunchedEffect(artwork) {
        val source = artwork.toAwtImage()
        // One smoothly scaled image per size: the system picks, instead of shrinking the artwork itself.
        window.iconImages = WINDOW_ICON_SIZES.map { source.getScaledInstance(it, it, Image.SCALE_SMOOTH) }
        runCatching {
            val taskbar = Taskbar.getTaskbar()
            // Only the macOS Dock takes an image; its icons sit in 824 of 1024 units.
            if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
                val dock = BufferedImage(1024, 1024, BufferedImage.TYPE_INT_ARGB)
                val graphics = dock.createGraphics()
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                graphics.drawImage(source, 100, 100, 824, 824, null)
                graphics.dispose()
                taskbar.iconImage = dock
            }
        }
    }
}

/** The chosen app icon in the tray; the macOS menu bar takes the plain mark instead. */
@Composable
fun meldTrayIcon(darkMenuBar: Boolean): Painter =
    if (OsUtil.isMac) {
        @Suppress("DEPRECATION")
        classpathPainterResource(if (darkMenuBar) "logo.svg" else "logo_dark.png")
    } else {
        painterResource(Settings.shared.meldAppIcon.artwork)
    }
