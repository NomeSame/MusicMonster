package com.nomesame.musicmonster.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.nomesame.musicmonster.R
import com.nomesame.musicmonster.state.ImportErrorCode
import com.nomesame.musicmonster.state.ImportState
import com.nomesame.musicmonster.ui.ImportText
import com.nomesame.musicmonster.ui.theme.LocalAppColors

/** Upper bound for the accepted link; longer input is truncated in the field, not rejected. */
const val MAX_IMPORT_URL_LENGTH = 2048

/**
 * Stateless import surface: link input, submit, live progress/cancel and the outcome text.
 * It receives the [ImportState] and forwards events — no binder, job, SAF or filename knowledge.
 * All visible text and accessibility labels come from `stringResource` (EN/DE), never hardcoded.
 *
 * Exactly one job per confirmation: the submit button is disabled while [ImportState.Active], so
 * a second tap cannot start a parallel job; the state layer refuses it anyway.
 */
@Composable
fun ImportPanel(
    state: ImportState,
    url: String,
    onUrlChange: (String) -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onPickFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = state is ImportState.Active
    val colors = LocalAppColors.current
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = modifier.fillMaxWidth().testTag("import_panel"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = url,
            onValueChange = { onUrlChange(it.take(MAX_IMPORT_URL_LENGTH)) },
            label = { Text(stringResource(R.string.import_url_label)) },
            placeholder = { Text(stringResource(R.string.import_url_placeholder)) },
            singleLine = true,
            enabled = !active,
            modifier = Modifier.fillMaxWidth().testTag("import_url_field"),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onStart,
                enabled = !active,
                modifier = Modifier.weight(1f).testTag("import_submit"),
            ) {
                ControlLabel(stringResource(R.string.import_submit))
            }
            OutlinedButton(
                onClick = {
                    clipboard.getText()?.text?.takeIf { it.isNotBlank() }?.let {
                        onUrlChange(it.take(MAX_IMPORT_URL_LENGTH))
                    }
                },
                enabled = !active,
                modifier = Modifier.weight(1f).testTag("import_paste"),
            ) {
                ControlLabel(stringResource(R.string.import_paste))
            }
        }
        if (active) {
            TextButton(onClick = onCancel, modifier = Modifier.testTag("import_cancel")) {
                ControlLabel(stringResource(R.string.cancel))
            }
        }
        when (state) {
            is ImportState.Active -> ImportProgress(state)
            is ImportState.Succeeded -> Text(
                text = stringResource(R.string.import_success, state.displayName),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary,
                modifier = Modifier.testTag("import_success"),
            )
            is ImportState.Failed -> ImportFailure(state.errorCode, onPickFolder)
            ImportState.Cancelled -> Text(
                text = stringResource(R.string.import_cancelled),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textMuted,
                modifier = Modifier.testTag("import_cancelled"),
            )
            ImportState.Idle, ImportState.Unavailable -> Unit
        }
    }
}

/** Determinate progress plus the localized phase label; cancel lives next to the submit button. */
@Composable
private fun ImportProgress(state: ImportState.Active) {
    val colors = LocalAppColors.current
    val progress = state.progress.coerceIn(0, 100)
    val progressDescription = stringResource(R.string.import_progress_description, progress)
    Row(
        modifier = Modifier.fillMaxWidth().testTag("import_progress"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            progress = { progress / 100f },
            color = colors.accent,
            trackColor = colors.divider,
            modifier = Modifier.semantics { contentDescription = progressDescription },
        )
        Column {
            Text(
                text = stringResource(ImportText.phase(state.state)),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary,
            )
            Text(
                text = stringResource(R.string.import_progress, progress),
                style = MaterialTheme.typography.labelMedium,
                color = colors.textMuted,
            )
        }
    }
}

/** Localized failure text; an unwritable/missing target additionally offers the folder picker. */
@Composable
private fun ImportFailure(errorCode: Int, onPickFolder: () -> Unit) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier.fillMaxWidth().testTag("import_error"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(ImportText.error(errorCode)),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textPrimary,
        )
        if (errorCode == ImportErrorCode.TARGET_NOT_WRITABLE || errorCode == ImportErrorCode.NO_TARGET) {
            Button(onClick = onPickFolder, modifier = Modifier.testTag("import_pick_folder")) {
                ControlLabel(stringResource(R.string.import_pick_folder))
            }
        }
    }
}

/**
 * Dialog wrapper: only the transient visibility lives in the screen (`rememberSaveable`); the
 * typed URL is kept here as `rememberSaveable`, the import state always comes from the caller.
 */
@Composable
fun ImportDialog(
    state: ImportState,
    onStart: (String) -> Unit,
    onCancel: () -> Unit,
    onPickFolder: () -> Unit,
    onDismiss: () -> Unit,
) {
    var url by rememberSaveable { mutableStateOf("") }
    val colors = LocalAppColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.panel,
        title = { Text(stringResource(R.string.import_url_title), color = colors.textPrimary) },
        text = {
            ImportPanel(
                state = state,
                url = url,
                onUrlChange = { url = it },
                onStart = { onStart(url) },
                onCancel = onCancel,
                onPickFolder = onPickFolder,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("import_close")) {
                ControlLabel(stringResource(R.string.close), color = colors.accent)
            }
        },
    )
}
