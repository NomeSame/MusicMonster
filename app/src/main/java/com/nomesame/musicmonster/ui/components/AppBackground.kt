package com.nomesame.musicmonster.ui.components

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.nomesame.musicmonster.R
import com.nomesame.musicmonster.ui.theme.decodeSampledBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads the background image at [uri], downscaled for display, off the main
 * thread. Re-decodes only when [uri] changes; null clears it.
 */
@Composable
fun rememberBackgroundBitmap(uri: Uri?): ImageBitmap? {
    val context = LocalContext.current
    val state = produceState<ImageBitmap?>(initialValue = null, uri) {
        value = if (uri == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    decodeSampledBitmap(context, uri, DISPLAY_SAMPLE_DIM)?.asImageBitmap()
                }.getOrNull()
            }
        }
    }
    return state.value
}

/**
 * Full-screen background image cropped to fill, with a black scrim at [scrim]
 * alpha so the UI stays readable. Uses the user's [bitmap] if present; otherwise
 * the bundled default image when [useDefaultImage] is true. Renders nothing when
 * there is no image to show (caller falls back to the gradient). Stateless.
 */
@Composable
fun AppBackground(
    bitmap: ImageBitmap?,
    useDefaultImage: Boolean,
    scrim: Float,
    modifier: Modifier = Modifier
) {
    val painter = when {
        bitmap != null -> BitmapPainter(bitmap)
        useDefaultImage -> painterResource(R.drawable.background_screen2)
        else -> return
    }
    Box(modifier = modifier.fillMaxSize()) {
        Image(
            painter = painter,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = scrim.coerceIn(0f, 1f)))
        )
    }
}

private const val DISPLAY_SAMPLE_DIM = 1440
