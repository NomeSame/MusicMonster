package com.nomesame.musicmonster;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Real, writable SAF-style document provider for the import end-to-end test.
 *
 * <p><b>Not a shadow, not a fake:</b> it is a genuine {@link ContentProvider} running in the
 * instrumented-test APK's own process, reached by the host app over the binder through
 * {@link android.content.ContentResolver} — exactly the cross-process seam a user-picked
 * {@code OpenDocumentTree} folder uses. The host has no in-process handle to it.
 *
 * <p>It is written in plain Java on purpose: the test APK's own process loads only the test
 * APK dex (the app APK's libraries, including the Kotlin runtime, are only on the classpath of
 * the host process the tests run in). A Kotlin provider would therefore crash at process start
 * with {@code NoClassDefFoundError: kotlin/jvm/internal/Intrinsics}. Java keeps this provider
 * self-contained and dependency-free.
 *
 * <p>It deliberately does <b>not</b> extend {@link android.provider.DocumentsProvider}: that
 * base class refuses to attach without {@code android.permission.MANAGE_DOCUMENTS}
 * (system-only) and would force the host to go through the system picker. A documents provider
 * is only reachable directly once a per-URI grant was issued, which is what the test
 * reproduces: a plain provider speaking the real {@code DocumentsContract} wire protocol,
 * exported with {@code grantUriPermissions="true"}, to which the test APK grants the host a
 * read+write permission on the tree.
 *
 * <p>Documents live in memory (id → name/MIME/flags); their bytes live in the test APK's
 * {@code cacheDir}, one real file per document. It refuses to create a document whose name
 * already exists, so a host-side overwrite would surface loudly instead of silently truncating
 * an existing file.
 */
public class E2eTreeDocumentsProvider extends ContentProvider {

    /** Authority declared in app/src/androidTest/AndroidManifest.xml. */
    public static final String AUTHORITY = "com.nomesame.musicmonster.test.e2etree";

    /** Document id of the single writable root. */
    public static final String ROOT_ID = "e2e-root";

    /**
     * Read+write flags the test APK grants the host for the tree.
     *
     * <p>{@link Intent#FLAG_GRANT_PREFIX_URI_PERMISSION} is essential and is exactly what the
     * system SAF picker adds for a picked tree: without it the grant is a "basic" grant, and
     * because this provider is exported without a platform permission the system considers the
     * target to already hold access and records <b>no</b> {@code UriPermission} at all
     * (confirmed in AOSP {@code UriGrantsManagerService.checkGrantUriPermissionUnlocked}, which
     * returns early for {@code basicGrant && targetHoldsPermission}). The prefix flag forces a
     * real, recorded grant that also covers every descendant document URI — which is what
     * {@code DocumentFile.canWrite()} checks via {@code checkCallingOrSelfUriPermission}.
     */
    public static final int TREE_GRANT_FLAGS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;

    private static final String DIR_NAME = "e2e-import-tree";
    private static final String PATH_ROOT = "root";
    private static final String PATH_CHILDREN = "children";

    // @hide SAF protocol literals (not in the public SDK stubs). Values verified against AOSP
    // android-14.0.0_r1 DocumentsContract.java: create/delete route through these exact method
    // names and Bundle keys.
    private static final String METHOD_CREATE_DOCUMENT = "android:createDocument";
    private static final String METHOD_DELETE_DOCUMENT = "android:deleteDocument";
    private static final String EXTRA_URI = "uri";
    private static final String EXTRA_RESULT = "result";

    private static final String[] DEFAULT_DOCUMENT_COLUMNS = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    };

    private static final class Doc {
        final String id;
        final String name;
        final String mime;
        final boolean isDir;
        final File file;

        Doc(String id, String name, String mime, boolean isDir, File file) {
            this.id = id;
            this.name = name;
            this.mime = mime;
            this.isDir = isDir;
            this.file = file;
        }
    }

    private final Map<String, Doc> docs = new LinkedHashMap<>();
    private final Map<String, List<String>> children = new LinkedHashMap<>();
    private int seq;

    @Override
    public boolean onCreate() {
        reset();
        return true;
    }

    /**
     * Drops every document and its backing file and re-creates the single root. Runs on
     * provider creation and can be triggered from the host side (via deleting every child) so
     * the tree is empty and a run is repeatable.
     */
    private void reset() {
        docs.clear();
        children.clear();
        seq = 0;
        File dir = backingDir();
        deleteRecursively(dir);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        docs.put(ROOT_ID, new Doc(ROOT_ID, "E2E import tree",
                DocumentsContract.Document.MIME_TYPE_DIR, true, dir));
        children.put(ROOT_ID, new ArrayList<String>());
    }

    /** The tree URI the host treats as its library folder. */
    public static Uri treeUri() {
        return DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT_ID);
    }

    private File backingDir() {
        return new File(getContext().getCacheDir(), DIR_NAME);
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] kids = file.listFiles();
        if (kids != null) for (File kid : kids) deleteRecursively(kid);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        List<String> requested = new ArrayList<>();
        if (projection != null) {
            for (String column : projection) requested.add(column);
        } else {
            for (String column : DEFAULT_DOCUMENT_COLUMNS) requested.add(column);
        }

        List<String> segments = uri.getPathSegments();
        if (segments.size() == 1 && PATH_ROOT.equals(segments.get(0))) {
            return rootsCursor(requested);
        }

        String parentOrDocumentId = DocumentsContract.getDocumentId(uri);
        List<String> ids = new ArrayList<>();
        if (segments.contains(PATH_CHILDREN)) {
            List<String> kids = children.get(parentOrDocumentId);
            if (kids != null) ids.addAll(kids);
        } else if (docs.containsKey(parentOrDocumentId)) {
            ids.add(parentOrDocumentId);
        }

        MatrixCursor cursor = new MatrixCursor(requested.toArray(new String[0]));
        for (String id : ids) {
            Doc doc = docs.get(id);
            Object[] row = new Object[requested.size()];
            for (int i = 0; i < requested.size(); i++) row[i] = columnValue(requested.get(i), doc);
            cursor.addRow(row);
        }
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        List<String> segments = uri.getPathSegments();
        if (segments.size() == 1 && PATH_ROOT.equals(segments.get(0))) {
            return DocumentsContract.Root.MIME_TYPE_ITEM;
        }
        Doc doc = docs.get(DocumentsContract.getDocumentId(uri));
        return doc == null ? null : doc.mime;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (getContext().getPackageManager().checkSignatures(android.os.Binder.getCallingUid(),
                android.os.Process.myUid()) != android.content.pm.PackageManager.SIGNATURE_MATCH) {
            throw new SecurityException("Fixture only accepts the same-signed host");
        }
        if ("grant-fixture-tree".equals(method)) {
            getContext().grantUriPermission("com.nomesame.musicmonster", treeUri(), TREE_GRANT_FLAGS);
            return new Bundle();
        }
        if ("revoke-fixture-tree".equals(method)) {
            getContext().revokeUriPermission(treeUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            return new Bundle();
        }
        if (METHOD_CREATE_DOCUMENT.equals(method)) return createDocument(extras);
        if (METHOD_DELETE_DOCUMENT.equals(method)) return deleteDocument(extras);
        return super.call(method, arg, extras);
    }

    /** Real descendant check (SAF {@code isChildDocument}); used by the roots flag. */
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        if (parentDocumentId.equals(documentId)) return true;
        List<String> queue = new ArrayList<>();
        queue.add(parentDocumentId);
        for (int i = 0; i < queue.size(); i++) {
            List<String> kids = children.get(queue.get(i));
            if (kids == null) continue;
            if (kids.contains(documentId)) return true;
            queue.addAll(kids);
        }
        return false;
    }

    // ---- create/delete call protocol ----------------------------------------------------

    @SuppressWarnings("deprecation")
    private Bundle createDocument(Bundle extras) {
        String name = extras.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
        String mime = extras.getString(DocumentsContract.Document.COLUMN_MIME_TYPE);
        if (mime == null) mime = DocumentsContract.Document.MIME_TYPE_DIR;
        Uri parentUri = extras.getParcelable(EXTRA_URI);
        String parentId = parentUri == null ? ROOT_ID : DocumentsContract.getDocumentId(parentUri);

        // An overwrite is a bug in the writer, never something to paper over.
        List<String> existing = children.get(parentId);
        if (existing != null) {
            for (String childId : existing) {
                Doc doc = docs.get(childId);
                if (doc != null && doc.name.equals(name)) {
                    throw new IllegalStateException(
                            "refusing to overwrite existing document '" + name + "'");
                }
            }
        }

        String id = "doc" + (++seq);
        File file = new File(backingDir(), id);
        Doc doc;
        if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
            //noinspection ResultOfMethodCallIgnored
            file.mkdirs();
            doc = new Doc(id, name, mime, true, file);
        } else {
            doc = new Doc(id, name, mime, false, file);
        }
        docs.put(id, doc);
        if (existing == null) {
            existing = new ArrayList<>();
            children.put(parentId, existing);
        }
        existing.add(id);

        Bundle out = new Bundle();
        out.putParcelable(EXTRA_URI, DocumentsContract.buildDocumentUriUsingTree(treeUri(), id));
        return out;
    }

    @SuppressWarnings("deprecation")
    private Bundle deleteDocument(Bundle extras) {
        Uri uri = extras.getParcelable(EXTRA_URI);
        if (uri != null) {
            String id = DocumentsContract.getDocumentId(uri);
            Doc doc = docs.remove(id);
            if (doc != null && doc.file != null) deleteRecursively(doc.file);
            children.remove(id);
            for (List<String> kids : children.values()) kids.remove(id);
        }
        Bundle out = new Bundle();
        out.putBoolean(EXTRA_RESULT, true);
        return out;
    }

    // ---- streams ------------------------------------------------------------------------

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String id = DocumentsContract.getDocumentId(uri);
        Doc doc = docs.get(id);
        if (doc == null) throw new FileNotFoundException("no document '" + id + "'");
        File file = doc.file;
        if (file == null) throw new FileNotFoundException("document '" + id + "' has no stream");

        boolean write = mode.contains("w");
        boolean read = mode.contains("r");
        if (write) {
            File parent = file.getParentFile();
            if (parent != null) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
        } else if (!file.exists()) {
            throw new FileNotFoundException("file for '" + id + "' does not exist");
        }

        int flags;
        if (write && read) {
            flags = ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_READ_WRITE
                    | ParcelFileDescriptor.MODE_TRUNCATE;
        } else if (write) {
            flags = ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_WRITE_ONLY
                    | ParcelFileDescriptor.MODE_TRUNCATE;
        } else {
            flags = ParcelFileDescriptor.MODE_READ_ONLY;
        }
        return ParcelFileDescriptor.open(file, flags);
    }

    // ---- cursor helpers -----------------------------------------------------------------

    private Cursor rootsCursor(List<String> requested) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID);
        values.put(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_ID);
        values.put(DocumentsContract.Root.COLUMN_TITLE, "E2E import tree");
        values.put(DocumentsContract.Root.COLUMN_SUMMARY, "Instrumented-test SAF tree");
        values.put(DocumentsContract.Root.COLUMN_FLAGS,
                DocumentsContract.Root.FLAG_SUPPORTS_CREATE
                        | DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD);
        values.put(DocumentsContract.Root.COLUMN_ICON, 0);
        values.put(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES, Long.MAX_VALUE);
        values.put(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*");

        MatrixCursor cursor = new MatrixCursor(requested.toArray(new String[0]));
        Object[] row = new Object[requested.size()];
        for (int i = 0; i < requested.size(); i++) row[i] = values.get(requested.get(i));
        cursor.addRow(row);
        return cursor;
    }

    private Object columnValue(String column, Doc doc) {
        switch (column) {
            case DocumentsContract.Document.COLUMN_DOCUMENT_ID:
                return doc.id;
            case DocumentsContract.Document.COLUMN_DISPLAY_NAME:
                return doc.name;
            case DocumentsContract.Document.COLUMN_MIME_TYPE:
                return doc.mime;
            case DocumentsContract.Document.COLUMN_FLAGS:
                return flagsFor(doc);
            case DocumentsContract.Document.COLUMN_SIZE:
                return doc.file == null ? 0L : doc.file.length();
            case DocumentsContract.Document.COLUMN_LAST_MODIFIED:
                return doc.file == null ? 0L : doc.file.lastModified();
            default:
                return null;
        }
    }

    private int flagsFor(Doc doc) {
        if (doc.isDir) {
            return DocumentsContract.Document.FLAG_SUPPORTS_WRITE
                    | DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE;
        }
        return DocumentsContract.Document.FLAG_SUPPORTS_WRITE
                | DocumentsContract.Document.FLAG_SUPPORTS_DELETE;
    }

    // Unused ContentProvider write paths: the SAF call protocol is handled in call().
    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
