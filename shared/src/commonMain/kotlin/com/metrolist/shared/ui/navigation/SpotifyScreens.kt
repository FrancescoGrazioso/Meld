package com.metrolist.shared.ui.navigation

import com.meld.spotify.library.SpotifyCollectionType
import kotlinx.serialization.Serializable

// Meld's routes. They live in this package because `Screen` is sealed; keeping them out of Screen.kt
// keeps that upstream file untouched.

/** The Spotify library, or one playlist folder of it when [folderUri] is set. */
@Serializable
data class SpotifyLibraryScreen(
    val folderUri: String? = null,
    val title: String? = null,
) : Screen()

/** A Spotify track list; [id] is empty for the liked songs. [title] is shown until the list has loaded. */
@Serializable
data class SpotifyTracksScreen(
    val type: SpotifyCollectionType,
    val id: String = "",
    val title: String? = null,
) : Screen()

@Serializable
data object SpotifySettingsScreen : Screen()
