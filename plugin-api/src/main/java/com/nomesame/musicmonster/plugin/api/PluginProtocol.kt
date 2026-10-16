package com.nomesame.musicmonster.plugin.api

/** Stable wire constants shared by separately built APKs. Never renumber existing messages.
 * Version 1 is preparation only: no import capability, URLs, paths or URI grants.
 * Additive changes keep VERSION; incompatible wire changes require a new action/version.
 */
object PluginProtocol {
    const val HOST_PACKAGE = "com.nomesame.musicmonster"
    const val BIND_ACTION = "$HOST_PACKAGE.plugin.BIND_V1"
    const val PERMISSION = "$HOST_PACKAGE.permission.BIND_PLUGIN"
    const val META_VERSION = "$HOST_PACKAGE.plugin.PROTOCOL_VERSION"
    const val VERSION = 1
    const val HELLO = 1
    const val HELLO_REPLY = 2
    const val READY = 0
    const val INCOMPATIBLE = 1
    const val TIMEOUT_MS = 3_000L

    // Message.arg1 = protocol version, arg2 = status on replies; no Bundle/Parcelable payloads.
    fun accepts(version: Int): Boolean = version == VERSION
}
