package com.nomesame.musicmonster.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.nomesame.musicmonster.R
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * A single song row in the library list. Stateless: the caller supplies the
 * display strings, the "is currently playing" flag, colors and click handlers.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    title: String,
    durationLabel: String,
    isCurrent: Boolean,
    textWarm: Color,
    textMuted: Color,
    accent: Color,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    existingCount: Int = 0,
    modifier: Modifier = Modifier
) {
    val highlight = animateColorAsState(
        if (selectionMode && isSelected) accent.copy(alpha = 0.12f) else Color.Transparent,
        label = "songSelectionHighlight"
    ).value
    Row(
        modifier = modifier
            .background(highlight)
            .semantics(mergeDescendants = true) {
                if (selectionMode) {
                    role = Role.Checkbox
                    toggleableState = if (isSelected) ToggleableState.On else ToggleableState.Off
                }
            }
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AnimatedVisibility(
            visible = selectionMode,
            enter = expandHorizontally() + fadeIn(),
            exit = shrinkHorizontally() + fadeOut()
        ) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = null,
                colors = CheckboxDefaults.colors(checkedColor = accent),
                modifier = Modifier.padding(end = 10.dp)
            )
        }
        Icon(
            imageVector = Icons.Default.MusicNote,
            contentDescription = null,
            tint = if (isCurrent) accent else textMuted,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isCurrent) textWarm else textMuted,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (selectionMode && existingCount > 0) {
                Text(
                    text = pluralStringResource(R.plurals.already_in_playlist, existingCount, existingCount),
                    color = accent,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
        Text(
            text = durationLabel,
            style = MaterialTheme.typography.labelMedium,
            color = if (isCurrent) accent else textMuted,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Preview
@Composable
private fun SongRowPreview() {
    SongRow(
        title = stringResource(R.string.preview_song),
        durationLabel = "3:21",
        isCurrent = true,
        textWarm = Color.White,
        textMuted = Color.Gray,
        accent = Color(0xFF80DEEA),
        onClick = {},
        onLongClick = {}
    )
}
