package com.unchunks.echomark.data.video

import com.unchunks.components.core.DispatcherProvider
import com.unchunks.echomark.data.attachment.AttachmentStore
import com.unchunks.echomark.data.local.dao.OfflineVideoDao
import com.unchunks.echomark.data.local.entity.OfflineVideoEntity
import com.unchunks.echomark.domain.video.OfflineVideo
import com.unchunks.echomark.domain.video.OfflineVideoRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * 動画のオフライン保存の記録(offline_videos)と、そのサムネイル。
 * サムネイルは添付ファイルと同じ置き場所(`filesDir/attachments/`)に置くため、「すべてのデータを削除」と
 * 使われていないファイルの掃除([com.unchunks.echomark.data.attachment.UnusedAttachmentCleaner])の対象になる。
 * バックアップには入れない(zip にも入れない。BackupCoverage の理由を参照)。
 */
class OfflineVideoRepositoryImpl @Inject constructor(
    private val dao: OfflineVideoDao,
    private val attachmentStore: AttachmentStore,
    private val dispatcherProvider: DispatcherProvider
) : OfflineVideoRepository {

    override fun observe(bookmarkId: Long): Flow<OfflineVideo?> =
        dao.observe(bookmarkId).map { it?.toDomain() }.distinctUntilChanged()

    override suspend fun get(bookmarkId: Long): OfflineVideo? = withContext(dispatcherProvider.io) { dao.get(bookmarkId)?.toDomain() }

    override suspend fun markSaved(bookmarkId: Long, thumbnailPath: String?, savedAt: Long): Boolean {
        val previous = withContext(dispatcherProvider.io) { dao.get(bookmarkId) }
        val path = thumbnailPath ?: previous?.thumbnailPath
        val saved = withContext(dispatcherProvider.io) { dao.saveThumbnail(bookmarkId, path, savedAt) }
        if (!saved) {
            // ブックマークが消えていた。保存したばかりのサムネイルは、どこからも参照されないので消す
            thumbnailPath?.let { attachmentStore.delete(it) }
            return false
        }
        previous?.thumbnailPath?.takeIf { it != path }?.let { attachmentStore.delete(it) }
        return true
    }

    override suspend fun remove(bookmarkId: Long) {
        val previous = withContext(dispatcherProvider.io) { dao.get(bookmarkId) } ?: return
        withContext(dispatcherProvider.io) { dao.delete(bookmarkId) }
        previous.thumbnailPath?.let { attachmentStore.delete(it) }
    }

    override fun fileOf(path: String?): File? = attachmentStore.existingFile(path)
}

internal fun OfflineVideoEntity.toDomain(): OfflineVideo =
    OfflineVideo(bookmarkId = bookmarkId, thumbnailPath = thumbnailPath, savedAt = savedAt)
