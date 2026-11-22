package com.unchunks.echomark.data.video

import com.unchunks.components.core.DispatcherProvider
import com.unchunks.echomark.data.attachment.AttachmentStore
import com.unchunks.echomark.data.local.dao.OfflineVideoDao
import com.unchunks.echomark.data.local.entity.OfflineVideoEntity
import com.unchunks.echomark.domain.video.OfflineVideo
import com.unchunks.echomark.domain.video.OfflineVideoRepository
import com.unchunks.echomark.domain.video.SavedVideoFile
import com.unchunks.echomark.domain.video.SavedVideoListItem
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
 * 動画のファイルは別の置き場所([VideoFileStore]。`filesDir/offline_videos/`)に置く。
 * バックアップには入れない(zip にも、Android のバックアップにも入れない。BackupCoverage の理由を参照)。
 */
class OfflineVideoRepositoryImpl @Inject constructor(
    private val dao: OfflineVideoDao,
    private val attachmentStore: AttachmentStore,
    private val videoFileStore: VideoFileStore,
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
        previous.videoPath?.let { videoFileStore.delete(it) }
        videoFileStore.deletePart(bookmarkId)
    }

    override fun fileOf(path: String?): File? = attachmentStore.existingFile(path)

    override fun observeSavedVideos(): Flow<List<SavedVideoListItem>> =
        dao.observeSavedVideos().map { rows ->
            rows.map { SavedVideoListItem(it.bookmarkId, it.title, it.path, it.size ?: 0L, it.label, it.savedAt ?: 0L) }
        }.distinctUntilChanged()

    override fun observeVideoBytes(): Flow<Long> = dao.observeVideoBytes().distinctUntilChanged()

    override suspend fun totalVideoBytes(excludingBookmarkId: Long?): Long =
        withContext(dispatcherProvider.io) { dao.totalVideoBytes(excludingBookmarkId ?: NO_BOOKMARK) }

    override suspend fun saveVideo(bookmarkId: Long, video: SavedVideoFile): Boolean {
        val previous = withContext(dispatcherProvider.io) { dao.get(bookmarkId) }
        val saved = withContext(dispatcherProvider.io) {
            dao.saveVideo(bookmarkId, video.path, video.mimeType, video.size, video.sourceUrl, video.label, video.savedAt)
        }
        if (!saved) {
            // ブックマークが消えていた。落としたばかりのファイルは、どこからも参照されないので消す
            videoFileStore.delete(video.path)
            return false
        }
        previous?.videoPath?.takeIf { it != video.path }?.let { videoFileStore.delete(it) }
        return true
    }

    override suspend fun removeVideo(bookmarkId: Long) {
        val previous = withContext(dispatcherProvider.io) { dao.get(bookmarkId) }
        withContext(dispatcherProvider.io) { dao.clearVideo(bookmarkId) }
        previous?.videoPath?.let { videoFileStore.delete(it) }
        videoFileStore.deletePart(bookmarkId)
    }

    override suspend fun discardPartialVideo(bookmarkId: Long) = videoFileStore.deletePart(bookmarkId)

    override suspend fun removeAllVideos() {
        withContext(dispatcherProvider.io) { dao.clearAllVideos() }
        videoFileStore.deleteAll()
    }

    override fun videoFileOf(path: String?): File? = videoFileStore.existingFile(path)

    override suspend fun freeBytes(): Long = withContext(dispatcherProvider.io) { videoFileStore.freeBytes() }

    private companion object {
        /** どのブックマークも除かないときの ID(ID は 1 から振られる) */
        const val NO_BOOKMARK = 0L
    }
}

internal fun OfflineVideoEntity.toDomain(): OfflineVideo =
    OfflineVideo(
        bookmarkId = bookmarkId,
        thumbnailPath = thumbnailPath,
        savedAt = savedAt,
        video = videoPath?.let { path ->
            SavedVideoFile(
                path = path,
                mimeType = videoMimeType ?: "video/mp4",
                size = videoSize ?: 0L,
                sourceUrl = videoSourceUrl.orEmpty(),
                label = videoLabel,
                savedAt = videoSavedAt ?: savedAt
            )
        }
    )
