package com.nomesame.musicmonster.fixtures
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
/** Private debug-only provider: exercises real Binder/document rename contracts on disposable files. */
class RenameDocumentsProvider : DocumentsProvider() {
 private val folder get()=File(context!!.cacheDir,"rename-fixtures").apply { mkdirs() }
 private fun file(id:String):File {
  require(id.isNotEmpty() && id != "." && id != ".." && !id.contains('/') && !id.contains('\\'))
  return if(id=="root") folder else File(folder,id)
 }
 override fun isChildDocument(parentDocumentId:String,documentId:String)=parentDocumentId=="root" && documentId!="root" && file(documentId).exists()
 override fun onCreate()=true
 override fun queryRoots(projection:Array<out String>?):Cursor=MatrixCursor(projection ?: arrayOf(Root.COLUMN_ROOT_ID,Root.COLUMN_DOCUMENT_ID,Root.COLUMN_TITLE,Root.COLUMN_FLAGS)).apply {
  addRow(columnNames.map { when(it) { Root.COLUMN_ROOT_ID,Root.COLUMN_DOCUMENT_ID->"root";Root.COLUMN_TITLE->"Rename fixtures";else->0 } }.toTypedArray())
 }
 override fun queryDocument(documentId:String,projection:Array<out String>?):Cursor=rows(listOf(file(documentId)),projection)
 override fun queryChildDocuments(parentDocumentId:String,projection:Array<out String>?,sortOrder:String?):Cursor=rows(file(parentDocumentId).listFiles()?.toList().orEmpty(),projection)
 private fun rows(files:List<File>,projection:Array<out String>?):Cursor=MatrixCursor(projection ?: arrayOf(Document.COLUMN_DOCUMENT_ID,Document.COLUMN_DISPLAY_NAME,Document.COLUMN_MIME_TYPE,Document.COLUMN_FLAGS,Document.COLUMN_SIZE)).apply {
  files.filter { it.exists() }.forEach { f -> addRow(columnNames.map { when(it) {
   Document.COLUMN_DOCUMENT_ID->if(f==folder) "root" else f.name
   Document.COLUMN_DISPLAY_NAME->f.name
   Document.COLUMN_MIME_TYPE->if(f.isDirectory) Document.MIME_TYPE_DIR else if(f.extension == "partial") "application/octet-stream" else "audio/"+f.extension
   Document.COLUMN_FLAGS->if(f.name.startsWith("locked")) 0 else if(f.isDirectory) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_RENAME or Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE
   Document.COLUMN_SIZE->f.length()
   else->null
  } }.toTypedArray()) }
 }
 override fun createDocument(parentDocumentId:String,mimeType:String,displayName:String):String {
  check(parentDocumentId == "root"); val dest=file(displayName); check(dest.createNewFile()); return dest.name
 }
 override fun deleteDocument(documentId:String) { check(file(documentId).delete()) }
 override fun openDocument(documentId:String,mode:String,signal:CancellationSignal?):ParcelFileDescriptor {
  beforeOpen?.invoke(documentId)
  return ParcelFileDescriptor.open(file(documentId),ParcelFileDescriptor.parseMode(mode))
 }
 companion object { @Volatile var beforeOpen: ((String) -> Unit)? = null }
 override fun renameDocument(documentId:String,displayName:String):String {
  val source=file(documentId);val dest=file(displayName)
  if(source.name.startsWith("locked")) throw SecurityException("Read-only fixture")
  check(!dest.exists());check(source.renameTo(dest));return dest.name
 }
}
