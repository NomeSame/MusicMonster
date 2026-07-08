package com.example.myapplication.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.example.myapplication.Song
import kotlinx.coroutines.launch
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

    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    var isDragging by remember { mutableStateOf(false) }
    var dragLetter by remember { mutableStateOf("") }
    var trackHeightPx by remember { mutableFloatStateOf(1f) }

    // Current scroll position → thumb offset. The thumb is a short fixed-height
    // grip (not proportional to list length) so it stays compact.
    val firstVisible = lazyListState.firstVisibleItemIndex
    val visibleCount = lazyListState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
    val maxFirst = (total - visibleCount).coerceAtLeast(1)
    val progress = (firstVisible.toFloat() / maxFirst).coerceIn(0f, 1f)
    val thumbHeightPx = with(density) { 40.dp.toPx() }.coerceAtMost(trackHeightPx)

    fun jumpTo(y: Float) {
        val fraction = (y / trackHeightPx).coerceIn(0f, 1f)
        val index = (fraction * (total - 1)).roundToInt().coerceIn(0, total - 1)
        val letter = songs[index].title.firstOrNull()?.uppercase() ?: ""
        if (letter != dragLetter) {
            dragLetter = letter
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
        scope.launch { lazyListState.scrollToItem(index) }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Touch zone (generous) + track + draggable thumb, on the right edge.
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(56.dp)
                .onSizeChanged { trackHeightPx = it.height.toFloat() }
                .pointerInput(total, visibleCount) {
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            isDragging = true
                            jumpTo(offset.y)
                        },
                        onVerticalDrag = { change, _ -> jumpTo(change.position.y) },
                        onDragEnd = { isDragging = false },
                        onDragCancel = { isDragging = false }
                    )
                }
        ) {
            // Track background.
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(if (isDragging) 5.dp else 4.dp)
                    .background(textMuted.copy(alpha = 0.22f), RoundedCornerShape(3.dp))
            )
            // Thumb: short fixed-height grip positioned by scroll progress.
            val thumbOffsetPx = progress * (trackHeightPx - thumbHeightPx)
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(0, thumbOffsetPx.roundToInt()) }
                    .height(with(density) { thumbHeightPx.toDp() })
                    .width(if (isDragging) 7.dp else 5.dp)
                    .background(
                        if (isDragging) accent else textMuted.copy(alpha = 0.65f),
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
