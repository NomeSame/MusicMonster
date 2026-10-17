package com.nomesame.musicmonster.extensions

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.nomesame.musicmonster.extension.api.ExtensionContract as Contract

data class ExtensionAction(val component: ComponentName, val label: String)

/** Discovery is fail-closed; arbitrary installed apps cannot contribute actions. */
class ExtensionRegistry(private val context: Context) {
    fun actions(): List<ExtensionAction> = runCatching {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(Intent(Contract.ACTION), PackageManager.GET_META_DATA)
            .mapNotNull { match ->
                val info = match.activityInfo ?: return@mapNotNull null
                if (!info.enabled || !info.applicationInfo.enabled || !info.exported ||
                    info.permission != Contract.PERMISSION ||
                    info.metaData?.get(Contract.META_VERSION) != Contract.VERSION ||
                    info.metaData?.getString(Contract.META_OUTPUT) != Contract.AUDIO ||
                    pm.checkSignatures(context.packageName, info.packageName) != PackageManager.SIGNATURE_MATCH
                ) return@mapNotNull null
                val label = info.loadLabel(pm).toString().trim()
                if (label.isEmpty() || label.length > 80 || label.any(Char::isISOControl)) return@mapNotNull null
                ExtensionAction(ComponentName(info.packageName, info.name), label)
            }.distinctBy { it.component }.sortedBy { it.label }
    }.getOrDefault(emptyList())

    fun contains(component: ComponentName) = actions().any { it.component == component }
}
