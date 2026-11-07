package com.unchunks.echomark.data.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject

/** 自動バックアップの保存先のフォルダの中のファイル */
data class BackupFolderEntry(val name: String, val uri: String)

/** 利用者が選んだフォルダ(SAF のツリーの URI)への読み書き。テストでは Fake に差し替える */
interface BackupFolder {
    /** [treeUri] を読み書きする許可を持ち続けているか(フォルダを消された・許可を取り消されたら false) */
    fun canWrite(treeUri: String): Boolean

    /** [treeUri] の中にファイルを作り、その URI を返す。作れなければ IOException(許可が無ければ SecurityException) */
    fun create(treeUri: String, displayName: String, mimeType: String): String

    /** [treeUri] の中のファイル */
    fun list(treeUri: String): List<BackupFolderEntry>

    /** ファイルを消す。消せたら true */
    fun delete(documentUri: String): Boolean
}

/** SAF(DocumentsContract)で読み書きする。ファイルの置き場所は利用者が選び、アプリは許可を持ち続ける(takePersistableUriPermission) */
class SafBackupFolder @Inject constructor(
    @ApplicationContext private val context: Context
) : BackupFolder {

    override fun canWrite(treeUri: String): Boolean =
        context.contentResolver.persistedUriPermissions.any {
            it.uri.toString() == treeUri && it.isReadPermission && it.isWritePermission
        }

    override fun create(treeUri: String, displayName: String, mimeType: String): String {
        val created = DocumentsContract.createDocument(context.contentResolver, folderDocument(treeUri), mimeType, displayName)
            ?: throw IOException("createDocument returned null")
        return created.toString()
    }

    override fun list(treeUri: String): List<BackupFolderEntry> {
        val tree = Uri.parse(treeUri)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        return context.contentResolver.query(children, columns, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    add(BackupFolderEntry(name, DocumentsContract.buildDocumentUriUsingTree(tree, id).toString()))
                }
            }
        }.orEmpty()
    }

    override fun delete(documentUri: String): Boolean =
        DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(documentUri))

    private fun folderDocument(treeUri: String): Uri {
        val tree = Uri.parse(treeUri)
        return DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    }
}
