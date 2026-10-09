package com.nomesame.musicmonster.ui.components

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.LayoutDirection
import com.nomesame.musicmonster.localization.appLanguageContext
import com.nomesame.musicmonster.model.AppLanguage

/** Recompose strings/plurals immediately without recreating the activity or its UI state. */
@Composable
fun AppLanguageContent(language: AppLanguage, content: @Composable () -> Unit) {
    val base = LocalContext.current
    val configuration = LocalConfiguration.current
    val localized = remember(base, configuration, language) { appLanguageContext(base, language) }
    val localizedConfig = localized.resources.configuration
    CompositionLocalProvider(
        LocalContext provides localized,
        LocalConfiguration provides localizedConfig,
        // Dialogs establish their own Android context/configuration. An explicit
        // resource provider is inherited and keeps all dialog strings localized too.
        LocalResources provides localized.resources,
        LocalLayoutDirection provides if (localizedConfig.layoutDirection == View.LAYOUT_DIRECTION_RTL)
            LayoutDirection.Rtl else LayoutDirection.Ltr,
        content = content
    )
}
