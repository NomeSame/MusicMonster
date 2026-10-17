package com.nomesame.musicmonster.data

import android.content.ComponentName
import android.content.Context
import android.net.Uri

data class PendingExtension(val output: Uri, val tree: Uri, val component: ComponentName, val id: String)

/** Storage boundary for a single delegated output; URI permissions are not backed up. */
class ExtensionJournal(context: Context) {
    private val prefs = context.getSharedPreferences("extension_output", Context.MODE_PRIVATE)
    fun read(): PendingExtension? = runCatching {
        if (!prefs.contains("uri")) return null
        PendingExtension(Uri.parse(requireNotNull(prefs.getString("uri", null))),
            Uri.parse(requireNotNull(prefs.getString("tree", null))),
            requireNotNull(ComponentName.unflattenFromString(requireNotNull(prefs.getString("component", null)))),
            requireNotNull(prefs.getString("request", null)))
    }.getOrNull()

    fun save(pending: PendingExtension): Boolean = prefs.edit().putString("uri", pending.output.toString())
        .putString("tree", pending.tree.toString()).putString("component", pending.component.flattenToString())
        .putString("request", pending.id).commit()

    fun clear(): Boolean = prefs.edit().clear().commit()
}
