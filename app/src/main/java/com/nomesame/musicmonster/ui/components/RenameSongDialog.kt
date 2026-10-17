package com.nomesame.musicmonster.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.nomesame.musicmonster.R
import com.nomesame.musicmonster.SongRenameRules
import com.nomesame.musicmonster.data.RenameError
import com.nomesame.musicmonster.state.RenameForm

@Composable
fun RenameSongDialog(form: RenameForm, onEdit: (String) -> Unit, onSave: () -> Unit,
    onDismiss: () -> Unit, onPickFolder: () -> Unit) {
    AlertDialog(
        onDismissRequest = { if (!form.busy) onDismiss() },
        title = { Text(stringResource(R.string.rename_song)) },
        text = {
            Column {
                Text(stringResource(R.string.rename_description))
                OutlinedTextField(value = form.input, onValueChange = onEdit,
                    enabled = !form.busy && form.original.isNotEmpty(), singleLine = true,
                    modifier = Modifier.testTag("rename_input"),
                    label = { Text(stringResource(R.string.rename_filename)) },
                    suffix = { Text(SongRenameRules.extension(form.original)) },
                    isError = form.error != null)
                if (form.busy) LinearProgressIndicator()
                form.error?.let { error ->
                    Text(stringResource(when (error) {
                        RenameError.INVALID_NAME -> R.string.rename_invalid
                        RenameError.NAME_EXISTS -> R.string.rename_exists
                        RenameError.READ_ONLY -> R.string.rename_read_only
                        RenameError.MISSING -> R.string.rename_missing
                        RenameError.REFERENCES -> R.string.rename_references
                        RenameError.FAILED -> R.string.rename_failed
                    }), color = MaterialTheme.colorScheme.error)
                    if (error == RenameError.READ_ONLY && form.song.parentUri != null) {
                        TextButton(onClick = { onDismiss(); onPickFolder() }) {
                            Text(stringResource(R.string.rename_choose_folder))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = !form.busy && form.original.isNotEmpty(),
                modifier = Modifier.testTag("rename_confirm")) { Text(stringResource(R.string.rename_song)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !form.busy) { Text(stringResource(android.R.string.cancel)) }
        }
    )
}
