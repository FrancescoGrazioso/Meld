package com.metrolist.shared.ui.navigation

import com.meld.spotify.library.SpotifyCollectionType
import kotlinx.serialization.Serializable

// Meld's routes. They live in this package because `Screen` is sealed; keeping them out of Screen.kt
// keeps that upstream file untouched.

/** One playlist folder of the Spotify library. */
@Serializable
data class SpotifyFolderScreen(
    val folderUri: String,
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
