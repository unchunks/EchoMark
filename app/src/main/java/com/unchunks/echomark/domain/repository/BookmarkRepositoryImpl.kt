package com.unchunks.echomark.domain.repository

import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.unchunks.echomark.data.local.dao.BookmarkDao
import com.unchunks.echomark.data.local.dao.TagDao
import com.unchunks.echomark.data.local.entity.BookmarkTagCrossRef
import com.unchunks.echomark.data.local.entity.TagEntity
import com.unchunks.echomark.data.local.objectbox.EmbeddingEntity
import com.unchunks.echomark.data.local.objectbox.EmbeddingEntity_
import com.unchunks.echomark.data.mapper.toDomain
import com.unchunks.echomark.data.mapper.toEntity
import com.unchunks.echomark.di.DispatcherProvider
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.model.Tag
import com.unchunks.echomark.worker.BookmarkAiProcessingWorker
import io.objectbox.Box
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.collections.map

class BookmarkRepositoryImpl @Inject constructor(
    private val bookmarkDao: BookmarkDao,
    private val tagDao: TagDao,
    private val embeddingBox: Box<EmbeddingEntity>,
    private val dispatcherProvider: DispatcherProvider,
    private val workManager: WorkManager
) : BookmarkRepository {

    override suspend fun saveBookmark(bookmark: Bookmark): Long =
        withContext(dispatcherProvider.io) {
            val id = bookmarkDao.insert(bookmark.toEntity())
            enqueueAiProcessing(id)
            id
        }
    private fun enqueueAiProcessing(bookmarkId: Long) {
        val request = OneTimeWorkRequestBuilder<BookmarkAiProcessingWorker>()
            .setInputData(workDataOf(BookmarkAiProcessingWorker.KEY_BOOKMARK_ID to bookmarkId))
            .build()
        workManager.enqueue(request)
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
            val existingId = findEmbeddingObjectBoxId(bookmarkId)
            embeddingBox.put(
                EmbeddingEntity(
                    id = existingId ?: 0,
                    bookmarkId = bookmarkId,
                    vector = vector,
                    modelVersion = modelVersion
                )
            )
        }
    private fun findEmbeddingObjectBoxId(bookmarkId: Long): Long? {
        val query = embeddingBox.query(EmbeddingEntity_.bookmarkId.equal(bookmarkId)).build()
        val existing = query.findFirst()
        query.close()
        return existing?.id
    }



    override suspend fun updateSummary(id: Long, summary: String) =
        withContext(dispatcherProvider.io) {
            bookmarkDao.updateSummary(id, summary)
        }

    override suspend fun updateCategory(id: Long, category: String) =
        withContext(dispatcherProvider.io) {
            bookmarkDao.updateCategory(id, category)
        }



    override suspend fun deleteBookmark(bookmark: Bookmark) =
        withContext(dispatcherProvider.io) {
            bookmarkDao.delete(bookmark.toEntity())
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
            val selfQuery = embeddingBox.query(EmbeddingEntity_.bookmarkId.equal(bookmarkId)).build()
            val myEmbedding = selfQuery.findFirst()
            selfQuery.close()
            if (myEmbedding == null) return@withContext emptyList()

            // 2. そのベクトルで類似検索(自分自身も結果に含まれるため+1件多めに取る)
            val nnQuery = embeddingBox.query(
                EmbeddingEntity_.vector.nearestNeighbors(myEmbedding.vector, limit + 1)
            ).build()
            val results = nnQuery.findWithScores()
            nnQuery.close()

            val relatedIds = results.map { it.get().bookmarkId }
                .filter { it != bookmarkId } // 自分自身を除外
                .take(limit)

            bookmarkDao.getByIds(relatedIds).map { it.toDomain() }
        }

    override suspend fun getEmbeddingModelVersion(bookmarkId: Long): String? =
        withContext(dispatcherProvider.io) {
            val query = embeddingBox.query(EmbeddingEntity_.bookmarkId.equal(bookmarkId)).build()
            val existing = query.findFirst()
            query.close()
            existing?.modelVersion
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
