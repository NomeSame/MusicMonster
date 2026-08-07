package com.nomesame.musicmonster.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.nomesame.musicmonster.Song
import kotlin.math.roundToInt

/**
 * A draggable scrollbar for the song list. The thumb reflects the current
 * scroll position and its size the visible fraction; dragging anywhere on the
 * track scrolls the list to that position and shows the current letter on the
 * opposite (left) side, with haptic ticks.
 */
@Composable
fun FastScroller(
    lazyListState: LazyListState,
    songs: List<Song>,
    modifier: Modifier = Modifier,
    accent: Color,
    textWarm: Color,
    textMuted: Color
) {
    val total = songs.size
    if (total == 0) return

    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    var isDragging by remember { mutableStateOf(false) }
    var dragLetter by remember { mutableStateOf("") }
    var trackHeightPx by remember { mutableFloatStateOf(1f) }
    var dragY by remember { mutableFloatStateOf(0f) }

    // Current scroll position → thumb offset. The thumb is a short fixed-height
    // grip (not proportional to list length) so it stays compact.
    // firstVisibleItemIndex/layoutInfo change every scroll frame; reading them
    // directly in composition would recompose the whole scroller per frame, so
    // they are hoisted into a derived state that only recomposes on change.
    val progress by remember {
        derivedStateOf {
            val firstVisible = lazyListState.firstVisibleItemIndex
            val visibleCount = lazyListState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
            val maxFirst = (total - visibleCount).coerceAtLeast(1)
            (firstVisible.toFloat() / maxFirst).coerceIn(0f, 1f)
        }
    }
    val thumbHeightPx = with(density) { 40.dp.toPx() }.coerceAtMost(trackHeightPx)

    // "Active" while the finger drags the bar OR the list is scrolling by any
    // means (finger-fling on the list included). The bar grows softly when
    // active and shrinks back to a thin resting state when scrolling stops.
    val active = isDragging || lazyListState.isScrollInProgress
    val trackWidth by animateDpAsState(if (active) 6.dp else 3.dp, label = "trackWidth")
    val thumbWidth by animateDpAsState(if (active) 14.dp else 5.dp, label = "thumbWidth")

    fun jumpTo(y: Float) {
        dragY = y.coerceIn(0f, trackHeightPx)
        val fraction = (y / trackHeightPx).coerceIn(0f, 1f)
        val index = (fraction * (total - 1)).roundToInt().coerceIn(0, total - 1)
        val letter = songs[index].title.firstOrNull()?.uppercase(java.util.Locale.ROOT) ?: ""
        if (letter != dragLetter) {
            dragLetter = letter
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
        lazyListState.requestScrollToItem(index)
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Touch zone (generous) + track + draggable thumb, inset from the right
        // edge and excluded from the system back-gesture so direct taps register.
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(56.dp)
                // Keep the grabbable zone clear of the screen's extreme edge,
                // where Android's back-gesture lives (systemGestureExclusion has
                // a ~200dp/edge cap and can't protect a full-height bar alone).
                .padding(end = 12.dp)
                .systemGestureExclusion()
                .onSizeChanged { trackHeightPx = it.height.toFloat() }
                .pointerInput(songs) {
                    // Grab immediately on touch-down in the zone and release
                    // reliably on up/cancel, so the thumb never gets "stuck".
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        isDragging = true
                        jumpTo(down.position.y)
                        down.consume()
                        // Follow the same finger through every move until it lifts.
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            jumpTo(change.position.y)
                            change.consume()
                            if (!change.pressed) break
                        }
                        isDragging = false
                    }
                }
        ) {
            // Track background.
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(trackWidth)
                    .background(textMuted.copy(alpha = 0.22f), RoundedCornerShape(3.dp))
            )
            // Thumb: short fixed-height grip. While dragging it is pinned
            // exactly under the finger; otherwise it reflects scroll progress.
            val maxOffset = (trackHeightPx - thumbHeightPx).coerceAtLeast(0f)
            val thumbOffsetPx = (
                if (isDragging) dragY - thumbHeightPx / 2f
                else progress * maxOffset
            ).coerceIn(0f, maxOffset)
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(0, thumbOffsetPx.roundToInt()) }
                    .height(with(density) { thumbHeightPx.toDp() })
                    .width(thumbWidth)
                    .background(
                        when {
                            isDragging -> accent
                            active -> textMuted.copy(alpha = 0.85f)
                            else -> textMuted.copy(alpha = 0.65f)
                        },
                        RoundedCornerShape(4.dp)
                    )
            )
        }

        // Large current-letter label on the opposite (left) side while dragging.
        if (isDragging && dragLetter.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 24.dp)
                    .size(96.dp)
                    .background(textWarm.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = dragLetter,
                    style = MaterialTheme.typography.displayLarge,
                    color = accent
                )
            }
        }
    }
}
