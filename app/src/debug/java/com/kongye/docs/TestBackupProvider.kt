package com.kongye.docs

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File

/** Debug-only SAF fixture. Never exposes any real document or user's backup directory. */
class TestBackupProvider : DocumentsProvider() {
    companion object { @Volatile var rejectDelete = false }
    private val root get() = File(requireNotNull(context).filesDir,"v27-saf-fixture").apply { mkdirs() }.canonicalFile
    private fun file(id: String): File {
        val target = if(id=="root") root else File(root,id.removePrefix("root/")).canonicalFile
        require(target == root.canonicalFile || target.path.startsWith(root.canonicalPath+File.separator))
        return target
    }
    override fun onCreate() = true
    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: arrayOf(Root.COLUMN_ROOT_ID,Root.COLUMN_DOCUMENT_ID,Root.COLUMN_TITLE,Root.COLUMN_FLAGS)).apply {
        val row=newRow(); columnNames.forEach { row.add(it,when(it) { Root.COLUMN_ROOT_ID,Root.COLUMN_DOCUMENT_ID -> "root"; Root.COLUMN_TITLE -> "隔离备份测试"; Root.COLUMN_FLAGS -> Root.FLAG_SUPPORTS_CREATE; else -> null }) }
    }
    private fun add(cursor: MatrixCursor, file: File) {
        val id=if(file==root) "root" else "root/"+file.relativeTo(root).invariantSeparatorsPath
        val row=cursor.newRow()
        cursor.columnNames.forEach { row.add(it,when(it) {
            Document.COLUMN_DOCUMENT_ID -> id
            Document.COLUMN_DISPLAY_NAME -> file.name
            Document.COLUMN_MIME_TYPE -> if(file.isDirectory) Document.MIME_TYPE_DIR else "application/json"
            Document.COLUMN_SIZE -> file.length()
            Document.COLUMN_LAST_MODIFIED -> file.lastModified()
            Document.COLUMN_FLAGS -> if(file.isDirectory) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_WRITE
            else -> null
        }) }
    }
    private fun columns(p: Array<out String>?) = p ?: arrayOf(Document.COLUMN_DOCUMENT_ID,Document.COLUMN_DISPLAY_NAME,Document.COLUMN_MIME_TYPE,Document.COLUMN_SIZE,Document.COLUMN_LAST_MODIFIED,Document.COLUMN_FLAGS)
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = MatrixCursor(columns(projection)).also { add(it,file(documentId)) }
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor = MatrixCursor(columns(projection)).also { c -> file(parentDocumentId).listFiles().orEmpty().forEach { add(c,it) } }
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?) = ParcelFileDescriptor.open(file(documentId),ParcelFileDescriptor.parseMode(mode))
    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        require('/' !in displayName && '\\' !in displayName)
        val f=File(file(parentDocumentId),displayName); check(f.createNewFile()); return "root/"+f.relativeTo(root).invariantSeparatorsPath
    }
    override fun deleteDocument(documentId: String) { check(!rejectDelete) { "Fixture rejects deletion" }; check(file(documentId).isFile && file(documentId).delete()) }
    override fun isChildDocument(parentDocumentId: String, documentId: String) = file(documentId).path.startsWith(file(parentDocumentId).path+File.separator)
}
