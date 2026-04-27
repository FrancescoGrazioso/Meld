/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.music.R
import com.metrolist.music.constants.ListThumbnailSize
import com.metrolist.music.constants.ThumbnailCornerRadius
import com.metrolist.spotify.models.SpotifyLibraryFolder

/**
 * List row for a Spotify library folder. Visually mirrors [PlaylistListItem] so the
 * mixed list of folders and playlists in the library reads as one cohesive list,
 * with a folder glyph instead of a thumbnail mosaic.
 */
@Composable
fun SpotifyFolderListItem(
    folder: SpotifyLibraryFolder,
    modifier: Modifier = Modifier,
) = ListItem(
    title = folder.name,
    subtitle = folder.totalChildren.takeIf { it > 0 }?.let { count ->
        pluralStringResource(R.plurals.n_playlist, count, count)
    },
    thumbnailContent = {
        Box(
            modifier = Modifier
                .size(ListThumbnailSize)
                .clip(RoundedCornerShape(ThumbnailCornerRadius))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.folder),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(ListThumbnailSize)
                    .padding(12.dp),
            )
        }
    },
    modifier = modifier,
)

/** Grid variant of [SpotifyFolderListItem]. */
@Composable
fun SpotifyFolderGridItem(
    folder: SpotifyLibraryFolder,
    modifier: Modifier = Modifier,
    fillMaxWidth: Boolean = false,
) = GridItem(
    title = {
        Text(
            text = folder.name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    },
    subtitle = {
        if (folder.totalChildren > 0) {
            Text(
                text = pluralStringResource(R.plurals.n_playlist, folder.totalChildren, folder.totalChildren),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    },
    thumbnailContent = {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(ThumbnailCornerRadius))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.folder),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(32.dp),
            )
        }
    },
    fillMaxWidth = fillMaxWidth,
    modifier = modifier,
)
