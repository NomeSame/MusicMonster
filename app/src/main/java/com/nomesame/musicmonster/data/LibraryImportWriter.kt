package com.nomesame.musicmonster.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Writes one imported result (a narrow read-only `content://` file delivered by
 * the plugin) into the user's SAF library folder.
 *
 * Layer: **Data**. It only touches storage through [ContentResolver],
 * [DocumentsContract] and [DocumentFile]; it knows nothing about the UI, the
 * library model or the plugin wire contract beyond the plain arguments here.
 *
 * Contract choices (private-plugin/DECISIONS.md §8/§9):
 *  - **Never overwrite.** A free name is found by appending ` (n)` before the
 *    extension of the (sanitized) base name, using [ImportFileNames].
 *  - **A partial copy is never success.** The freshly created document is
 *    deleted again on any failure; only a fully copied, non-empty file is
 *    reported as [WriteResult.Success].
 *  - **No atomic-rename assumption.** SAF does not guarantee that a temporary
 *    document can be renamed into place, so the fallback is create-then-copy and
 *    delete-on-failure rather than "write a temp, rename it".
 *  - The `baseName` comes from the plugin and is **untrusted**, so it is
 *    sanitized before use ([ImportFileNames.sanitizeName]).
 *
 * The writer is only ever handed a tree URI the user picked through
 * `OpenDocumentTree`; it does not grant anything to a third party and requests
 * no global storage permission.
 */
class LibraryImportWriter(context: Context) : ImportFileWriter {

    private val appContext: Context = context.applicationContext ?: context
    private val resolver: ContentResolver = appContext.contentResolver

    /**
     * Real write check against the provider behind [treeUri]. Any failure
     * (missing/invalid tree, provider crash, revoked grant) reports `false`
     * instead of throwing, so callers can branch on it safely.
     */
    override fun canWrite(treeUri: Uri): Boolean = runCatching {
        DocumentFile.fromTreeUri(appContext, treeUri)?.canWrite() == true
    }.getOrDefault(false)

    /**
     * Copies [sourceUri] into [treeUri] under a free, sanitized name derived
     * from [baseName]. [mimeType] selects the new document's MIME type (falls
     * back to `audio/mpeg`). Returns a [WriteResult] describing the concrete
     * outcome; it never throws outward.
     */
    override fun write(
        treeUri: Uri,
        sourceUri: Uri,
        baseName: String,
        mimeType: String,
    ): WriteResult {
        if (!canWrite(treeUri)) {
            return WriteResult.Failure(WriteResult.Reason.NO_WRITE_PERMISSION)
        }

        val parent = runCatching { treeDocumentUri(treeUri) }.getOrNull()
            ?: return WriteResult.Failure(WriteResult.Reason.PROVIDER_ERROR)

        val mime = mimeType.takeIf { it.isNotBlank() } ?: ImportFileNames.DEFAULT_MIME_TYPE

        // Determining the free name only queries the tree; it must happen before
        // anything is created so a name clash never truncates an existing file.
        val displayName = runCatching { freeDisplayName(treeUri, baseName) }.getOrNull()
            ?: return WriteResult.Failure(WriteResult.Reason.NAME_UNAVAILABLE)

        val source = runCatching { resolver.openInputStream(sourceUri) }.getOrNull()
            ?: return WriteResult.Failure(WriteResult.Reason.SOURCE_UNREADABLE)

        val target = runCatching {
            DocumentsContract.createDocument(resolver, parent, mime, displayName)
        }.getOrNull() ?: run {
            runCatching { source.close() }
            return WriteResult.Failure(WriteResult.Reason.PROVIDER_ERROR)
        }

        var copied = 0L
        try {
            val output = resolver.openOutputStream(target, "w")
                ?: return discardAndFail(target, WriteResult.Reason.PROVIDER_ERROR)
            output.use { sink -> copied = copyCounting(source, sink) }
            if (Thread.currentThread().isInterrupted) {
                return discardAndFail(target, WriteResult.Reason.UNKNOWN_ERROR)
            }
        } catch (e: StreamCopyException) {
            return discardAndFail(target, e.reason)
        } catch (e: SecurityException) {
            return discardAndFail(target, WriteResult.Reason.NO_WRITE_PERMISSION)
        } catch (e: Exception) {
            return discardAndFail(target, WriteResult.Reason.UNKNOWN_ERROR)
        } finally {
            runCatching { source.close() }
        }

        if (copied <= 0L) {
            return discardAndFail(target, WriteResult.Reason.EMPTY_CONTENT)
        }
        return WriteResult.Success(target, displayName)
    }

    /**
     * Copies until EOF, counting bytes and keeping the failure origin apart:
     * a read error means the source is unusable, a write error means the target
     * provider failed. Both are surfaced as [StreamCopyException].
     */
    private fun copyCounting(source: InputStream, output: OutputStream): Long {
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        var total = 0L
        while (true) {
            if (Thread.currentThread().isInterrupted) {
                throw StreamCopyException(WriteResult.Reason.UNKNOWN_ERROR)
            }
            val read = try {
                source.read(buffer)
            } catch (e: IOException) {
                throw StreamCopyException(WriteResult.Reason.SOURCE_UNREADABLE)
            }
            if (read < 0) break
            try {
                output.write(buffer, 0, read)
            } catch (e: IOException) {
                throw StreamCopyException(WriteResult.Reason.PROVIDER_ERROR)
            }
            total += read
        }
        return total
    }

    /** Removes the half-written document, then reports the failure. */
    private fun discardAndFail(target: Uri, reason: WriteResult.Reason): WriteResult {
        runCatching { DocumentsContract.deleteDocument(resolver, target) }
        return WriteResult.Failure(reason)
    }

    /**
     * First free `stem (n) + extension`, starting at the untouched stem. Returns
     * null when the tree cannot be read or all [MAX_NAME_ATTEMPTS] are taken.
     */
    private fun freeDisplayName(treeUri: Uri, baseName: String): String? {
        val sanitized = ImportFileNames.sanitizeName(baseName)
        val (stem, extension) = ImportFileNames.splitExtension(sanitized)
        val root = DocumentFile.fromTreeUri(appContext, treeUri) ?: return null
        for (index in 1..MAX_NAME_ATTEMPTS) {
            val candidate = ImportFileNames.candidateName(stem, index) + extension
            if (root.findFile(candidate) == null) return candidate
        }
        return null
    }

    private fun treeDocumentUri(treeUri: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(
        treeUri,
        DocumentsContract.getTreeDocumentId(treeUri),
    )

    private class StreamCopyException(val reason: WriteResult.Reason) :
        IOException(reason.name)

    private companion object {
        const val MAX_NAME_ATTEMPTS = 1_000
        const val COPY_BUFFER_SIZE = 8 * 1024
    }
}

/**
 * Narrow seam over the import write path. [LibraryImportWriter] is the only
 * production implementation; the state layer depends on this interface so its
 * orchestration can be exercised with a fake writer and no SAF provider.
 *
 * Layer: **Data**. Consumers must not know about [DocumentFile]/[DocumentsContract].
 */
interface ImportFileWriter {

    /** True when [treeUri] currently permits writing a new document. */
    fun canWrite(treeUri: Uri): Boolean

    /** See [LibraryImportWriter.write]: never throws, reports a [WriteResult]. */
    fun write(treeUri: Uri, sourceUri: Uri, baseName: String, mimeType: String): WriteResult
}

/** Outcome of a single [LibraryImportWriter.write] attempt. */
sealed interface WriteResult {

    /** The file was fully written; [uri] is the new document, [displayName] its name. */
    data class Success(val uri: Uri, val displayName: String) : WriteResult

    /** Nothing usable was produced; [reason] distinguishes the failure. */
    data class Failure(val reason: Reason) : WriteResult

    /** Distinguishable failure causes for the caller/UI to react to. */
    enum class Reason {
        /** The tree grant does not allow writing. */
        NO_WRITE_PERMISSION,

        /** The document provider failed (create/output/call error). */
        PROVIDER_ERROR,

        /** The delivered source URI could not be opened or read. */
        SOURCE_UNREADABLE,

        /** The source was readable but empty; no empty file is left behind. */
        EMPTY_CONTENT,

        /** No free target name could be determined. */
        NAME_UNAVAILABLE,

        /** Any other unexpected failure. */
        UNKNOWN_ERROR,
    }
}

/**
 * Pure, framework-free file-name rules for imports, so the trust boundary
 * (untrusted plugin-provided names) is unit-testable on the JVM without the
 * Android runtime.
 *
 * Rules (DECISIONS.md §9): drop path separators, invalid characters and control
 * characters; strip leading/trailing dots and spaces; keep Unicode; cap the
 * length while preserving a trailing extension; fall back to a fixed technical
 * placeholder for an empty result. A path separator must never survive, and a
 * name is never interpreted as a path.
 */
object ImportFileNames {

    /** Fixed technical placeholder used when the cleaned name would be empty. */
    const val FALLBACK_BASE_NAME = "musicmonster_import"

    /** MIME type used when the caller passes none. */
    const val DEFAULT_MIME_TYPE = "audio/mpeg"

    /** Upper bound for a produced name, extension included. */
    const val MAX_NAME_LENGTH = 120

    // Path separators, the characters document providers reject, and every
    // control character (including NUL) are removed, never turned into a path.
    private val ILLEGAL_CHARS = Regex("""[\\/:*?"<>|\p{Cntrl}]""")

    // A trailing ".<1..5 alnum>" is treated as the file extension; it stays at
    // the very end so a collision suffix lands before it ("Title (2).mp3").
    private val EXTENSION = Regex("""\.[A-Za-z0-9]{1,5}$""")

    /**
     * The `index`-th collision-free candidate for [base]: the base itself for
     * `index <= 1`, otherwise `"$base (index)"` (e.g. `Song (2)`).
     */
    fun candidateName(base: String, index: Int): String =
        if (index <= 1) base else "$base ($index)"

    /**
     * Splits [name] into its stem and its trailing extension (with the dot).
     * A leading dot (hidden file) is not an extension, so `".mp3"` yields no
     * extension.
     */
    fun splitExtension(name: String): Pair<String, String> {
        val match = EXTENSION.find(name) ?: return name to ""
        if (match.range.first <= 0) return name to ""
        return name.substring(0, match.range.first) to match.value
    }

    /**
     * Cleans an untrusted [raw] name for storage. Never returns a blank string,
     * never contains a path separator, and keeps a trailing extension when
     * truncating.
     */
    fun sanitizeName(raw: String): String {
        val cleaned = ILLEGAL_CHARS.replace(raw, "").trim().trim('.', ' ')
        if (cleaned.isEmpty()) return FALLBACK_BASE_NAME
        val (stem, extension) = splitExtension(cleaned)
        val budget = (MAX_NAME_LENGTH - extension.length).coerceAtLeast(1)
        val shortened = stem.take(budget).trimEnd('.', ' ')
        return shortened.ifEmpty { FALLBACK_BASE_NAME } + extension
    }
}
