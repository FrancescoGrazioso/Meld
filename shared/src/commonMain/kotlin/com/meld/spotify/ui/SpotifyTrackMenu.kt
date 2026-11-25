package com.meld.spotify.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalClipboard
import com.meld.spotify.SpotifyNotice
import com.meld.spotify.SpotifyNotices
import com.meld.spotify.SpotifyTrack
import com.meld.spotify.download.SpotifyDownloads
import com.meld.spotify.download.notice
import com.meld.spotify.library.toEntry
import com.meld.spotify.library.toLibraryEntry
import com.meld.spotify.likes.SpotifyLikeSync
import com.meld.spotify.playback.SpotifyResolution
import com.meld.spotify.playback.SpotifyTrackResolver
import com.meld.spotify.playback.SpotifyVersionPicker
import com.metrolist.shared.ui.navigation.Screen
import com.metrolist.shared.ui.playback.LocalPlaybackController
import com.metrolist.shared.ui.utils.setText
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform

/**
 * What the menu of a Spotify track does beyond queueing it, wherever the track is shown. An action is
 * null where the app has nothing to do it with; those that play need [canPlay].
 */
internal class SpotifyTrackMenu(
    val startRadio: ((SpotifyTrack) -> Unit)?,
    val download: ((SpotifyTrack) -> Unit)?,
    val setLiked: ((SpotifyTrack, Boolean) -> Unit)?,
    val chooseVersion: ((SpotifyTrack) -> Unit)?,
    val copyLink: (SpotifyTrack) -> Unit,
    val openAlbum: (SpotifyTrack) -> Unit,
    val openArtist: (SpotifyTrack) -> Unit,
)

@Composable
internal fun rememberSpotifyTrackMenu(
    canPlay: Boolean,
    onOpen: (Screen) -> Unit,
): SpotifyTrackMenu {
    val koin = remember { KoinPlatform.getKoinOrNull() }
    val resolver = remember { koin?.getOrNull<SpotifyTrackResolver>() }
    val downloads = remember { koin?.getOrNull<SpotifyDownloads>() }
    val likeSync = remember { koin?.getOrNull<SpotifyLikeSync>() }
    val versions = remember { koin?.getOrNull<SpotifyVersionPicker>() }
    val player = LocalPlaybackController.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    return SpotifyTrackMenu(
        // The radio YouTube Music makes from the song that plays for the track.
        startRadio =
            resolver?.takeIf { canPlay }?.let { resolver ->
                { track ->
                    scope.launch {
                        val song = (resolver.resolve(track) as? SpotifyResolution.Matched)?.song
                        if (song != null) player.startRadio(song) else SpotifyNotices.show(SpotifyNotice.NO_MATCH)
                    }
                }
            },
        download =
            downloads?.let { downloads ->
                { track -> scope.launch { SpotifyNotices.show(downloads.download(listOf(track)).notice()) } }
            },
        setLiked = likeSync?.let { sync -> { track, liked -> sync.setLiked(track, liked) } },
        chooseVersion = versions?.takeIf { canPlay }?.let { picker -> { track -> picker.open(track) } },
        copyLink = { track ->
            scope.launch {
                clipboard.setText("https://open.spotify.com/track/${track.id}")
                SpotifyNotices.show(SpotifyNotice.COPIED)
            }
        },
        openAlbum = { track -> track.album?.let { onOpen(it.toLibraryEntry().screen()) } },
        openArtist = { track -> track.artists.firstOrNull()?.let { onOpen(it.toEntry().screen()) } },
    )
}
