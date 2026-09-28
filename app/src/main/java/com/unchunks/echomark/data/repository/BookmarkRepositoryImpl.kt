package com.unchunks.echomark.data.repository

import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.unchunks.echomark.data.local.dao.BookmarkDao
import com.unchunks.echomark.data.local.dao.TagDao
import com.unchunks.echomark.data.local.entity.BookmarkTagCrossRef
import com.unchunks.echomark.data.local.entity.TagEntity
import com.unchunks.echomark.data.local.objectbox.VectorSearchDataSource
import com.unchunks.echomark.data.mapper.toDomain
import com.unchunks.echomark.data.mapper.toEntity
import com.unchunks.echomark.di.DispatcherProvider
import com.unchunks.echomark.domain.repository.BookmarkRepository
import com.unchunks.echomark.domain.repository.SaveResult
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.model.Tag
import com.unchunks.echomark.worker.BookmarkAiProcessingWorker
import com.unchunks.echomark.worker.UrlFetchWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.collections.map

class BookmarkRepositoryImpl @Inject constructor(
    private val bookmarkDao: BookmarkDao,
    private val tagDao: TagDao,
    private val vectorSearch: VectorSearchDataSource,
    private val dispatcherProvider: DispatcherProvider,
    private val workManager: WorkManager
) : BookmarkRepository {

    override suspend fun saveBookmark(bookmark: Bookmark): Long =
        saveBookmarkWithResult(bookmark).id

    override suspend fun saveBookmarkWithResult(bookmark: Bookmark): SaveResult =
        withContext(dispatcherProvider.io) {
            val contentUri = bookmark.contentUri
            if (contentUri == null) {
                val id = bookmarkDao.insert(bookmark.toEntity())
                enqueueProcessing(id, bookmark.type)
                return@withContext SaveResult(id, isDuplicate = false)
            }

            // contentUri を持つもの(URLなど)は重複チェック。REPLACEするとタグ参照が消えるためIGNOREで挿入する
            val insertedId = bookmarkDao.insertIgnore(bookmark.toEntity())
            if (insertedId != -1L) {
                enqueueProcessing(insertedId, bookmark.type)
                SaveResult(insertedId, isDuplicate = false)
            } else {
                val existing = bookmarkDao.getByContentUri(contentUri)
                    ?: return@withContext SaveResult(-1L, isDuplicate = false)
                bookmarkDao.updateLastAccessedAt(existing.id, System.currentTimeMillis())
                SaveResult(existing.id, isDuplicate = true)
            }
        }

    override suspend fun saveUrlBookmark(url: String, title: String?, memo: String?): SaveResult {
        val trimmedUrl = url.trim()
        val now = System.currentTimeMillis()
        return saveBookmarkWithResult(
            Bookmark(
                type = BookmarkType.URL,
                content = memo?.takeIf { it.isNotBlank() }?.trim(),
                contentUri = trimmedUrl,
                title = title?.takeIf { it.isNotBlank() }?.trim() ?: trimmedUrl,
                createdAt = now,
                lastAccessedAt = now
            )
        )
    }

    /** URLは「本文取得 → AI処理」のチェーン、それ以外はAI処理のみを実行する */
    private fun enqueueProcessing(bookmarkId: Long, type: BookmarkType) {
        val inputData = workDataOf(BookmarkAiProcessingWorker.KEY_BOOKMARK_ID to bookmarkId)
        val aiRequest = OneTimeWorkRequestBuilder<BookmarkAiProcessingWorker>()
            .setInputData(inputData)
            .build()

        if (type == BookmarkType.URL) {
            val fetchRequest = OneTimeWorkRequestBuilder<UrlFetchWorker>()
                .setInputData(inputData)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            workManager
                .beginUniqueWork("process_bookmark_$bookmarkId", ExistingWorkPolicy.REPLACE, fetchRequest)
                .then(aiRequest)
                .enqueue()
        } else {
            workManager.enqueue(aiRequest)
        }
    }

    override suspend fun saveTags(bookmarkId: Long, tagNames: List<String>) =
        withContext(dispatcherProvider.io) {
            tagNames.forEach { name ->
                val tagId = getOrCreateTagId(name)
                tagDao.insertCrossRef(BookmarkTagCrossRef(bookmarkId, tagId))
            }
        }
    private suspend fun getOrCreateTagId(name: String): Long {
        val insertedId = tagDao.insertTag(TagEntity(name = name))
        return if (insertedId != -1L) insertedId else tagDao.getTagByName(name)?.id ?: -1L
    }

    override suspend fun saveEmbedding(bookmarkId: Long, vector: FloatArray, modelVersion: String): Unit =
        withContext(dispatcherProvider.io) {
            vectorSearch.upsert(bookmarkId, vector, modelVersion)
        }

    override suspend fun updateSummary(id: Long, summary: String) =
        withContext(dispatcherProvider.io) {
            bookmarkDao.updateSummary(id, summary)
        }

    override suspend fun updateCategory(id: Long, category: String) =
        withContext(dispatcherProvider.io) {
            bookmarkDao.updateCategory(id, category)
        }

    override suspend fun updateTitleAndContent(id: Long, title: String, content: String?) =
        withContext(dispatcherProvider.io) {
            bookmarkDao.updateTitleAndContent(id, title, content)
        }



    override suspend fun deleteBookmark(bookmark: Bookmark) =
        withContext(dispatcherProvider.io) {
            bookmarkDao.delete(bookmark.toEntity())
            // ObjectBox側の埋め込みも削除する(孤児ベクトルを残さない)
            vectorSearch.deleteByBookmarkId(bookmark.id)
        }



    override suspend fun getBookmarkById(id: Long): Bookmark? =
        withContext(dispatcherProvider.io) {
            bookmarkDao.getById(id)?.toDomain()
        }

    override suspend fun getBookmarksByIds(ids: List<Long>): List<Bookmark> =
        withContext(dispatcherProvider.io) {
            bookmarkDao.getByIds(ids).map { it.toDomain() }
        }


    override suspend fun getAllBookmarkIds(): List<Long> =
        withContext(dispatcherProvider.io) {
            bookmarkDao.getAllIds()
        }

    override suspend fun getRelatedBookmarks(bookmarkId: Long, limit: Int): List<Bookmark> =
        withContext(dispatcherProvider.io) {
            // 1. 自分自身のembeddingベクトルを取得
            val myVector = vectorSearch.getVector(bookmarkId) ?: return@withContext emptyList()

            // 2. そのベクトルで類似検索(自分自身も結果に含まれるため+1件多めに取る)
            val relatedIds = vectorSearch.nearestNeighbors(myVector, limit + 1)
                .map { it.bookmarkId }
                .filter { it != bookmarkId } // 自分自身を除外
                .take(limit)

            bookmarkDao.getByIds(relatedIds).map { it.toDomain() }
        }

    override suspend fun getEmbeddingModelVersion(bookmarkId: Long): String? =
        withContext(dispatcherProvider.io) {
            vectorSearch.getModelVersion(bookmarkId)
        }



    override fun observeBookmarks(): Flow<List<Bookmark>> =
        bookmarkDao.getAllWithTags()
            .map { list -> list.map { it.toDomain() } }
            .flowOn(dispatcherProvider.io)

    override fun observeBookmarksByTag(tagId: Long): Flow<List<Bookmark>> =
        bookmarkDao.getByTag(tagId)
            .map { list -> list.map { it.toDomain() } }
            .flowOn(dispatcherProvider.io)

    override fun observeAllTags(): Flow<List<Tag>> =
        tagDao.getAllTags().map { list -> list.map { Tag(it.id, it.name) } }
            .flowOn(dispatcherProvider.io)

    override fun searchBookmarks(query: String): Flow<List<Bookmark>> =
        bookmarkDao.searchWithTags(query)
            .map { list -> list.map { it.toDomain() }}
            .flowOn(dispatcherProvider.io)
}
