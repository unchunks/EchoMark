package com.unchunks.echomark.data.repository

import androidx.work.BackoffPolicy
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
import com.unchunks.echomark.domain.repository.BookmarkSearchResult
import com.unchunks.echomark.domain.bookmark.model.AiStatus
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkFilter
import com.unchunks.echomark.domain.bookmark.model.BookmarkSortOrder
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.model.Tag
import com.unchunks.echomark.domain.provider.EmbeddingProvider
import com.unchunks.echomark.domain.search.RankFusion
import com.unchunks.echomark.worker.BookmarkAiProcessingWorker
import com.unchunks.echomark.worker.UrlFetchWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.collections.map

class BookmarkRepositoryImpl @Inject constructor(
    private val bookmarkDao: BookmarkDao,
    private val tagDao: TagDao,
    private val vectorSearch: VectorSearchDataSource,
    private val dispatcherProvider: DispatcherProvider,
    private val workManager: WorkManager,
    private val embeddingProvider: EmbeddingProvider
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

    private fun buildAiRequest(bookmarkId: Long) =
        OneTimeWorkRequestBuilder<BookmarkAiProcessingWorker>()
            .setInputData(workDataOf(BookmarkAiProcessingWorker.KEY_BOOKMARK_ID to bookmarkId))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

    /** AI処理のみを実行する(本文取得済み・モデル待ちからの再開など) */
    private fun enqueueAiProcessing(bookmarkId: Long) {
        workManager.enqueue(buildAiRequest(bookmarkId))
    }

    /** URLは「本文取得 → AI処理」のチェーン、それ以外はAI処理のみを実行する */
    private fun enqueueProcessing(bookmarkId: Long, type: BookmarkType) {
        val inputData = workDataOf(BookmarkAiProcessingWorker.KEY_BOOKMARK_ID to bookmarkId)
        val aiRequest = buildAiRequest(bookmarkId)

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
            enqueueAiProcessing(bookmarkId)
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

    override suspend fun updateAiStatus(id: Long, status: AiStatus) =
        withContext(dispatcherProvider.io) {
            bookmarkDao.updateAiStatus(id, status)
        }

    override suspend fun enqueueWaitingModelProcessing() =
        withContext(dispatcherProvider.io) {
            bookmarkDao.getIdsByAiStatus(AiStatus.WAITING_MODEL).forEach { id ->
                bookmarkDao.updateAiStatus(id, AiStatus.PENDING)
                enqueueAiProcessing(id)
            }
        }

    override suspend fun enqueueFailedAndWaitingProcessing(): Int =
        withContext(dispatcherProvider.io) {
            val ids = bookmarkDao.getIdsByAiStatus(AiStatus.FAILED) +
                bookmarkDao.getIdsByAiStatus(AiStatus.WAITING_MODEL)
            ids.distinct().forEach { id ->
                bookmarkDao.updateAiStatus(id, AiStatus.PENDING)
                enqueueAiProcessing(id)
            }
            ids.distinct().size
        }

    override suspend fun reprocess(id: Long) =
        withContext(dispatcherProvider.io) {
            bookmarkDao.resetAiResult(id, AiStatus.PENDING)
            enqueueAiProcessing(id)
        }

    override suspend fun restoreBookmark(bookmark: Bookmark) {
        withContext(dispatcherProvider.io) {
            // 同じ ID で挿入し直す(削除済みなので競合しない)。ベクトルは削除時に消えているため再生成する
            bookmarkDao.insert(bookmark.copy(aiStatus = AiStatus.PENDING).toEntity())
            bookmark.tags.forEach { name ->
                val tagId = getOrCreateTagId(name)
                tagDao.insertCrossRef(BookmarkTagCrossRef(bookmark.id, tagId))
            }
            enqueueAiProcessing(bookmark.id)
        }
    }

    override suspend fun addTag(bookmarkId: Long, tagName: String) {
        val name = tagName.trim()
        if (name.isEmpty()) return
        saveTags(bookmarkId, listOf(name))
    }

    override suspend fun removeTag(bookmarkId: Long, tagName: String) =
        withContext(dispatcherProvider.io) {
            val tag = tagDao.getTagByName(tagName) ?: return@withContext
            tagDao.deleteCrossRef(bookmarkId, tag.id)
        }

    override suspend fun markAccessed(id: Long) =
        withContext(dispatcherProvider.io) {
            bookmarkDao.updateLastAccessedAt(id, System.currentTimeMillis())
        }

    override suspend fun setFavorite(id: Long, isFavorite: Boolean) =
        withContext(dispatcherProvider.io) {
            bookmarkDao.updateFavorite(id, isFavorite)
        }

    override suspend fun setArchived(id: Long, isArchived: Boolean) =
        withContext(dispatcherProvider.io) {
            bookmarkDao.updateArchived(id, isArchived)
        }

    override suspend fun updateLinkMetadata(id: Long, imageUrl: String?, siteName: String?) =
        withContext(dispatcherProvider.io) {
            bookmarkDao.updateLinkMetadata(id, imageUrl, siteName)
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


    override suspend fun getStaleBookmarks(threshold: Long, limit: Int): List<Bookmark> =
        withContext(dispatcherProvider.io) {
            bookmarkDao.getStale(threshold, limit).map { it.toDomain() }
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

    override fun observeBookmark(id: Long): Flow<Bookmark?> =
        bookmarkDao.observeByIdWithTags(id)
            .map { it?.toDomain() }
            .flowOn(dispatcherProvider.io)

    override fun observeBookmarks(
        filter: BookmarkFilter,
        sortOrder: BookmarkSortOrder,
        tagId: Long?
    ): Flow<List<Bookmark>> =
        bookmarkDao.observeFiltered(
            archived = filter == BookmarkFilter.ARCHIVED,
            favoriteOnly = filter == BookmarkFilter.FAVORITES,
            tagId = tagId,
            sortOrder = sortOrder.name
        )
            .map { list -> list.map { it.toDomain() } }
            .flowOn(dispatcherProvider.io)

    override suspend fun search(query: String, tagId: Long?): List<Bookmark> =
        searchWithDetails(query, tagId).bookmarks

    override fun observeBookmarkCount(): Flow<Int> =
        bookmarkDao.observeCount().flowOn(dispatcherProvider.io)

    override suspend fun searchWithDetails(query: String, tagId: Long?): BookmarkSearchResult =
        withContext(dispatcherProvider.io) {
            val q = query.trim()

            // キーワード検索(タイトル・要約・本文・タグ名)。createdAt 降順
            val keyword = bookmarkDao.searchByKeyword(escapeLike(q), tagId).map { it.toDomain() }
            if (q.isEmpty()) return@withContext BookmarkSearchResult(keyword, semanticAvailable = false)

            // ベクトル検索。モデル未取得などで失敗したらキーワードのみにフォールバックする
            val semantic = try {
                val vector = embeddingProvider.embedQuery(q)
                val ids = vectorSearch.nearestNeighbors(vector, VECTOR_TOP_K)
                    .filter { it.score <= MAX_VECTOR_DISTANCE }
                    .map { it.bookmarkId }
                val byId = bookmarkDao.getByIdsWithTags(ids).associateBy { it.bookmark.id }
                ids.mapNotNull { byId[it] }
                    .filter { tagId == null || it.tags.any { tag -> tag.id == tagId } }
                    .map { it.toDomain() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "ベクトル検索に失敗したためキーワード検索のみ実行")
                null
            }

            val semanticList = semantic.orEmpty()
            val all = (keyword + semanticList).associateBy { it.id }
            val keywordIds = keyword.map { it.id }.toSet()
            BookmarkSearchResult(
                bookmarks = RankFusion.fuse(listOf(keyword.map { it.id }, semanticList.map { it.id }))
                    .mapNotNull { all[it] },
                semanticAvailable = semantic != null,
                semanticOnlyIds = semanticList.map { it.id }.filterNot { it in keywordIds }.toSet()
            )
        }

    /** LIKE のワイルドカード(% _)とエスケープ文字自体を無効化する(DAO側は ESCAPE バックスラッシュ)。 */
    private fun escapeLike(s: String): String =
        s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private companion object {
        /** ベクトル検索で取得する上位件数 */
        const val VECTOR_TOP_K = 20

        /**
         * ベクトル検索の採用上限(ObjectBox COSINE の距離 = 1 - コサイン類似度。小さいほど近い)。
         * 0.4 はコサイン類似度 0.6 相当。無関係な結果が混ざる/取りこぼす場合はここを調整する。
         */
        const val MAX_VECTOR_DISTANCE = 0.4
    }
}
