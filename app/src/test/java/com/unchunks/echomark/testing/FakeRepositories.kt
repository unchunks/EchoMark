package com.unchunks.echomark.testing

import com.unchunks.echomark.domain.bookmark.model.TagMatch
import com.unchunks.echomark.domain.bookmark.model.BookmarkQuery
import com.unchunks.echomark.domain.bookmark.model.AiStatus
import com.unchunks.echomark.domain.bookmark.model.ProcessingFailureReason
import com.unchunks.echomark.domain.bookmark.model.ProcessingQueue
import com.unchunks.echomark.domain.model.SummaryOrigin
import com.unchunks.echomark.domain.model.SummaryVersion
import com.unchunks.echomark.domain.bookmark.model.ProcessingStage
import com.unchunks.echomark.domain.bookmark.model.StageDurationKey
import com.unchunks.echomark.domain.repository.ProcessingStatsRepository
import com.unchunks.echomark.domain.usage.ApiUsageRecord
import com.unchunks.echomark.domain.usage.ApiUsageRepository
import com.unchunks.echomark.domain.bookmark.model.AttachmentError
import com.unchunks.echomark.domain.bookmark.model.AttachmentException
import com.unchunks.echomark.domain.bookmark.model.StoredAttachment
import com.unchunks.echomark.domain.bookmark.model.bookmarkTypeOfMimeType
import com.unchunks.echomark.domain.bookmark.model.titleFromFileName
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkFilter
import com.unchunks.echomark.domain.bookmark.model.BookmarkSortOrder
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.model.AnalysisScope
import com.unchunks.echomark.domain.model.ChatAttachmentContext
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.model.ChatScopeChoices
import com.unchunks.echomark.domain.model.Conversation
import com.unchunks.echomark.domain.model.ConversationPreview
import com.unchunks.echomark.domain.model.EmbeddingProgress
import com.unchunks.echomark.domain.model.Tag
import com.unchunks.echomark.domain.repository.AiSetupRepository
import com.unchunks.echomark.domain.repository.AiSetupState
import com.unchunks.echomark.domain.repository.AttachmentRepository
import com.unchunks.echomark.domain.repository.BookmarkRepository
import com.unchunks.echomark.domain.repository.ChatModelChoices
import com.unchunks.echomark.domain.repository.ChatModelOption
import com.unchunks.echomark.domain.repository.ChatModelRepository
import com.unchunks.echomark.domain.repository.ChatRepository
import com.unchunks.echomark.domain.repository.ChatStreamEvent
import com.unchunks.echomark.domain.repository.SaveResult
import com.unchunks.echomark.domain.model.CategoryDefinition
import com.unchunks.echomark.domain.model.CategoryEntry
import com.unchunks.echomark.domain.model.CategoryKind
import com.unchunks.echomark.domain.model.TagNames
import com.unchunks.echomark.domain.model.TagTree
import com.unchunks.echomark.domain.repository.CategoryRepository
import com.unchunks.echomark.domain.repository.CategoryResult
import com.unchunks.echomark.domain.repository.TagParentResult
import com.unchunks.echomark.domain.model.TagWithCount
import com.unchunks.echomark.domain.repository.BookmarkSearchResult
import com.unchunks.echomark.domain.repository.TagRenameResult
import com.unchunks.echomark.domain.repository.TagRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

fun testBookmark(
    id: Long,
    title: String = "title$id",
    lastAccessedAt: Long = 0L
) = Bookmark(
    id = id,
    type = BookmarkType.TEXT,
    title = title,
    createdAt = 0L,
    lastAccessedAt = lastAccessedAt
)

/** ViewModel テスト用の手書き Fake。使わないメソッドは呼ばれたら失敗させる。 */
class FakeBookmarkRepository : BookmarkRepository {
    val bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    val tags = MutableStateFlow<List<Tag>>(emptyList())

    /** search / searchWithDetails に渡された検索語 */
    val searchCalls = mutableListOf<String>()
    val deleted = mutableListOf<Bookmark>()
    val restored = mutableListOf<Bookmark>()

    override suspend fun search(query: String): List<Bookmark> {
        searchCalls += query
        return bookmarks.value.filter { it.title.contains(query) }
    }

    override fun observeBookmarks(): Flow<List<Bookmark>> = bookmarks

    override fun observeAllTags(): Flow<List<Tag>> = tags

    /**
     * DAO のクエリと同じ規則で絞り込み・並べ替えする(タグは tags に tagId の文字列を入れて表す。階層は持たない。
     * カテゴリは大文字小文字を区別しない)。
     */
    override fun observeBookmarks(
        query: BookmarkQuery,
        sortOrder: BookmarkSortOrder
    ): Flow<List<Bookmark>> = bookmarks.map { list ->
        list
            .filter {
                when (query.filter) {
                    BookmarkFilter.ACTIVE -> !it.isArchived
                    BookmarkFilter.ARCHIVED -> it.isArchived
                    BookmarkFilter.FAVORITES -> it.isFavorite && !it.isArchived
                }
            }
            .filter { bookmark ->
                val has = { id: Long -> id.toString() in bookmark.tags }
                query.tagIds.isEmpty() || when (query.tagMatch) {
                    TagMatch.ALL -> query.tagIds.all(has)
                    TagMatch.ANY -> query.tagIds.any(has)
                }
            }
            .filter { bookmark ->
                query.categories.isEmpty() || query.categories.any { it.equals(bookmark.category, ignoreCase = true) }
            }
            .sortedWith(
                when (sortOrder) {
                    BookmarkSortOrder.NEWEST -> compareByDescending<Bookmark> { it.createdAt }
                    BookmarkSortOrder.OLDEST -> compareBy<Bookmark> { it.createdAt }
                    BookmarkSortOrder.RECENTLY_OPENED -> compareByDescending<Bookmark> { it.lastAccessedAt }
                    BookmarkSortOrder.TITLE -> compareBy<Bookmark, String>(String.CASE_INSENSITIVE_ORDER) { it.title }
                }.thenByDescending { it.createdAt }.thenByDescending { it.id }
            )
    }

    override suspend fun setFavorite(id: Long, isFavorite: Boolean) =
        updateBookmark(id) { it.copy(isFavorite = isFavorite) }

    override suspend fun setArchived(id: Long, isArchived: Boolean) =
        updateBookmark(id) { it.copy(isArchived = isArchived) }

    override suspend fun updateLinkMetadata(id: Long, imageUrl: String?, siteName: String?) =
        updateBookmark(id) { it.copy(imageUrl = imageUrl, siteName = siteName) }

    override suspend fun markContentFetched(id: Long, fetchedAt: Long) =
        updateBookmark(id) { it.copy(contentFetchedAt = fetchedAt) }

    private fun updateBookmark(id: Long, transform: (Bookmark) -> Bookmark) {
        bookmarks.value = bookmarks.value.map { if (it.id == id) transform(it) else it }
    }

    override suspend fun deleteBookmark(bookmark: Bookmark) {
        deleted += bookmark
        bookmarks.value = bookmarks.value.filterNot { it.id == bookmark.id }
    }

    override suspend fun restoreBookmark(bookmark: Bookmark) {
        restored += bookmark
        bookmarks.value = bookmarks.value + bookmark
    }

    override suspend fun saveBookmark(bookmark: Bookmark): SaveResult {
        val id = (bookmarks.value.maxOfOrNull { it.id } ?: 0L) + 1L
        bookmarks.value = bookmarks.value + bookmark.copy(id = id)
        return SaveResult(id, isDuplicate = false)
    }

    /** 同じ URL が既にあれば重複として既存の ID を返す(実装と同じ規則)。 */
    override suspend fun saveUrlBookmark(url: String, title: String?, memo: String?): SaveResult {
        val trimmed = url.trim()
        savedUrls += Triple(trimmed, title, memo)
        bookmarks.value.firstOrNull { it.contentUri == trimmed }?.let { return SaveResult(it.id, isDuplicate = true) }
        return saveBookmark(
            Bookmark(
                type = BookmarkType.URL,
                contentUri = trimmed,
                content = memo?.takeIf { it.isNotBlank() },
                title = title?.takeIf { it.isNotBlank() } ?: trimmed,
                createdAt = 0L,
                lastAccessedAt = 0L
            )
        )
    }

    /** 保存したファイルのブックマーク(ファイル・タイトル・メモ)。種類の決め方は実装と同じ */
    val savedFiles = mutableListOf<Triple<StoredAttachment, String?, String?>>()

    override suspend fun saveFileBookmark(attachment: StoredAttachment, title: String?, memo: String?): SaveResult {
        savedFiles += Triple(attachment, title, memo)
        val type = bookmarkTypeOfMimeType(attachment.mimeType)
            ?: throw AttachmentException(AttachmentError.Unsupported(attachment.mimeType))
        return saveBookmark(
            Bookmark(
                type = type,
                content = memo?.takeIf { it.isNotBlank() },
                title = title?.takeIf { it.isNotBlank() } ?: titleFromFileName(attachment.fileName),
                createdAt = 0L,
                lastAccessedAt = 0L,
                filePath = attachment.filePath,
                mimeType = attachment.mimeType,
                fileName = attachment.fileName,
                fileSize = attachment.fileSize
            )
        )
    }

    override suspend fun updateAttachment(id: Long, attachment: StoredAttachment?) =
        updateBookmark(id) {
            it.copy(
                filePath = attachment?.filePath,
                mimeType = attachment?.mimeType,
                fileName = attachment?.fileName,
                fileSize = attachment?.fileSize
            )
        }

    override suspend fun getStaleBookmarks(threshold: Long, limit: Int): List<Bookmark> =
        bookmarks.value.filter { it.lastAccessedAt <= threshold }.sortedBy { it.lastAccessedAt }.take(limit)

    /** searchWithDetails の「意味検索が使えたか」。 */
    var semanticAvailable = true
    /** searchWithDetails で「意味だけで見つかった」扱いにする ID。 */
    var semanticOnlyIds: Set<Long> = emptySet()
    val savedUrls = mutableListOf<Triple<String, String?, String?>>()
    val accessedIds = mutableListOf<Long>()
    val reprocessedIds = mutableListOf<Long>()
    var related: List<Bookmark> = emptyList()

    override suspend fun searchWithDetails(query: String): BookmarkSearchResult {
        searchCalls += query
        val hits = bookmarks.value.filter {
            it.title.contains(query) || it.id in semanticOnlyIds
        }
        return BookmarkSearchResult(hits, semanticAvailable, semanticOnlyIds.intersect(hits.map { it.id }.toSet()))
    }

    /** searchKeywordOnly の呼び出し(検索語) */
    val keywordSearchCalls = mutableListOf<String>()

    override suspend fun searchKeywordOnly(query: String): List<Bookmark> {
        keywordSearchCalls += query
        return bookmarks.value.filter { it.title.contains(query) }
    }

    /** prepareSemanticSearch の呼び出し(検索語) */
    val prepareCalls = mutableListOf<String>()

    /** 非NULLなら、prepareSemanticSearch はこれが完了するまで戻らない(埋め込みが遅いときを再現する) */
    var prepareGate: CompletableDeferred<Unit>? = null

    override suspend fun prepareSemanticSearch(query: String): Boolean {
        prepareCalls += query
        prepareGate?.await()
        return semanticAvailable
    }

    override fun observeBookmarkCount(): Flow<Int> = bookmarks.map { it.size }

    override fun observeBookmark(id: Long): Flow<Bookmark?> = bookmarks.map { list -> list.firstOrNull { it.id == id } }

    override suspend fun getBookmarkById(id: Long): Bookmark? = bookmarks.value.firstOrNull { it.id == id }

    override suspend fun markAccessed(id: Long) {
        accessedIds += id
    }

    /** reprocess の呼び出し(ID と作り直すもの) */
    val reprocessCalls = mutableListOf<Pair<Long, AnalysisScope>>()

    override suspend fun reprocess(id: Long, scope: AnalysisScope) {
        reprocessedIds += id
        reprocessCalls += id to scope
        updateBookmark(id) {
            if (scope == AnalysisScope.ALL) it.copy(aiStatus = AiStatus.PENDING, summary = null) else it.copy(aiStatus = AiStatus.PENDING)
        }
    }

    override suspend fun updateTitleAndContent(id: Long, title: String, content: String?) =
        updateBookmark(id) { it.copy(title = title, content = content) }

    override suspend fun addTag(bookmarkId: Long, tagName: String) =
        updateBookmark(bookmarkId) { if (tagName in it.tags) it else it.copy(tags = it.tags + tagName) }

    override suspend fun removeTag(bookmarkId: Long, tagName: String) =
        updateBookmark(bookmarkId) { it.copy(tags = it.tags - tagName) }

    override suspend fun getRelatedBookmarks(bookmarkId: Long, limit: Int): List<Bookmark> = related.take(limit)

    override suspend fun saveAiTags(bookmarkId: Long, tagNames: List<String>) = TODO("not used")
    override suspend fun getTagNamesForAi(): List<String> = TODO("not used")

    /** AI の分類に渡す、ユーザーが作ったカテゴリ */
    var categoryDefinitions: List<CategoryDefinition> = emptyList()
    val categoryCalls = mutableListOf<Pair<Long, String?>>()

    override suspend fun getCategoryDefinitionsForAi(): List<CategoryDefinition> = categoryDefinitions

    override suspend fun setCategory(id: Long, category: String) {
        categoryCalls += id to category
        bookmarks.value = bookmarks.value.map { if (it.id == id) it.copy(category = category, categoryEdited = true) else it }
    }

    override suspend fun resetCategory(id: Long) {
        categoryCalls += id to null
        bookmarks.value = bookmarks.value.map { if (it.id == id) it.copy(category = null, categoryEdited = false) else it }
    }

    /** suggestTags に渡された内容と、返す提案 */
    val suggestCalls = mutableListOf<String>()
    var suggestions: List<String> = emptyList()
    var suggestFailure: Exception? = null

    override suspend fun suggestTags(text: String, limit: Int): List<String> {
        suggestFailure?.let { throw it }
        suggestCalls += text
        return suggestions.take(limit)
    }
    override suspend fun saveEmbedding(bookmarkId: Long, vector: FloatArray, modelVersion: String) = TODO("not used")
    override suspend fun updateSummary(id: Long, summary: String) = TODO("not used")
    /** 作った要約の保存(履歴の版は持たず、要約と印だけ) */
    val generatedSummaries = mutableListOf<Triple<Long, String, SummaryOrigin?>>()
    val editedSummaries = mutableListOf<Pair<Long, String>>()
    val restoredSummaryVersions = mutableListOf<Long>()
    val summaryHistory = MutableStateFlow<List<SummaryVersion>>(emptyList())

    override suspend fun saveGeneratedSummary(id: Long, summary: String, origin: SummaryOrigin?) {
        generatedSummaries += Triple(id, summary, origin)
        updateBookmark(id) { it.copy(summary = summary, summaryEdited = false) }
    }

    override suspend fun editSummary(id: Long, summary: String) {
        editedSummaries += id to summary
        updateBookmark(id) { it.copy(summary = summary, summaryEdited = true) }
    }

    override suspend fun restoreSummary(id: Long, versionId: Long) {
        restoredSummaryVersions += versionId
    }

    override fun observeSummaryHistory(id: Long): Flow<List<SummaryVersion>> = summaryHistory

    override suspend fun updateCategory(id: Long, category: String) = TODO("not used")
    /** 処理の状況(処理中・処理待ち・失敗を bookmarks から作る) */
    override fun observeProcessingQueue(): Flow<ProcessingQueue> = bookmarks.map { list ->
        ProcessingQueue(
            running = list.filter { it.aiStatus == AiStatus.PROCESSING },
            waiting = list.filter { it.aiStatus == AiStatus.PENDING },
            recentFailures = list.filter { it.aiStatus == AiStatus.FAILED }
        )
    }

    val cancelledIds = mutableListOf<Long>()
    val processNowIds = mutableListOf<Long>()
    var bulkCancelled = 0

    override suspend fun cancelProcessing(ids: List<Long>) {
        cancelledIds += ids
        ids.forEach { id ->
            updateBookmark(id) {
                it.copy(
                    aiStatus = AiStatus.FAILED,
                    processing = it.processing.copy(stage = null, failureReason = ProcessingFailureReason.CANCELLED)
                )
            }
        }
    }

    override suspend fun processNow(id: Long) {
        processNowIds += id
    }

    override suspend fun cancelBulkProcessing(): Int {
        bulkCancelled++
        return 0
    }

    /** observeStageProgress が返す、実行中の段の進み具合 */
    val stageProgress = MutableStateFlow<Float?>(null)
    override fun observeStageProgress(id: Long): Flow<Float?> = stageProgress

    /** 始めた段(ブックマークの ID と段)を順に記録する。 */
    val startedStages = mutableListOf<Pair<Long, ProcessingStage>>()
    private var stageClock = 0L

    // 処理の状態の移り変わりは、DAO の更新と同じ規則にする
    override suspend fun startProcessingStage(id: Long, stage: ProcessingStage): Long {
        val now = ++stageClock
        startedStages += id to stage
        updateBookmark(id) {
            it.copy(
                aiStatus = AiStatus.PROCESSING,
                processing = it.processing.copy(
                    stage = stage, stageStartedAt = now, startedAt = it.processing.startedAt ?: now, finishedAt = null
                )
            )
        }
        return now
    }

    override suspend fun endProcessingStage(id: Long, stageStartedAt: Long) =
        updateBookmark(id) {
            if (it.aiStatus != AiStatus.PROCESSING || it.processing.stageStartedAt != stageStartedAt) {
                it
            } else {
                it.copy(aiStatus = AiStatus.PENDING, processing = it.processing.copy(stage = null, stageStartedAt = null))
            }
        }

    override suspend fun markProcessingRetrying(id: Long, reason: ProcessingFailureReason) =
        updateBookmark(id) {
            it.copy(
                aiStatus = AiStatus.PENDING,
                processing = it.processing.copy(stage = null, stageStartedAt = null, failureReason = reason)
            )
        }

    override suspend fun finishProcessing(id: Long, status: AiStatus, reason: ProcessingFailureReason?) =
        updateBookmark(id) {
            it.copy(
                aiStatus = status,
                processing = it.processing.copy(
                    stage = null, stageStartedAt = null, finishedAt = ++stageClock, failureReason = reason
                )
            )
        }
    var waitingModelEnqueues = 0
    override suspend fun enqueueWaitingModelProcessing() {
        waitingModelEnqueues++
    }
    override suspend fun enqueueFailedAndWaitingProcessing(): Int = TODO("not used")
    override suspend fun enqueueStalledProcessing(processStartedAt: Long): Int = TODO("not used")
    /** enqueueWaitingProcessing の呼び出し([nightly] の値) */
    val waitingProcessingCalls = mutableListOf<Boolean>()
    override suspend fun enqueueWaitingProcessing(nightly: Boolean): Int {
        waitingProcessingCalls += nightly
        return 0
    }
    override suspend fun getBookmarksByIds(ids: List<Long>): List<Bookmark> = TODO("not used")
    override suspend fun getAllBookmarkIds(): List<Long> = TODO("not used")
    override suspend fun getEmbeddingModelVersion(bookmarkId: Long): String? = TODO("not used")

    /** [getEmbeddingProgress] が返す値(テストで差し替える)。 */
    var embeddingProgress = EmbeddingProgress(embedded = 0, total = 0)
    override suspend fun getEmbeddingProgress(): EmbeddingProgress = embeddingProgress
}

/** タグ管理のフェイク。統合・削除は tags の中だけで表す。 */
class FakeCategoryRepository : CategoryRepository {
    val entries = MutableStateFlow<List<CategoryEntry>>(emptyList())
    val added = mutableListOf<Pair<String, String>>()
    val deletedIds = mutableListOf<Long>()
    var result: CategoryResult? = null

    override fun observeCategories(): Flow<List<CategoryEntry>> = entries

    override suspend fun addCategory(name: String, description: String): CategoryResult {
        added += name to description
        result?.let { return it }
        entries.value = entries.value + CategoryEntry(name, description, CategoryKind.USER, 0, id = entries.value.size + 1L)
        return CategoryResult.Saved(name)
    }

    override suspend fun updateCategory(id: Long, name: String, description: String): CategoryResult {
        result?.let { return it }
        entries.value = entries.value.map { if (it.id == id) it.copy(name = name, description = description) else it }
        return CategoryResult.Saved(name)
    }

    override suspend fun deleteCategory(id: Long) {
        deletedIds += id
        entries.value = entries.value.filterNot { it.id == id }
    }
}

class FakeTagRepository : TagRepository {
    val tags = MutableStateFlow<List<TagWithCount>>(emptyList())
    val deletedIds = mutableListOf<Long>()

    override fun observeTagsWithCount(): Flow<List<TagWithCount>> = tags

    override suspend fun renameTag(tagId: Long, newName: String): TagRenameResult {
        val name = TagNames.clean(newName)
        val current = tags.value.firstOrNull { it.id == tagId } ?: return TagRenameResult.Invalid
        if (name.isEmpty()) return TagRenameResult.Invalid
        if (current.name == name) return TagRenameResult.Unchanged
        val others = tags.value.filter { it.id != tagId }
        val existing = TagNames.findSame(name, others.map { it.name })?.let { same -> others.first { it.name == same } }
        return if (existing == null) {
            tags.value = tags.value.map { if (it.id == tagId) it.copy(name = name) else it }
            TagRenameResult.Renamed(tagId)
        } else {
            tags.value = tags.value
                .filterNot { it.id == tagId }
                .map { if (it.id == existing.id) it.copy(bookmarkCount = it.bookmarkCount + current.bookmarkCount) else it }
            TagRenameResult.Merged(existing.id)
        }
    }

    val mergeCalls = mutableListOf<Pair<Long, List<Long>>>()
    val parentCalls = mutableListOf<Pair<Long, Long?>>()

    override suspend fun setParent(tagId: Long, parentId: Long?): TagParentResult {
        parentCalls += tagId to parentId
        if (tags.value.none { it.id == tagId } || (parentId != null && tags.value.none { it.id == parentId })) {
            return TagParentResult.Invalid
        }
        if (parentId != null && (parentId == tagId || parentId in TagTree.descendantIds(tags.value, tagId))) {
            return TagParentResult.Cycle
        }
        tags.value = tags.value.map { if (it.id == tagId) it.copy(parentId = parentId) else it }
        return TagParentResult.Updated
    }

    override suspend fun mergeTags(intoTagId: Long, fromTagIds: List<Long>): Int {
        mergeCalls += intoTagId to fromTagIds
        val into = tags.value.firstOrNull { it.id == intoTagId } ?: return 0
        val from = tags.value.filter { it.id in fromTagIds && it.id != intoTagId }
        tags.value = tags.value
            .filterNot { tag -> from.any { it.id == tag.id } }
            .map { if (it.id == into.id) it.copy(bookmarkCount = it.bookmarkCount + from.sumOf { f -> f.bookmarkCount }) else it }
        return from.size
    }

    override suspend fun deleteTag(tagId: Long) {
        deletedIds += tagId
        tags.value = tags.value.filterNot { it.id == tagId }
    }
}

open class FakeChatRepository : ChatRepository {
    val messages = MutableStateFlow<List<ChatMessage>>(emptyList())

    val previews = MutableStateFlow<List<ConversationPreview>>(emptyList())
    val conversation = MutableStateFlow<Conversation?>(null)

    /** getBookmarks / getRecentBookmarks が返すブックマーク(新しい順)。 */
    var bookmarks: List<Bookmark> = emptyList()

    var createdConversationId = 42L
    var createCalls = 0
    val createdTitles = mutableListOf<String?>()
    /** createConversation に渡された「このブックマークについて質問」の対象。 */
    val createdAboutBookmarkIds = mutableListOf<Long?>()
    val sent = mutableListOf<Pair<Long, String>>()

    /** sendMessageStream の呼び出し(固定したブックマーク・再試行かどうかを含む)。 */
    data class SendCall(val conversationId: Long, val text: String, val pinnedBookmarkId: Long?, val isRetry: Boolean)
    val sendCalls = mutableListOf<SendCall>()

    /** sendMessageStream に渡された範囲。 */
    val sentScopes = mutableListOf<BookmarkQuery?>()

    /** latestConversationAbout が返す会話。 */
    var latestAboutConversationId: Long? = null
    val scopeChoices = MutableStateFlow(ChatScopeChoices())
    val scopeCount = MutableStateFlow(0)

    val renamed = mutableListOf<Pair<Long, String>>()
    val deletedIds = mutableListOf<Long>()
    val restored = mutableListOf<Pair<Conversation, List<ChatMessage>>>()

    /** 非 null なら sendMessageStream がこの例外で Failed を流す。 */
    var sendFailure: Throwable? = null

    override suspend fun createConversation(title: String?, aboutBookmarkId: Long?): Long {
        createCalls++
        createdTitles += title
        createdAboutBookmarkIds += aboutBookmarkId
        return createdConversationId
    }

    override fun observeMessages(conversationId: Long): Flow<List<ChatMessage>> = messages

    override fun observeConversation(conversationId: Long): Flow<Conversation?> = conversation

    override fun observeConversationPreviews(): Flow<List<ConversationPreview>> = previews

    override suspend fun latestConversationAbout(bookmarkId: Long): Long? = latestAboutConversationId
    override fun observeScopeChoices(): Flow<ChatScopeChoices> = scopeChoices
    override fun observeScopeCount(scope: BookmarkQuery): Flow<Int> = scopeCount

    /** 既定: sendFailure があれば Failed、無ければ Started → Delta → Completed を流す。 */
    /** sendMessageStream に渡された添付(添付なしの送信は null)。 */
    val sentAttachments = mutableListOf<ChatAttachmentContext?>()

    override fun sendMessageStream(
        conversationId: Long,
        userMessage: String,
        pinnedBookmarkId: Long?,
        isRetry: Boolean,
        attachment: ChatAttachmentContext?,
        scope: BookmarkQuery?
    ): Flow<ChatStreamEvent> = flow {
        sentScopes += scope
        sent += conversationId to userMessage
        sendCalls += SendCall(conversationId, userMessage, pinnedBookmarkId, isRetry)
        sentAttachments += attachment
        val failure = sendFailure
        if (failure != null) {
            emit(ChatStreamEvent.Failed(failure))
            return@flow
        }
        emit(ChatStreamEvent.Started(emptyList()))
        emit(ChatStreamEvent.Delta("回答"))
        emit(ChatStreamEvent.Completed(1L))
    }

    override suspend fun getBookmarks(ids: List<Long>): Map<Long, Bookmark> =
        bookmarks.filter { it.id in ids }.associateBy { it.id }

    /** deleteMessagesFrom に渡されたメッセージ。 */
    val deletedFrom = mutableListOf<ChatMessage>()

    override suspend fun deleteMessagesFrom(message: ChatMessage) {
        deletedFrom += message
        val index = messages.value.indexOfFirst { it.id == message.id }
        if (index >= 0) messages.value = messages.value.take(index)
    }

    override suspend fun getRecentBookmarks(limit: Int): List<Bookmark> = bookmarks.take(limit)

    override suspend fun renameConversation(conversationId: Long, title: String) {
        renamed += conversationId to title
    }

    override suspend fun deleteConversation(conversationId: Long) {
        deletedIds += conversationId
        previews.value = previews.value.filterNot { it.conversation.id == conversationId }
    }

    override suspend fun restoreConversation(conversation: Conversation, messages: List<ChatMessage>) {
        restored += conversation to messages
        previews.value = (previews.value + ConversationPreview(conversation))
            .sortedByDescending { it.conversation.updatedAt }
    }
}

/** AI の準備状態の Fake。 */
class FakeAiSetupRepository(initial: AiSetupState = AiSetupState.READY) : AiSetupRepository {
    val state = MutableStateFlow(initial)
    override val setupState: Flow<AiSetupState> = state
}

/** チャットの AI の選択肢の Fake。[selections] に選ばれた順に残す。 */
class FakeChatModelRepository(initial: ChatModelChoices = ChatModelChoices()) : ChatModelRepository {
    val state = MutableStateFlow(initial)
    val selections = mutableListOf<ChatModelOption>()
    override val choices: Flow<ChatModelChoices> = state
    override suspend fun select(option: ChatModelOption) {
        selections += option
        state.value = state.value.copy(selected = option)
    }
}

/**
 * ファイルの取り込みの Fake。[files] に uri ごとの取り込み結果を入れておく(無ければ uri から作る)。
 * [failures] にある uri は、その理由で失敗させる。
 */
class FakeAttachmentRepository : AttachmentRepository {
    val files = mutableMapOf<String, StoredAttachment>()
    val failures = mutableMapOf<String, AttachmentError>()
    val imported = mutableListOf<String>()

    override suspend fun importFile(uri: String): StoredAttachment {
        imported += uri
        failures[uri]?.let { throw AttachmentException(it) }
        return files[uri] ?: StoredAttachment(
            filePath = "attachments/${uri.hashCode().toUInt()}.jpg",
            mimeType = "image/jpeg",
            fileName = uri.substringAfterLast('/'),
            fileSize = 1024L
        )
    }
}

/** 段ごとの所要時間の記録の Fake。記録した値をそのまま平均として返す(重み付けはしない)。 */
class FakeProcessingStatsRepository : ProcessingStatsRepository {
    val averages = MutableStateFlow<Map<StageDurationKey, Long>>(emptyMap())
    val recorded = mutableListOf<Pair<StageDurationKey, Long>>()
    override val averageStageDurations: Flow<Map<StageDurationKey, Long>> = averages

    override suspend fun recordStageDuration(key: StageDurationKey, millis: Long) {
        recorded += key to millis
        averages.value = averages.value + (key to millis)
    }
}

/** クラウド API の使用量の記録の Fake。記録した値をそのまま持つ。 */
class FakeApiUsageRepository : ApiUsageRepository {
    val records = MutableStateFlow<List<ApiUsageRecord>>(emptyList())

    /** 設定すると、記録がこの例外で失敗する */
    var failure: Exception? = null

    override suspend fun record(usage: ApiUsageRecord) {
        failure?.let { throw it }
        records.value = records.value + usage
    }

    override fun observeSince(from: Long): Flow<List<ApiUsageRecord>> =
        records.map { list -> list.filter { it.createdAt >= from } }
}
