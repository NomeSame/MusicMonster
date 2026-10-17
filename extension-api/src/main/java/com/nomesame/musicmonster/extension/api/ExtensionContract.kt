package com.nomesame.musicmonster.extension.api

/**
 * Optional signed document producers. The host supplies exactly one writable document
 * in Intent.data/ClipData, with a temporary WRITE grant, never a directory grant.
 * The extension owns its UI and processing. RESULT_OK echoes version/request and
 * supplies a safe playable-media filename after closing the output. Cancellation returns no result.
 * Existing v1 APKs remain usable across host updates; incompatible versions stay hidden.
 */
object ExtensionContract {
    const val VERSION = 1
    const val ACTION = "com.nomesame.musicmonster.extension.PRODUCE_DOCUMENT_V1"
    const val PERMISSION = "com.nomesame.musicmonster.permission.BIND_PLUGIN"
    const val META_VERSION = "com.nomesame.musicmonster.extension.VERSION"
    const val META_OUTPUT = "com.nomesame.musicmonster.extension.OUTPUT"
    const val AUDIO = "audio"
    // Additive capability: a producer may return audio or MP4 video.
    const val MEDIA = "media"
    const val EXTRA_VERSION = "extension_version"
    const val EXTRA_REQUEST = "extension_request"
    const val EXTRA_NAME = "extension_name"
    const val EXTRA_LANGUAGE = "extension_language"
    // Optional presentation hint; absent in older v1 callers.
    const val EXTRA_ACCENT_COLOR = "extension_accent_color"
}
