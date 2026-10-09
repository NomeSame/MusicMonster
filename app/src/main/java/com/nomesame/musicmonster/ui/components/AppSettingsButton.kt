package com.nomesame.musicmonster.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.nomesame.musicmonster.R
import com.nomesame.musicmonster.BuildConfig
import com.nomesame.musicmonster.model.AppLanguage

/** Small overflow entry; only transient dialog visibility is owned by the UI. */
@Composable
fun AppSettingsButton(language: AppLanguage, onLanguageChange: (AppLanguage) -> Unit) {
    if (!BuildConfig.SHOW_LANGUAGE_SETTINGS) return
    var open by rememberSaveable { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = Modifier.testTag("app_settings")) {
        Icon(Icons.Default.MoreVert, stringResource(R.string.settings),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.settings)) },
            text = {
                Column(Modifier.selectableGroup()) {
                    Text(stringResource(R.string.app_language), style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(bottom = 8.dp))
                    AppLanguage.entries.forEach { option ->
                        val label = when (option) {
                            AppLanguage.SYSTEM -> R.string.language_system
                            AppLanguage.ENGLISH -> R.string.language_english
                            AppLanguage.GERMAN -> R.string.language_german
                        }
                        Row(
                            Modifier.fillMaxWidth().testTag("language_${option.name}")
                                .selectable(language == option, role = Role.RadioButton,
                                    onClick = { onLanguageChange(option) })
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = language == option, onClick = null)
                            ControlLabel(stringResource(label), Modifier.weight(1f).padding(start = 12.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }, modifier = Modifier.testTag("settings_close")) {
                    ControlLabel(stringResource(R.string.close))
                }
            }
        )
    }
}
