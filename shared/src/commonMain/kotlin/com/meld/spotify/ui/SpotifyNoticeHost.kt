package com.meld.spotify.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.meld.spotify.SpotifyNotice
import com.meld.spotify.SpotifyNotices
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.copied
import com.metrolist.shared.generated.resources.meld_spotify_download_nothing_new
import com.metrolist.shared.generated.resources.meld_spotify_notice_download_started
import com.metrolist.shared.generated.resources.meld_spotify_notice_failed
import com.metrolist.shared.generated.resources.meld_spotify_notice_liked
import com.metrolist.shared.generated.resources.meld_spotify_notice_no_match
import com.metrolist.shared.generated.resources.meld_spotify_notice_nothing_to_play
import com.metrolist.shared.generated.resources.meld_spotify_notice_playing_next
import com.metrolist.shared.generated.resources.meld_spotify_notice_queued
import com.metrolist.shared.generated.resources.meld_spotify_notice_unliked
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private const val SHOWN_MILLIS = 2_500L

/** Shows each [SpotifyNotices] message for a moment, low in the window and above the player bar. */
@Composable
internal fun SpotifyNoticeHost() {
    var shown by remember { mutableStateOf<SpotifyNotice?>(null) }
    LaunchedEffect(Unit) {
        SpotifyNotices.events.collectLatest {
            shown = it
            delay(SHOWN_MILLIS)
            shown = null
        }
    }
    val notice = shown ?: return
    // Not focusable: the screen under it stays usable while the message shows.
    Popup(popupPositionProvider = LowInTheWindow, properties = PopupProperties(focusable = false)) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shadowElevation = 6.dp,
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            Text(stringResource(notice.text()), Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
        }
    }
}

/** Centred, a sixth of the window above its bottom edge, wherever the host was placed. */
private object LowInTheWindow : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ) = IntOffset(
        (windowSize.width - popupContentSize.width) / 2,
        windowSize.height - popupContentSize.height - windowSize.height / 6,
    )
}

private fun SpotifyNotice.text(): StringResource =
    when (this) {
        SpotifyNotice.PLAYING_NEXT -> Res.string.meld_spotify_notice_playing_next
        SpotifyNotice.QUEUED -> Res.string.meld_spotify_notice_queued
        SpotifyNotice.DOWNLOAD_STARTED -> Res.string.meld_spotify_notice_download_started
        SpotifyNotice.NOTHING_NEW -> Res.string.meld_spotify_download_nothing_new
        SpotifyNotice.NO_MATCH -> Res.string.meld_spotify_notice_no_match
        SpotifyNotice.NOTHING_TO_PLAY -> Res.string.meld_spotify_notice_nothing_to_play
        SpotifyNotice.COPIED -> Res.string.copied
        SpotifyNotice.LIKED -> Res.string.meld_spotify_notice_liked
        SpotifyNotice.UNLIKED -> Res.string.meld_spotify_notice_unliked
        SpotifyNotice.FAILED -> Res.string.meld_spotify_notice_failed
    }
