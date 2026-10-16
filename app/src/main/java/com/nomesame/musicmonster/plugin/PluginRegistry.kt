package com.nomesame.musicmonster.plugin

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.nomesame.musicmonster.plugin.api.PluginProtocol

/** Discovery only; never executes plugin code or shares storage grants.
 * Call again before use (and on resume after package changes); do not persist trust decisions.
 */
class PluginRegistry(context: Context) {
    private val packages = context.applicationContext.packageManager
    private val hostPackage = context.applicationContext.packageName

    @Suppress("DEPRECATION")
    fun available(): List<ComponentName> = try {
        packages.queryIntentServices(
            Intent(PluginProtocol.BIND_ACTION), PackageManager.GET_META_DATA
        ).mapNotNull { resolved ->
            val service = resolved.serviceInfo ?: return@mapNotNull null
            val version = service.metaData?.get(PluginProtocol.META_VERSION)
            if (!service.enabled || !service.applicationInfo.enabled || !service.exported ||
                service.permission != PluginProtocol.PERMISSION ||
                version !is Int || !PluginProtocol.accepts(version) ||
                packages.checkSignatures(hostPackage, service.packageName) !=
                    PackageManager.SIGNATURE_MATCH
            ) null else ComponentName(service.packageName, service.name)
        }.distinct().sortedBy { it.flattenToString() }
    } catch (_: RuntimeException) {
        // Package removal/provider errors must not affect the player.
        emptyList()
    }
}
