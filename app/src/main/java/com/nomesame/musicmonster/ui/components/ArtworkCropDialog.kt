package com.nomesame.musicmonster.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.nomesame.musicmonster.ArtworkCropGeometry
import com.nomesame.musicmonster.R
import com.nomesame.musicmonster.model.ArtworkCrop
import com.nomesame.musicmonster.ui.theme.LocalAppColors

/** Stateless crop editor. The preview and service use the same square-crop geometry. */
@Composable
fun ArtworkCropDialog(
    image: Bitmap?,
    loading: Boolean,
    position: ArtworkCrop,
    scrim: Float,
    onPositionChange: (ArtworkCrop) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalAppColors.current
    val currentPosition by rememberUpdatedState(position)
    val changePosition by rememberUpdatedState(onPositionChange)
    val previewDescription = stringResource(R.string.card_crop_preview)
    val cropSide = image?.let { ArtworkCropGeometry.rectangle(it.width, it.height, position).side }
    val bitmap = remember(image) { image?.asImageBitmap() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.panel,
        title = { Text(stringResource(R.string.card_image_crop), color = colors.textPrimary) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.card_crop_description), color = colors.textMuted)
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (image != null && bitmap != null) {
                        Canvas(Modifier.fillMaxWidth(0.7f).aspectRatio(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, colors.accent, RoundedCornerShape(12.dp))
                            .testTag("artwork_crop_preview")
                            .semantics { contentDescription = previewDescription }
                            .pointerInput(image) {
                                detectDragGestures { event, delta ->
                                    event.consume()
                                    changePosition(ArtworkCropGeometry.drag(currentPosition,
                                        delta.x, delta.y, size.width.toFloat(), image.width, image.height))
                                }
                            }) {
                            val rect = ArtworkCropGeometry.rectangle(image.width, image.height, position)
                            drawImage(bitmap, srcOffset = IntOffset(rect.left, rect.top),
                                srcSize = IntSize(rect.side, rect.side),
                                dstSize = IntSize(size.width.toInt(), size.height.toInt()))
                            drawRect(Color.Black.copy(alpha = if (scrim.isFinite()) scrim.coerceIn(0f, 1f) else 0.75f))
                        }
                    } else if (loading) {
                        CircularProgressIndicator(Modifier.padding(24.dp))
                    } else {
                        Text(stringResource(R.string.card_crop_unavailable), color = colors.textMuted)
                    }
                }
                ControlLabel(stringResource(R.string.card_crop_zoom, position.zoom), color = colors.textMuted)
                Slider(value = position.zoom, valueRange = 1f..4f,
                    onValueChange = { onPositionChange(position.copy(zoom = it)) },
                    enabled = image != null, modifier = Modifier.testTag("artwork_crop_zoom"),
                    colors = SliderDefaults.colors(thumbColor = colors.accent,
                        activeTrackColor = colors.accent, inactiveTrackColor = colors.divider))
                ControlLabel(stringResource(R.string.card_crop_horizontal), color = colors.textMuted)
                Slider(value = position.x,
                    onValueChange = { onPositionChange(position.copy(x = it)) },
                    enabled = image != null && image.width > (cropSide ?: image.width),
                    modifier = Modifier.testTag("artwork_crop_x"),
                    colors = SliderDefaults.colors(thumbColor = colors.accent,
                        activeTrackColor = colors.accent, inactiveTrackColor = colors.divider))
                ControlLabel(stringResource(R.string.card_crop_vertical), color = colors.textMuted)
                Slider(value = position.y,
                    onValueChange = { onPositionChange(position.copy(y = it)) },
                    enabled = image != null && image.height > (cropSide ?: image.height),
                    modifier = Modifier.testTag("artwork_crop_y"),
                    colors = SliderDefaults.colors(thumbColor = colors.accent,
                        activeTrackColor = colors.accent, inactiveTrackColor = colors.divider))
            }
        },
        dismissButton = {
            TextButton(onClick = { onPositionChange(ArtworkCrop()) }) {
                ControlLabel(stringResource(R.string.card_crop_center), color = colors.accent)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                ControlLabel(stringResource(R.string.done), color = colors.accent)
            }
        }
    )
}
