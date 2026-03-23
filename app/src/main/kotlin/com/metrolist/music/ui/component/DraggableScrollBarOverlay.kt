/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

@Composable
fun DraggableScrollbar(
    scrollState: LazyListState,
    modifier: Modifier = Modifier,
    thumbColor: Color = LocalContentColor.current.copy(alpha = 0.8f),
    thumbColorActive: Color = MaterialTheme.colorScheme.secondary,
    thumbHeight: Dp = 72.dp,
    thumbWidth: Dp = 8.dp,
    thumbCornerRadius: Dp = 4.dp,
    trackWidth: Dp = 24.dp,
    minItemCountForScroll: Int = 15,
    minScrollRangeForDrag: Int = 5,
    headerItems: Int = 0
) {
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    var isDragging by remember { mutableStateOf(false) }
    var lastScrollTime by remember { mutableLongStateOf(0L) }
    var smoothedY by remember { mutableFloatStateOf(0f) }
    var smoothedThumbY by remember { mutableFloatStateOf(0f) }
    val animatedThumbY = remember { Animatable(0f) }
    val thumbAlpha = remember { Animatable(0f) }

    val isUserScrolling by remember(scrollState) {
        derivedStateOf { scrollState.isScrollInProgress }
    }

    LaunchedEffect(isUserScrolling, isDragging) {
        if (isUserScrolling || isDragging) {
            thumbAlpha.animateTo(1f, animationSpec = tween(150))
        } else {
            delay(1200L)
            thumbAlpha.animateTo(0f, animationSpec = tween(400))
        }
    }

    val isScrollable by remember {
        derivedStateOf {
            val layoutInfo = scrollState.layoutInfo
            val total = layoutInfo.totalItemsCount
            val visible = layoutInfo.visibleItemsInfo.size
            val contentCount = total - headerItems
            contentCount > minItemCountForScroll && contentCount > visible
        }
    }

    if (!isScrollable) return

    var lastTargetIndex by remember { mutableIntStateOf(-1) }

    BoxWithConstraints(
        modifier = modifier
            .width(trackWidth)
            .fillMaxHeight()
            .pointerInput(scrollState) {
                detectDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        lastTargetIndex = -1
                        val viewportHeight = size.height.toFloat()
                        val constThumbHeight = with(density) { thumbHeight.toPx() }
                        val maxThumbY = viewportHeight - constThumbHeight
                        smoothedThumbY = (offset.y - constThumbHeight / 2).coerceIn(0f, maxThumbY)
                    },
                    onDragEnd = { 
                        isDragging = false
                        lastScrollTime = 0L
                    },
                    onDragCancel = { 
                        isDragging = false 
                        lastScrollTime = 0L
                    }
                ) { change, _ ->
                    val currentTime = System.currentTimeMillis()
                    val viewportHeight = size.height.toFloat()
                    val constThumbHeight = with(density) { thumbHeight.toPx() }
                    val maxThumbY = viewportHeight - constThumbHeight
                    
                    val targetThumbY = (change.position.y - constThumbHeight / 2).coerceIn(0f, maxThumbY)
                    
                    val layoutInfo = scrollState.layoutInfo
                    val totalContentItems = layoutInfo.totalItemsCount - headerItems
                    
                    val thumbSmoothingFactor = when {
                        totalContentItems < 20 -> 0.1f
                        totalContentItems < 50 -> 0.3f
                        else -> 0.7f
                    }
                    
                    smoothedThumbY = smoothedThumbY * (1f - thumbSmoothingFactor) + targetThumbY * thumbSmoothingFactor
                    
                    if (currentTime - lastScrollTime < 40) return@detectDragGestures
                    lastScrollTime = currentTime

                    val visibleItems = layoutInfo.visibleItemsInfo
                    if (visibleItems.isEmpty()) return@detectDragGestures

                    val maxScrollIndex = max(1, totalContentItems - visibleItems.size)

                    if (maxScrollIndex > minScrollRangeForDrag) {
                        val touchProgress = (change.position.y / size.height).coerceIn(0f, 1f)
                        
                        val listSmoothingFactor = when {
                            totalContentItems < 20 -> 0.15f
                            totalContentItems < 50 -> 0.4f
                            else -> 0.8f
                        }
                        
                        smoothedY = smoothedY * (1f - listSmoothingFactor) + touchProgress * listSmoothingFactor
                        
                        val targetFractionalIndex = smoothedY * maxScrollIndex
                        val targetIndex = (headerItems + targetFractionalIndex.toInt())
                            .coerceIn(headerItems, layoutInfo.totalItemsCount - 1)

                        if (abs(targetIndex - lastTargetIndex) >= 1) {
                            lastTargetIndex = targetIndex
                            coroutineScope.launch {
                                try {
                                    scrollState.animateScrollToItem(
                                        index = targetIndex,
                                        scrollOffset = 0
                                    )
                                } catch (e: Exception) {
                                }
                            }
                        }
                    }
                }
            }
    ) {
        val viewportHeight = with(density) { this@BoxWithConstraints.maxHeight.toPx() }
        val constThumbHeight = with(density) { thumbHeight.toPx() }

        val targetThumbY by remember {
            derivedStateOf {
                val layoutInfo = scrollState.layoutInfo
                val visibleItems = layoutInfo.visibleItemsInfo
                if (visibleItems.isEmpty()) return@derivedStateOf 0f

                val totalContentItems = layoutInfo.totalItemsCount - headerItems
                val maxScrollIndex = max(1, totalContentItems - visibleItems.size)
                if (maxScrollIndex <= minScrollRangeForDrag) return@derivedStateOf 0f

                val rawIndex = (scrollState.firstVisibleItemIndex - headerItems).coerceAtLeast(0)
                val scrollProgress = rawIndex.toFloat() / maxScrollIndex

                val maxThumbY = viewportHeight - constThumbHeight
                (scrollProgress * maxThumbY).coerceIn(0f, maxThumbY)
            }
        }

        LaunchedEffect(targetThumbY, isDragging, isUserScrolling, smoothedThumbY) {
            when {
                isDragging -> {
                    animatedThumbY.snapTo(smoothedThumbY)
                }
                isUserScrolling -> {
                    animatedThumbY.snapTo(targetThumbY)
                }
                else -> {
                    animatedThumbY.animateTo(
                        targetValue = targetThumbY,
                        animationSpec = spring(stiffness = 200f, dampingRatio = 1f),
                    )
                }
            }
        }

        Canvas(
            modifier = Modifier
                .width(thumbWidth)
                .fillMaxHeight()
                .align(Alignment.CenterEnd),
        ) {
            val alpha = thumbAlpha.value
            if (alpha <= 0f) return@Canvas
            val color = (if (isDragging) thumbColorActive else thumbColor).copy(alpha = alpha)
            val cornerRadiusPx = thumbCornerRadius.toPx()

            drawRoundRect(
                color = color,
                topLeft = Offset(0f, animatedThumbY.value),
                size = Size(this.size.width, constThumbHeight),
                cornerRadius = CornerRadius(cornerRadiusPx),
            )
        }
    }
}
