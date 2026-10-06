package com.unchunks.echomark.data.repository

import com.unchunks.echomark.data.attachment.AttachmentStore
import com.unchunks.echomark.data.local.dao.BookmarkDao
import com.unchunks.echomark.data.local.dao.TagDao
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
import com.unchunks.echomark.domain.bookmark.model.AttachmentError
import com.unchunks.echomark.domain.bookmark.model.AttachmentException
import com.unchunks.echomark.domain.bookmark.model.StoredAttachment
import com.unchunks.echomark.domain.bookmark.model.bookmarkTypeOfMimeType
import com.unchunks.echomark.domain.bookmark.model.titleFromFileName
import com.unchunks.echomark.domain.model.Tag
import com.unchunks.echomark.domain.model.TagSource
import com.unchunks.echomark.domain.provider.EmbeddingProvider
import com.unchunks.echomark.domain.search.RankFusion
import com.unchunks.echomark.worker.BookmarkWorkScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import kotlin.collections.map

class BookmarkRepositoryImpl @Inject constructor(
    private val bookmarkDao: BookmarkDao,
    private val tagDao: TagDao,
    private val vectorSearch: VectorSearchDataSource,
    private val dispatcherProvider: DispatcherProvider,
    private val workScheduler: BookmarkWorkScheduler,
    private val embeddingProvider: EmbeddingProvider,
    private val attachmentStore: AttachmentStore
) : BookmarkRepository {

    override suspend fun saveBookmark(bookmark: Bookmark): Long =
        saveBookmarkWithResult(bookmark).id

    override suspend fun saveBookmarkWithResult(bookmark: Bookmark): SaveResult =
        withContext(dispatcherProvider.io) {
            val contentUri = bookmark.contentUri
            if (contentUri == null) {
                val id = bookmarkDao.insert(bookmark.toEntity())
                enqueueProcessing(id, bookmark)
                return@withContext SaveResult(id, isDuplicate = false)
            }

            // contentUri を持つもの(URLなど)は重複チェック。REPLACEするとタグ参照が消えるためIGNOREで挿入する
            val insertedId = bookmarkDao.insertIgnore(bookmark.toEntity())
            if (insertedId != -1L) {
                enqueueProcessing(insertedId, bookmark)
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

    override suspend fun saveFileBookmark(attachment: StoredAttachment, title: String?, memo: String?): SaveResult {
        val type = bookmarkTypeOfMimeType(attachment.mimeType)
            ?: throw AttachmentException(AttachmentError.Unsupported(attachment.mimeType))
        // テキストファイルは中身をそのまま本文にする(取り出しの段は使わない)
        val text = if (type == BookmarkType.TEXT) attachmentStore.readText(attachment.filePath, MAX_TEXT_FILE_CHARS) else null
        val content = listOfNotNull(memo?.trim(), text?.trim()).filter { it.isNotEmpty() }.joinToString("\n\n")
        val now = System.currentTimeMillis()
        return saveBookmarkWithResult(
            Bookmark(
                type = type,
                content = content.ifEmpty { null },
                title = title?.trim()?.takeIf { it.isNotEmpty() } ?: titleFromFileName(attachment.fileName),
                createdAt = now,
                lastAccessedAt = now,
                filePath = attachment.filePath,
                mimeType = attachment.mimeType,
                fileName = attachment.fileName,
                fileSize = attachment.fileSize
            )
        )
    }

    /**
     * 処理をやり直す(再処理・復元・モデル待ちからの再開など)。同じブックマークの処理は置き換える。
     * 本文が未取得の URL は本文の取得から、ファイルのあるものは中身の取り出しから、それ以外は AI 処理のみ
     */
    private suspend fun enqueueReprocessing(bookmark: Bookmark) {
        val target = with(bookmark) { reprocessTarget(id, type, content, filePath, contentFetchedAt) }
        workScheduler.enqueue(bookmark.id, fetchContent = target.fetchContent, extractContent = target.extractContent)
    }

    /**
     * 保存したときの処理。URL は「本文取得 → 中身の取り出し → AI 処理」(リンク先が PDF・画像などなら取得の段で
     * ファイルを保存し、取り出しの段で読む。HTML なら取り出しの段は何もしない)、ファイルは「中身の取り出し → AI 処理」、
     * それ以外は AI 処理のみ
     */
    private suspend fun enqueueProcessing(bookmarkId: Long, bookmark: Bookmark) {
        val fetch = bookmark.type == BookmarkType.URL
        workScheduler.enqueue(
            bookmarkId,
            fetchContent = fetch,
            extractContent = fetch || hasExtractableFile(bookmark.type, bookmark.filePath)
        )
    }

    /** 状態を「処理待ち」に戻し、1件ずつ順番に処理する列に積む(一括の再処理。同時に API を呼びすぎない) */
    private suspend fun enqueueSequentialProcessing(ids: List<Long>) {
        val targets = ids.chunked(ID_QUERY_CHUNK_SIZE).flatMap { chunk ->
            bookmarkDao.getByIds(chunk).map {
                reprocessTarget(it.id, it.type, it.content, it.filePath, it.contentFetchedAt)
            }
        }
        targets.forEach { bookmarkDao.updateAiStatus(it.bookmarkId, AiStatus.PENDING) }
        workScheduler.enqueueSequential(targets)
    }

    /** やり直すときの処理の段。本文を取得し直すときは、リンク先がファイルかもしれないので取り出しの段も付ける */
    private fun reprocessTarget(
        bookmarkId: Long,
        type: BookmarkType,
        content: String?,
        filePath: String?,
        contentFetchedAt: Long?
    ): BookmarkWorkScheduler.Target {
        val fetch = needsContentFetch(type, content, contentFetchedAt)
        return BookmarkWorkScheduler.Target(
            bookmarkId,
            fetchContent = fetch,
            extractContent = fetch || hasExtractableFile(type, filePath)
        )
    }

    /**
     * 本文を取得してから AI 処理すべきか。保存時に取得できなかった・バックアップから本文未取得のまま読み込んだ URL は、
     * そのままでは URL の文字列(メモがあればメモだけ)を要約してしまうため、本文の取得からやり直す。
     * メモを付けて保存した URL は本文が空にならないので、取得できたかは [contentFetchedAt] で判断する
     */
    private fun needsContentFetch(type: BookmarkType, content: String?, contentFetchedAt: Long?): Boolean =
        type == BookmarkType.URL && (contentFetchedAt == null || content.isNullOrBlank())

    /** 中身を取り出す(OCR・PDF のテキスト・文字起こし)ファイルがあるか。テキストファイルは保存時に本文へ入れている */
    private fun hasExtractableFile(type: BookmarkType, filePath: String?): Boolean =
        filePath != null && type != BookmarkType.TEXT

    override suspend fun saveAiTags(bookmarkId: Long, tagNames: List<String>) {
        withContext(dispatcherProvider.io) {
            // AI 処理中に削除されたブックマークには付けない(孤児タグ・外部キー違反を防ぐ)
            if (!tagDao.replaceAiTags(bookmarkId, tagNames)) {
                Timber.i("削除済みのブックマークのためタグを保存しない: id=$bookmarkId")
            }
        }
    }

    override suspend fun getTagNamesForAi(): List<String> =
        withContext(dispatcherProvider.io) {
            tagDao.getTagNamesByPriority(MAX_TAG_NAMES_FOR_AI)
        }

    override suspend fun saveEmbedding(bookmarkId: Long, vector: FloatArray, modelVersion: String): Unit =
        withContext(dispatcherProvider.io) {
            // 削除済みのブックマークのベクトルを残さない。ObjectBox と Room は同じトランザクションにできないため、
            // 書き込みの後にも確かめ、その間に削除されていたら消す
            if (!bookmarkDao.exists(bookmarkId)) return@withContext
            vectorSearch.upsert(bookmarkId, vector, modelVersion)
            if (!bookmarkDao.exists(bookmarkId)) vectorSearch.deleteByBookmarkId(bookmarkId)
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
            enqueueSequentialProcessing(bookmarkDao.getIdsByAiStatus(AiStatus.WAITING_MODEL))
        }

    override suspend fun enqueueFailedAndWaitingProcessing(): Int =
        withContext(dispatcherProvider.io) {
            val ids = (bookmarkDao.getIdsByAiStatus(AiStatus.FAILED) +
                bookmarkDao.getIdsByAiStatus(AiStatus.WAITING_MODEL)).distinct()
            enqueueSequentialProcessing(ids)
            ids.size
        }

    override suspend fun markProcessingInterrupted(id: Long) =
        withContext(dispatcherProvider.io) {
            bookmarkDao.updateAiStatusIf(id, expected = AiStatus.PROCESSING, status = AiStatus.PENDING)
        }

    override suspend fun enqueueStalledProcessing(): Int =
        withContext(dispatcherProvider.io) {
            // ブックマークを先に読む。後から読むと、その間に保存されて処理の登録がまだのものを取り残しと見誤る
            // (それでも重なった場合は同じブックマークを2回処理するだけで、各工程は冪等なので結果は変わらない)
            val ids = (bookmarkDao.getIdsByAiStatus(AiStatus.PENDING) +
                bookmarkDao.getIdsByAiStatus(AiStatus.PROCESSING)).distinct()
            if (ids.isEmpty()) return@withContext 0
            // どのブックマークの処理か分からないワークが残っている間は、二重に積まないよう何もしない
            val withWork = workScheduler.bookmarkIdsWithUnfinishedWork() ?: return@withContext 0
            val stalled = ids.filterNot { it in withWork }
            enqueueSequentialProcessing(stalled)
            stalled.size
        }

    override suspend fun reprocess(id: Long) =
        withContext(dispatcherProvider.io) {
            val bookmark = bookmarkDao.getById(id) ?: return@withContext
            bookmarkDao.resetAiResult(id, AiStatus.PENDING)
            enqueueReprocessing(bookmark.toDomain())
        }

    override suspend fun restoreBookmark(bookmark: Bookmark) {
        withContext(dispatcherProvider.io) {
            // 同じ ID で挿入し直す(削除済みなので競合しない)。ベクトルは削除時に消えているため再生成する
            bookmarkDao.insert(bookmark.copy(aiStatus = AiStatus.PENDING).toEntity())
            // 付けた人も元どおりにする(AI のタグをユーザーのタグに変えてしまわない)
            val (aiTags, userTags) = bookmark.tags.partition { it in bookmark.aiTags }
            tagDao.addTagsToBookmark(bookmark.id, userTags, TagSource.USER)
            tagDao.addTagsToBookmark(bookmark.id, aiTags, TagSource.AI)
            enqueueReprocessing(bookmark)
        }
    }

    override suspend fun addTag(bookmarkId: Long, tagName: String) {
        val name = tagName.trim()
        if (name.isEmpty()) return
        withContext(dispatcherProvider.io) {
            if (!tagDao.addTagsToBookmark(bookmarkId, listOf(name), TagSource.USER)) {
                Timber.i("削除済みのブックマークのためタグを保存しない: id=$bookmarkId")
            }
        }
    }

    override suspend fun removeTag(bookmarkId: Long, tagName: String) =
        withContext(dispatcherProvider.io) {
            tagDao.removeTagFromBookmark(bookmarkId, tagName)
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

    override suspend fun markContentFetched(id: Long, fetchedAt: Long) =
        withContext(dispatcherProvider.io) {
            bookmarkDao.updateContentFetchedAt(id, fetchedAt)
        }

    override suspend fun updateAttachment(id: Long, attachment: StoredAttachment?) =
        withContext(dispatcherProvider.io) {
            bookmarkDao.updateAttachment(
                id,
                filePath = attachment?.filePath,
                mimeType = attachment?.mimeType,
                fileName = attachment?.fileName,
                fileSize = attachment?.fileSize
            )
        }

    override suspend fun updateTitleAndContent(id: Long, title: String, content: String?) =
        withContext(dispatcherProvider.io) {
            bookmarkDao.updateTitleAndContent(id, title, content)
        }



    override suspend fun deleteBookmark(bookmark: Bookmark) =
        withContext(dispatcherProvider.io) {
            // 処理待ち・実行中の本文取得・AI 処理を止める(削除後にクラウドへ送ったり、書き込んだりしない)
            workScheduler.cancel(bookmark.id)
            bookmarkDao.delete(bookmark.toEntity())
            // 紐付けは連鎖して消えるため、AI だけが付けていてどこにも付かなくなったタグを消す
            // (取り消したときは restoreBookmark が同じ名前で付け直す)
            tagDao.deleteOrphanAiTags()
            // ObjectBox側の埋め込みも削除する(孤児ベクトルを残さない)
            vectorSearch.deleteByBookmarkId(bookmark.id)
            // 添付ファイルは「元に戻す」に備えてすぐには消さない。掃除までの猶予を削除の時点から数える
            bookmark.filePath?.let { attachmentStore.touch(it) }
            Unit
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

            // IN 句の結果は ID 順で返るため、近い順に並べ直す
            val byId = bookmarkDao.getByIds(relatedIds).associateBy { it.id }
            relatedIds.mapNotNull { byId[it] }.map { it.toDomain() }
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
        /** テキストファイルから本文に入れる最大文字数 */
        const val MAX_TEXT_FILE_CHARS = 100_000

        /** ID の一覧で問い合わせるときの1回あたりの件数(SQLite の変数の上限より十分小さく) */
        const val ID_QUERY_CHUNK_SIZE = 500

        /** AI に渡す既存タグ名の最大数(プロンプトに入れる量は各 LlmProvider がさらに絞る) */
        const val MAX_TAG_NAMES_FOR_AI = 100

        /** ベクトル検索で取得する上位件数 */
        const val VECTOR_TOP_K = 20

        /**
         * ベクトル検索の採用上限(ObjectBox COSINE の距離 = 1 - コサイン類似度。小さいほど近い)。
         * 0.4 はコサイン類似度 0.6 相当。無関係な結果が混ざる/取りこぼす場合はここを調整する。
         */
        const val MAX_VECTOR_DISTANCE = 0.4
    }
}
