package com.nomesame.musicmonster.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.nomesame.musicmonster.R
import com.nomesame.musicmonster.SeekStep

/** Scrollable choices; selection is saved immediately by the owning state layer. */
@Composable
fun SeekIntervalPicker(
    seconds: Int,
    onChange: (Int) -> Unit,
    buttonsEnabled: Boolean,
    onButtonsEnabledChange: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex =
        (SeekStep.options.indexOf(seconds).coerceAtLeast(0) - 2).coerceAtLeast(0))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            val switchLabel = stringResource(R.string.seek_buttons_enabled)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.seek_interval), Modifier.weight(1f))
                Switch(checked = buttonsEnabled, onCheckedChange = onButtonsEnabledChange,
                    modifier = Modifier.padding(start = 12.dp).testTag("seek_buttons_switch")
                        .semantics { contentDescription = switchLabel })
            }
        },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)
                .selectableGroup().testTag("seek_interval_list"), state = state) {
                items(SeekStep.options, key = { it }) { option ->
                    Row(Modifier.fillMaxWidth().testTag("seek_seconds_$option")
                        .selectable(seconds == option, role = Role.RadioButton,
                            onClick = { onChange(option) })
                        .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = seconds == option, onClick = null)
                        ControlLabel(stringResource(R.string.seek_seconds, option),
                            Modifier.weight(1f).padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("seek_picker_done")) {
                ControlLabel(stringResource(R.string.done))
            }
        }
    )
}
