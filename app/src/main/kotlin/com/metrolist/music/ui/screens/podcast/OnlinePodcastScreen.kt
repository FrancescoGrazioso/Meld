package com.metrolist.music.ui.screens.podcast

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.metrolist.innertube.models.EpisodeItem
import com.metrolist.innertube.models.PodcastItem
import timber.log.Timber
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.db.entities.PodcastEntity
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.LocalMenuState
import com.metrolist.music.ui.component.YouTubeListItem
import com.metrolist.music.ui.menu.YouTubeSongMenu
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.viewmodels.OnlinePodcastViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OnlinePodcastScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: OnlinePodcastViewModel = hiltViewModel(),
) {
    val menuState = LocalMenuState.current
    val haptic = LocalHapticFeedback.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val database = LocalDatabase.current

    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()

    val podcast by viewModel.podcast.collectAsState()
    val episodes by viewModel.episodes.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val libraryPodcast by viewModel.libraryPodcast.collectAsState()

    val lazyListState = rememberLazyListState()

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = lazyListState,
            contentPadding = LocalPlayerAwareWindowInsets.current.union(WindowInsets(0)).asPaddingValues(),
        ) {
            if (podcast == null && isLoading) {
                item(key = "loading_placeholder") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        ContainedLoadingIndicator()
                    }
                }
            } else if (error != null) {
                item(key = "error") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = error ?: stringResource(R.string.error_unknown),
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center
                        )
                        Button(onClick = { viewModel.retry() }) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
            } else {
                podcast?.let { podcastItem ->
                    item(key = "podcast_header") {
                        val context = LocalContext.current
                        PodcastHeader(
                            podcast = podcastItem,
                            episodeCount = episodes.size,
                            inLibrary = libraryPodcast?.inLibrary == true,
                            onLibraryClick = { viewModel.toggleLibrary(context) }
                        )
                    }

                    items(
                        items = episodes,
                        key = { it.id }
                    ) { episode ->
                        YouTubeListItem(
                            item = episode,
                            isActive = mediaMetadata?.id == episode.id,
                            isPlaying = isPlaying,
                            modifier = Modifier
                                .combinedClickable(
                                    onClick = {
                                        if (episode.id == mediaMetadata?.id) {
                                            playerConnection.togglePlayPause()
                                        } else {
                                            val episodeIndex = episodes.indexOfFirst { it.id == episode.id }
                                            Timber.d("Playing episode: ${episode.title}, index: $episodeIndex, total episodes: ${episodes.size}")
                                            val mediaItems = episodes.map { it.toMediaMetadata().toMediaItem() }
                                            Timber.d("Created ${mediaItems.size} media items for queue")
                                            playerConnection.playQueue(
                                                ListQueue(
                                                    title = podcast?.title,
                                                    items = mediaItems,
                                                    startIndex = if (episodeIndex >= 0) episodeIndex else 0
                                                )
                                            )
                                        }
                                    },
                                    onLongClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        menuState.show {
                                            YouTubeSongMenu(episode.asSongItem(), navController, menuState::dismiss)
                                        }
                                    }
                                )
                                .animateItem(),
                            trailingContent = {
                                IconButton(onClick = {
                                    menuState.show {
                                        YouTubeSongMenu(episode.asSongItem(), navController, menuState::dismiss)
                                    }
                                }) {
                                    Icon(painterResource(R.drawable.more_vert), null)
                                }
                            }
                        )
                    }
                }
            }
        }

        TopAppBar(
            title = {
                if (lazyListState.firstVisibleItemIndex > 0) {
                    Text(podcast?.title ?: "")
                }
            },
            navigationIcon = {
                IconButton(
                    onClick = { navController.navigateUp() },
                    onLongClick = { navController.backToMain() }
                ) {
                    Icon(
                        painter = painterResource(R.drawable.arrow_back),
                        contentDescription = null
                    )
                }
            },
            scrollBehavior = scrollBehavior
        )
    }
}

@Composable
private fun PodcastHeader(
    podcast: PodcastItem,
    episodeCount: Int,
    inLibrary: Boolean,
    onLibraryClick: () -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(podcast.thumbnail)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(200.dp)
                .clip(RoundedCornerShape(8.dp))
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = podcast.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        podcast.author?.name?.let { authorName ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = authorName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = podcast.episodeCountText ?: "$episodeCount episodes",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(
            onClick = onLibraryClick,
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = if (inLibrary)
                    MaterialTheme.colorScheme.secondaryContainer
                else
                    Color.Transparent
            ),
            shape = RoundedCornerShape(50),
            modifier = Modifier.height(40.dp)
        ) {
            Icon(
                painter = painterResource(if (inLibrary) R.drawable.library_add_check else R.drawable.library_add),
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(
                text = stringResource(if (inLibrary) R.string.remove_from_library else R.string.add_to_library)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
