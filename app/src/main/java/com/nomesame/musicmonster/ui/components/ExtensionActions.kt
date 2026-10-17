package com.nomesame.musicmonster.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.nomesame.musicmonster.R
import com.nomesame.musicmonster.extensions.ExtensionAction
import com.nomesame.musicmonster.ui.theme.LocalAppColors

/** The module supplies its icon and accessible label; the player only hosts toolbar actions. */
@Composable
fun ExtensionActions(actions: List<ExtensionAction>, busy: Boolean, failed: Boolean,
    onAction: (ExtensionAction) -> Unit, onCancel: () -> Unit, onDismissError: () -> Unit) {
    if (actions.isEmpty()) return
    val context = LocalContext.current
    val colors = LocalAppColors.current
    Row {
        actions.forEach { action ->
            val icon = remember(context, action.iconResource) {
                runCatching { context.getDrawable(action.iconResource)?.toBitmap(72, 72)?.asImageBitmap() }.getOrNull()
            }
            IconButton(onClick = { onAction(action) }, enabled = !busy,
                modifier = Modifier.size(48.dp).testTag("extension_action")) {
                if (icon != null) Icon(icon, action.label, Modifier.size(24.dp), tint = colors.accent)
                else Icon(Icons.Default.Extension, action.label, tint = colors.accent)
            }
        }
        if (busy) IconButton(onClick = onCancel, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.Close, stringResource(R.string.extension_discard), tint = colors.accent)
        }
    }
    if (failed) AlertDialog(onDismissRequest = onDismissError, containerColor = colors.panel,
        text = { Text(stringResource(R.string.extension_failed), color = colors.textPrimary) },
        confirmButton = { Button(onClick = onDismissError) { Text(stringResource(android.R.string.ok)) } })
}
