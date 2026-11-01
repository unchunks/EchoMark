package com.unchunks.echomark.data.snapshot

import com.unchunks.components.core.DispatcherProvider
import com.unchunks.echomark.data.attachment.AttachmentStore
import com.unchunks.echomark.data.local.dao.BookmarkDao
import com.unchunks.echomark.domain.snapshot.PageSnapshotRepository
import com.unchunks.echomark.domain.snapshot.StoredSnapshot
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * ページのオフラインコピーを、添付ファイルと同じ保存先(`filesDir/attachments/`)に置く。
 * そのため「すべてのデータを削除」・使われていないファイルの掃除([com.unchunks.echomark.data.attachment.UnusedAttachmentCleaner])の対象になる。
 * バックアップには含めない(大きく、リンク先から取り直せる)。
 */
class PageSnapshotRepositoryImpl @Inject constructor(
    private val attachmentStore: AttachmentStore,
    private val bookmarkDao: BookmarkDao,
    private val dispatcherProvider: DispatcherProvider
) : PageSnapshotRepository {

    override suspend fun save(bookmarkId: Long, mhtml: File, pdf: File?): StoredSnapshot {
        val previous = withContext(dispatcherProvider.io) { bookmarkDao.getById(bookmarkId) }
            ?: throw IllegalStateException("bookmark not found: $bookmarkId")
        val mhtmlPath = attachmentStore.adopt(mhtml, MHTML_EXTENSION)
        val pdfPath = try {
            pdf?.let { attachmentStore.adopt(it, PDF_EXTENSION) }
        } catch (e: Exception) {
            // PDF だけ移せなかった。MHTML は残す(PDF の無いコピーとして保存する)
            null
        }
        val snapshot = StoredSnapshot(mhtmlPath, pdfPath, savedAt = System.currentTimeMillis())
        withContext(dispatcherProvider.io) { bookmarkDao.updateSnapshot(bookmarkId, snapshot.mhtmlPath, snapshot.pdfPath, snapshot.savedAt) }
        previous.snapshotPath?.let { attachmentStore.delete(it) }
        previous.snapshotPdfPath?.let { attachmentStore.delete(it) }
        return snapshot
    }

    override suspend fun remove(bookmarkId: Long) {
        val previous = withContext(dispatcherProvider.io) { bookmarkDao.getById(bookmarkId) } ?: return
        withContext(dispatcherProvider.io) { bookmarkDao.updateSnapshot(bookmarkId, null, null, null) }
        previous.snapshotPath?.let { attachmentStore.delete(it) }
        previous.snapshotPdfPath?.let { attachmentStore.delete(it) }
    }

    override fun fileOf(path: String?): File? = attachmentStore.existingFile(path)

    private companion object {
        const val MHTML_EXTENSION = "mhtml"
        const val PDF_EXTENSION = "pdf"
    }
}
