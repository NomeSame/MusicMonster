package com.nomesame.musicmonster.extensions

/** Names are untrusted metadata, never paths. */
object ExtensionNames {
    private val audioExtensions = setOf("mp4", "mp3", "wav", "flac", "m4a", "aac", "ogg", "opus", "wma", "aiff", "amr")
    fun valid(name: String): Boolean = name.length in 5..180 && name == name.trim() &&
        !name.startsWith('.') && name.none { it.isISOControl() || it in "/\\:*?\"<>|" } &&
        name.substringAfterLast('.', "").lowercase() in audioExtensions

    fun available(name: String, existing: Set<String>): String {
        require(valid(name))
        val names = existing.map { it.lowercase() }.toSet()
        if (name.lowercase() !in names) return name
        val stem = name.substringBeforeLast('.')
        val suffix = name.substringAfterLast('.')
        return (1..9999).asSequence().map { "$stem ($it).$suffix" }
            .first { it.lowercase() !in names }
    }
}
