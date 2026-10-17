package com.nomesame.musicmonster.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.nomesame.musicmonster.R
import com.nomesame.musicmonster.extensions.ExtensionAction

/** No contributed action means no placeholder, disabled button or feature-specific UI. */
@Composable
fun ExtensionActions(actions: List<ExtensionAction>, busy: Boolean, failed: Boolean,
    onAction: (ExtensionAction) -> Unit, onCancel: () -> Unit, onDismissError: () -> Unit) {
    if (actions.isEmpty()) return
    if (actions.isNotEmpty()) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.Center) {
            actions.forEach { action ->
                Button(onClick = { onAction(action) }, enabled = !busy,
                    modifier = Modifier.testTag("extension_action")) { Text(action.label) }
            }
        }
    }
    if (busy) Button(onClick = onCancel) { Text(stringResource(R.string.extension_discard)) }
    if (failed) AlertDialog(onDismissRequest = onDismissError,
        text = { Text(stringResource(R.string.extension_failed)) },
        confirmButton = { Button(onClick = onDismissError) { Text(stringResource(android.R.string.ok)) } })
}
