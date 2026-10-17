package com.nomesame.musicmonster

import java.util.Locale

/** Validate one filename, never a path. The original audio extension is preserved. */
object SongRenameRules {
    fun extension(name: String): String = name.substringAfterLast('.', "").let {
        if (it.isEmpty() || !name.contains('.')) "" else ".$it"
    }

    fun baseName(name: String): String = name.removeSuffix(extension(name))

    fun fileName(input: String, original: String): String? {
        val suffix = extension(original)
        val trimmed = input.trim()
        val base = if (suffix.isNotEmpty() && trimmed.endsWith(suffix, ignoreCase = true))
            trimmed.dropLast(suffix.length).trim() else trimmed
        if (base.isBlank() || base in listOf(".", "..") || base.endsWith('.')) return null
        if (base.any { it.isISOControl() || it in "\\/:*?\"<>|" }) return null
        val reserved = base.substringBefore('.').uppercase(Locale.ROOT)
        if (reserved in listOf("CON", "PRN", "AUX", "NUL") || reserved.matches(Regex("(COM|LPT)[1-9]"))) return null
        return (base + suffix).takeIf { it.toByteArray(Charsets.UTF_8).size <= 240 }
    }
}
