package com.nomesame.musicmonster.ui.components

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow

/**
 * Compact control caption: keep the theme/system font size, never grow via wrapping.
 * Ellipsis is visual only; Text semantics still expose the complete translated label.
 * Use for buttons and field labels, not descriptive paragraphs or dialog headings.
 */
@Composable
fun ControlLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        style = style,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis
    )
}
