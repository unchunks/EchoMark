package com.unchunks.echomark.data.repository

import com.unchunks.components.core.DispatcherProvider
import com.unchunks.components.embedding.EmbeddingProvider
import com.unchunks.components.embedding.EmbeddingUnavailableException
import com.unchunks.components.llm.ChatRole
import com.unchunks.components.llm.LlmException
import com.unchunks.echomark.data.ai.ChatMemoryCompressor
import com.unchunks.echomark.data.ai.LlmProviderResolver
import com.unchunks.echomark.data.local.dao.ChatMessageDao
import com.unchunks.echomark.data.local.dao.ConversationDao
import com.unchunks.echomark.data.local.entity.ChatMessageEntity
import com.unchunks.echomark.data.local.entity.ConversationEntity
import com.unchunks.echomark.data.local.objectbox.VectorSearchDataSource
import com.unchunks.echomark.data.mapper.toDomain
import com.unchunks.echomark.data.mapper.toEntity
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkFilter
import com.unchunks.echomark.domain.bookmark.model.BookmarkQuery
import com.unchunks.echomark.domain.bookmark.model.BookmarkSortOrder
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.bookmark.model.splitSearchTerms
import com.unchunks.echomark.domain.chat.ChatContextLimits
import com.unchunks.echomark.domain.chat.ChatMemoryPolicy
import com.unchunks.echomark.domain.chat.RagSupport
import com.unchunks.echomark.domain.model.ChatAttachmentContext
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.model.ChatScopeChoices
import com.unchunks.echomark.domain.model.Conversation
import com.unchunks.echomark.domain.model.ConversationBranch
import com.unchunks.echomark.domain.model.ConversationMatch
import com.unchunks.echomark.domain.model.ConversationPreview
import com.unchunks.echomark.domain.repository.AiTask
import com.unchunks.echomark.domain.repository.BookmarkRepository
import com.unchunks.echomark.domain.repository.ChatRepository
import com.unchunks.echomark.domain.repository.ChatStreamEvent
import com.unchunks.components.search.RankFusion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

class ChatRepositoryImpl @Inject constructor(
    private val conversationDao: ConversationDao,
    private val chatMessageDao: ChatMessageDao,
    private val vectorSearch: VectorSearchDataSource,
    private val embeddingProvider: EmbeddingProvider,
    private val llmProviderResolver: LlmProviderResolver,
    private val bookmarkRepository: BookmarkRepository,
    private val chatMemory: ChatMemoryCompressor,
    private val dispatcherProvider: DispatcherProvider
) : ChatRepository {

    override suspend fun createConversation(title: String?, aboutBookmarkId: Long?): Long =
        withContext(dispatcherProvider.io) {
            val now = System.currentTimeMillis()
            val fixedTitle = title?.trim()?.takeIf { it.isNotEmpty() }
            conversationDao.insert(
                ConversationEntity(
                    title = fixedTitle ?: DEFAULT_TITLE,
                    // 呼び出し側が決めたタイトルは、最初の発言からの自動タイトルで上書きしない
                    isTitleManuallySet = fixedTitle != null,
                    createdAt = now,
                    updatedAt = now,
                    aboutBookmarkId = aboutBookmarkId
                )
            )
        }

    override fun observeConversationPreviews(): Flow<List<ConversationPreview>> =
        conversationDao.observeAllWithLastMessage().map { list -> list.map { it.toDomain() } }

    override fun observeConversation(conversationId: Long): Flow<Conversation?> =
        conversationDao.observeById(conversationId).map { it?.toDomain() }

    override fun observeMessages(conversationId: Long): Flow<List<ChatMessage>> =
        chatMessageDao.observeMessages(conversationId).map { list -> list.map { it.toDomain() } }

    override fun sendMessageStream(
        conversationId: Long,
        userMessage: String,
        pinnedBookmarkId: Long?,
        isRetry: Boolean,
        attachment: ChatAttachmentContext?,
        scope: BookmarkQuery?
    ): Flow<ChatStreamEvent> = flow {
        // AI を使えなくても質問は保存する(再試行で使い回す)ため、失敗は準備の後で知らせる
        var providerFailure: Exception? = null
        val provider = try {
            llmProviderResolver.resolve(AiTask.CHAT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            providerFailure = e
            null
        }
        val prepared = try {
            val limits = provider?.chatContextLimits() ?: ChatContextLimits.COMPACT
            prepare(conversationId, userMessage, pinnedBookmarkId, isRetry, attachment, limits, scope)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(ChatStreamEvent.Failed(e))
            return@flow
        }
        emit(ChatStreamEvent.Started(prepared.referencedIds))
        if (provider == null) {
            providerFailure?.let { emit(ChatStreamEvent.Failed(it)) }
            return@flow
        }

        val answer = StringBuilder()
        var failure: Throwable? = null
        var contextTight = false
        try {
            provider.chatStream(userMessage, prepared.context, prepared.history, prepared.memory) { contextTight = it.isTight }
                // 生成側の失敗だけを受け取る(collect 側の例外は素通し)
                .catch { failure = it }
                .collect { chunk ->
                    answer.append(chunk)
                    emit(ChatStreamEvent.Delta(answer.toString()))
                }
        } catch (e: CancellationException) {
            // 停止ボタンなどによる中断。途中までの回答があれば「(停止)」付きで残す
            val partial = answer.toString().trimEnd()
            if (partial.isNotEmpty()) {
                withContext(NonCancellable) {
                    saveAssistantMessage(conversationId, partial + STOPPED_SUFFIX, prepared.referencedIds)
                }
            }
            throw e
        }

        // 失敗時は途中までの出力を破棄する(拒否などで不完全な回答を残さない)
        failure?.let {
            // 入力が文脈に入らなかったなら、やり直す前に古い発言を要約しておく
            if (contextTight) chatMemory.compressIfNeeded(conversationId, tight = true)
            emit(ChatStreamEvent.Failed(it))
            return@flow
        }
        val text = answer.toString().trim()
        if (text.isEmpty()) {
            emit(ChatStreamEvent.Failed(LlmException.Unexpected("応答が空でした")))
            return@flow
        }
        val messageId = saveAssistantMessage(conversationId, text, prepared.referencedIds)
        chatMemory.compressIfNeeded(conversationId, contextTight)
        emit(ChatStreamEvent.Completed(messageId))
    }.flowOn(dispatcherProvider.io)

    /** 送信前の準備: 履歴の取得、ユーザー発言の保存、文脈(関連ブックマーク)の検索。 */
    private suspend fun prepare(
        conversationId: Long,
        userMessage: String,
        pinnedBookmarkId: Long?,
        isRetry: Boolean,
        attachment: ChatAttachmentContext?,
        limits: ChatContextLimits,
        scope: BookmarkQuery?
    ): PreparedChat {
        // 0. 今回の発言を保存する前に、直近の会話履歴を取得しておく(今回分を含めないため)。
        //    再試行で、失敗した同じ発言が最後に残っているなら、それを履歴から外して使い回す(二重に保存しない)
        val recent = chatMessageDao.getRecentMessages(conversationId, limits.historyMessages + 1)
        val last = recent.lastOrNull()
        val reuseLastUserMessage = isRetry && last?.role == ChatRole.USER && last.content == userMessage
        // 要約に含めた発言は、要約の方で渡す
        val conversation = conversationDao.getById(conversationId)
        val coveredUntil = conversation?.summaryCoveredUntil
        val history = (if (reuseLastUserMessage) recent.dropLast(1) else recent)
            .takeLast(limits.historyMessages)
            .map { it.toDomain() }
            .let { ChatMemoryPolicy.notCovered(it, coveredUntil) }

        // 1. ユーザーの発言を保存
        val userTime = System.currentTimeMillis()
        if (!reuseLastUserMessage) {
            chatMessageDao.insert(
                ChatMessageEntity(
                    conversationId = conversationId,
                    role = ChatRole.USER,
                    content = userMessage,
                    createdAt = userTime
                )
            )
        }
        conversationDao.touch(conversationId, userTime)

        // 2〜3. 関連ブックマークを検索(削除済み等で取得できなかったものは除き、近い順を保つ)。
        //    質問の対象が固定されていれば、それだけを根拠にする(質問が対象の外の保存を聞いているときは、検索結果も足す)。
        //    「それについてもっと詳しく」のような続きの質問は、前の質問も合わせて探し、前の回答で使った保存も候補にする
        val pinned = pinnedBookmarkId?.let { bookmarkRepository.getBookmarksByIds(listOf(it)).firstOrNull() }
        val followUpQuery = RagSupport.searchQueryWithHistory(userMessage, history)
        val query = RagSupport.searchQueryFor(followUpQuery, pinned?.title)
        val keywords = RagSupport.keywordsOf(query)
        val candidates = if (pinned != null && !RagSupport.asksForRelated(userMessage)) {
            emptyList()
        } else {
            // 範囲を選んでいるときは、その範囲の保存の中から探す(対象が固定されていれば範囲は使わない)
            val range = scope.takeIf { pinned == null }
            val allowed = range?.let { inScope -> bookmarkRepository.observeBookmarks(inScope, BookmarkSortOrder.NEWEST).first().mapTo(HashSet()) { it.id } }
            val searched = try {
                findRelevantBookmarks(query, keywords, limits.maxSources, allowed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 文脈の検索が失敗しても回答はできる(最近保存したものを渡す)ため、チャット全体は失敗にしない
                Timber.w(e, "チャットの文脈の検索に失敗したため、最近保存したものを渡す")
                emptyList()
            }
            val previous = RagSupport.previousReferenceIds(userMessage, history).takeIf { it.isNotEmpty() }
                ?.let { ids -> bookmarkRepository.getBookmarksByIds(ids).sortedBy { ids.indexOf(it.id) } }
                .orEmpty()
                .filter { allowed == null || it.id in allowed }
            (previous + searched).distinctBy { it.id }
                // 質問に近いものもタグで絞れるものも無ければ(「最近保存した記事の要点は？」のような保存全体についての質問)、
                // 最近保存したものを渡す。関係があるかは AI に判断させる
                .ifEmpty { if (pinned == null) recentBookmarks(limits.maxSources, range ?: BookmarkQuery()) else emptyList() }
        }
        // 添付があるときは、資料を減らして添付の分の文脈を残す
        val usedBookmarks = RagSupport.pinFirst(
            pinned,
            candidates,
            idOf = { it.id },
            limit = if (attachment == null) limits.maxSources else limits.maxSourcesWithAttachment
        )
        val now = System.currentTimeMillis()
        val bookmarkContext = usedBookmarks.mapIndexed { i, b ->
            val title = RagSupport.contextTitle(b.title, b.createdAt, now)
            if (b.id == pinned?.id) {
                // 本文が長ければ、質問の語が出てくる部分を渡す(タイトルの語で本文を選ばないよう、質問の語だけで探す)
                val content = RagSupport.pinnedPassages(b.content, RagSupport.keywordsOf(followUpQuery), limits.pinnedChars)
                RagSupport.formatPinnedContextEntry(i + 1, title, b.summary, content, limits.pinnedChars)
            } else {
                // 上位のものには、要約に加えて本文の質問に関係する部分を添える(要約に無い細かいことにも答えられるように)
                val passage = if (i < limits.passageItems) RagSupport.bestPassage(b.content, keywords, limits.passageChars) else null
                RagSupport.formatContextEntry(
                    i + 1,
                    title,
                    RagSupport.contextBody(b.summary, b.content, passage),
                    maxChars = if (passage == null) limits.snippetChars else limits.snippetChars + limits.passageChars + PASSAGE_LABEL_CHARS
                )
            }
        }
        // 添付の番号は資料の続きにする(引用の番号を、保存した資料の順と揃える)
        val attachmentContext = attachment?.let {
            val body = RagSupport.bestPassage(it.text, keywords, limits.attachmentChars) ?: it.text
            RagSupport.formatContextEntry(usedBookmarks.size + 1, "添付「${it.name}」", body, limits.attachmentChars)
        }
        return PreparedChat(
            history.map(RagSupport::withoutCitations),
            // 文脈に入りきらないときは後ろから外れるため、ユーザーが選んで渡した添付を先に置く
            listOfNotNull(attachmentContext) + bookmarkContext,
            usedBookmarks.map { it.id },
            memory = conversation?.summary?.takeIf { coveredUntil != null }
        )
    }

    /**
     * 質問に意味の近いもの(埋め込み)・質問の語を含むもの(キーワード)・質問に名前が出てくるタグ(「「技術」の保存をまとめて」など)の
     * 付いたものを、順位を統合して(RRF)返す。意味の近さだけでは、固有名詞やタグで絞る質問の対象を取りこぼすため、3つを合わせる。
     */
    private suspend fun findRelevantBookmarks(
        query: String,
        keywords: List<String>,
        limit: Int,
        allowed: Set<Long>?
    ): List<Bookmark> {
        // 範囲の外のものが上位に来ても範囲の中のものが残るよう、多めに探してから絞る
        val widen = if (allowed == null) 1 else SCOPE_OVERFETCH_FACTOR
        fun List<Bookmark>.inScope() = if (allowed == null) this else filter { it.id in allowed }
        val similar = findSimilarBookmarks(query, limit * widen).inScope().take(limit)
        val keywordHits = findKeywordBookmarks(query, keywords, KEYWORD_SEARCH_LIMIT * widen).inScope().take(KEYWORD_SEARCH_LIMIT)
        val tagged = RagSupport.tagsMentionedIn(query, bookmarkRepository.observeAllTags().first())
            .flatMap { tag ->
                bookmarkRepository.observeBookmarks(BookmarkQuery(tagIds = setOf(tag.id)), BookmarkSortOrder.NEWEST)
                    .first()
                    .inScope()
                    .take(limit)
            }
        val byId = (similar + keywordHits + tagged).associateBy { it.id }
        return RankFusion.fuse(listOf(similar, keywordHits, tagged).map { list -> list.map { it.id } })
            .mapNotNull { byId[it] }
    }

    /**
     * 質問の文そのものと、質問から取り出した語([RagSupport.keywordsOf])ごとのキーワード検索の結果を、順位を統合して返す。
     * 多くの語を含むものほど上位になる。
     */
    private suspend fun findKeywordBookmarks(query: String, keywords: List<String>, limit: Int): List<Bookmark> {
        val lists = (listOf(query) + keywords).distinct().map { bookmarkRepository.searchByKeyword(it, limit) }
        val byId = lists.flatten().associateBy { it.id }
        return RankFusion.fuse(lists.map { list -> list.map { it.id } }).mapNotNull { byId[it] }.take(limit)
    }

    private suspend fun findSimilarBookmarks(userMessage: String, limit: Int): List<Bookmark> {
        // 2. 質問文をembedding化。埋め込みモデルが無い環境ではキーワード検索で代替する
        // 使えなければ意味の近さでは探さない(キーワードとタグでは探す)
        val queryVector = try {
            embeddingProvider.embedQuery(userMessage)
        } catch (e: CancellationException) {
            throw e
        } catch (e: EmbeddingUnavailableException) {
            Timber.i("埋め込みモデルが無いため、チャットの文脈はキーワード検索で探す")
            return emptyList()
        } catch (e: Exception) {
            // 埋め込みの推論の失敗(MediaPipe のエラー・縮めても入力を受け付けないなど)でも、チャット全体は失敗にしない
            Timber.w(e, "質問の埋め込みに失敗したため、チャットの文脈はキーワード検索で探す")
            return emptyList()
        } catch (e: LinkageError) {
            Timber.e(e, "埋め込みのライブラリを使えないため、チャットの文脈はキーワード検索で探す")
            return emptyList()
        }

        // 3. ObjectBoxで近傍検索し、類似度しきい値を下回る(無関係な)結果を除外
        // score は COSINE 距離。RagSupport 側で類似度へ変換して判定する
        // 現在のモデルの版のベクトルだけを比べる(再埋め込みの途中は旧版を除く)
        val profile = embeddingProvider.profile
        val hits = try {
            vectorSearch.nearestNeighbors(queryVector, limit, profile.modelVersion)
                .map { it.bookmarkId to it.score }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 近傍検索が失敗しても(ObjectBox の不具合・release の縮小で結果の型が消えたなど)、チャット全体は失敗にしない
            Timber.w(e, "ベクトル検索に失敗したため、チャットの文脈はキーワード検索で探す")
            return emptyList()
        }
        val relevantIds = RagSupport.selectRelevantIds(hits, profile.minRagSimilarity)
        val byId = bookmarkRepository.getBookmarksByIds(relevantIds).associateBy { it.id }
        return relevantIds.mapNotNull { byId[it] }
    }

    /** アシスタントの回答を保存し、会話の更新日時・自動タイトルを更新する。保存したメッセージ ID を返す。 */
    private suspend fun saveAssistantMessage(
        conversationId: Long,
        content: String,
        referencedIds: List<Long>
    ): Long {
        val answerTime = System.currentTimeMillis()
        val id = chatMessageDao.insert(
            ChatMessage(
                conversationId = conversationId,
                role = ChatRole.ASSISTANT,
                content = content,
                referencedBookmarkIds = referencedIds,
                createdAt = answerTime
            ).toEntity()
        )
        conversationDao.touch(conversationId, answerTime)

        // 6. 手動リネームされていなければ、最初のユーザー発言から自動タイトルを付ける
        chatMessageDao.getFirstByRole(conversationId, ChatRole.USER)?.let { first ->
            val title = RagSupport.titleFrom(first.content)
            if (title.isNotEmpty()) conversationDao.updateAutoTitle(conversationId, title, answerTime)
        }
        return id
    }

    override suspend fun deleteMessagesFrom(message: ChatMessage): Unit =
        withContext(dispatcherProvider.io) {
            chatMessageDao.deleteFrom(message.conversationId, message.createdAt, message.id)
            conversationDao.clearSummaryFrom(message.conversationId, message.createdAt)
        }

    override suspend fun branchConversation(message: ChatMessage): Long? =
        withContext(dispatcherProvider.io) {
            val source = conversationDao.getById(message.conversationId) ?: return@withContext null
            val upToMessage = chatMessageDao.getMessagesUpTo(message.conversationId, message.createdAt, message.id)
            val shared = if (message.role == ChatRole.USER) upToMessage.dropLast(1) else upToMessage
            val lastShared = shared.lastOrNull()?.createdAt ?: return@withContext null
            // 共通の発言より後まで含む要約は持ち越さない
            val keepSummary = source.summaryCoveredUntil?.let { it <= lastShared } == true
            val now = System.currentTimeMillis()
            conversationDao.insertBranch(
                ConversationEntity(
                    title = RagSupport.branchTitle(source.title),
                    isTitleManuallySet = true,
                    summary = source.summary.takeIf { keepSummary },
                    createdAt = now,
                    updatedAt = now,
                    aboutBookmarkId = source.aboutBookmarkId,
                    summaryCoveredUntil = source.summaryCoveredUntil.takeIf { keepSummary },
                    branchedFromId = source.id,
                    branchedAt = lastShared
                ),
                shared
            )
        }

    override fun observeBranches(conversationId: Long): Flow<List<ConversationBranch>> =
        conversationDao.observeBranches(conversationId).map { list ->
            list.mapNotNull { branch -> branch.branchedAt?.let { ConversationBranch(branch.id, branch.title, it) } }
        }

    override suspend fun saveNote(title: String, content: String): Long {
        val now = System.currentTimeMillis()
        return bookmarkRepository.saveBookmark(
            Bookmark(
                type = BookmarkType.TEXT,
                title = title,
                content = content,
                createdAt = now,
                lastAccessedAt = now
            )
        ).id
    }

    override suspend fun setPinned(conversationId: Long, pinned: Boolean): Unit =
        withContext(dispatcherProvider.io) { conversationDao.setPinned(conversationId, pinned) }

    override suspend fun searchConversations(query: String): List<ConversationMatch> =
        withContext(dispatcherProvider.io) {
            val terms = splitSearchTerms(query)
            if (terms.isEmpty()) return@withContext emptyList()
            // 語ごとに探して、すべての語に当たった会話を残す(発言は最初の語に当たったものを見せる)
            val matches = conversationDao.search(likePattern(terms.first()))
            val rest = terms.drop(1).map { term -> conversationDao.search(likePattern(term)).mapTo(HashSet()) { it.conversationId } }
            matches.filter { match -> rest.all { match.conversationId in it } }
                .map { ConversationMatch(it.conversationId, it.matchedMessage) }
        }

    private fun likePattern(term: String): String =
        "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

    override suspend fun renameConversation(conversationId: Long, title: String): Unit =
        withContext(dispatcherProvider.io) {
            val trimmed = title.trim()
            if (trimmed.isNotEmpty()) conversationDao.rename(conversationId, trimmed)
        }

    override suspend fun deleteConversation(conversationId: Long): Unit =
        withContext(dispatcherProvider.io) {
            conversationDao.deleteById(conversationId)
        }

    override suspend fun restoreConversation(conversation: Conversation, messages: List<ChatMessage>): Unit =
        withContext(dispatcherProvider.io) {
            conversationDao.insert(conversation.toEntity())
            if (messages.isNotEmpty()) chatMessageDao.insertAll(messages.map { it.toEntity() })
        }

    override suspend fun getBookmarks(ids: List<Long>): Map<Long, Bookmark> =
        if (ids.isEmpty()) emptyMap()
        else bookmarkRepository.getBookmarksByIds(ids).associateBy { it.id }

    override suspend fun getRecentBookmarks(limit: Int): List<Bookmark> = recentBookmarks(limit, BookmarkQuery())

    private suspend fun recentBookmarks(limit: Int, query: BookmarkQuery): List<Bookmark> =
        bookmarkRepository.observeBookmarks(query, BookmarkSortOrder.NEWEST)
            .first()
            .take(limit)

    override suspend fun latestConversationAbout(bookmarkId: Long): Long? =
        withContext(dispatcherProvider.io) { conversationDao.latestIdAbout(bookmarkId) }

    override fun observeScopeChoices(): Flow<ChatScopeChoices> = combine(
        bookmarkRepository.observeAllTags(),
        bookmarkRepository.observeBookmarks(BookmarkQuery(), BookmarkSortOrder.NEWEST)
    ) { tags, bookmarks ->
        val tagCounts = bookmarks.flatMap { it.tags }.groupingBy { it }.eachCount()
        val categoryCounts = bookmarks.mapNotNull { it.category }.filter { it.isNotBlank() }.groupingBy { it }.eachCount()
        ChatScopeChoices(
            tags = tags.sortedByDescending { tagCounts[it.name] ?: 0 },
            categories = categoryCounts.entries.sortedByDescending { it.value }.map { it.key }
        )
    }

    override fun observeScopeCount(scope: BookmarkQuery): Flow<Int> =
        bookmarkRepository.observeBookmarks(scope, BookmarkSortOrder.NEWEST).map { it.size }

    private class PreparedChat(
        val history: List<ChatMessage>,
        val context: List<String>,
        val referencedIds: List<Long>,
        /** 会話の古い発言の要約。無ければ null */
        val memory: String?
    )

    companion object {
        /** 「関連する部分: 「」」の見出しの分の文字数 */
        private const val PASSAGE_LABEL_CHARS = 12

        /** 範囲を選んでいるときに、絞る前に多めに探す倍率 */
        private const val SCOPE_OVERFETCH_FACTOR = 10

        /** キーワード検索で1つの語あたりに見る件数 */
        private const val KEYWORD_SEARCH_LIMIT = 10

        /** 生成を途中で止めた回答の末尾に付ける印。 */
        const val STOPPED_SUFFIX = "\n\n(停止)"

        /** 最初の発言が来るまでの会話タイトル。 */
        const val DEFAULT_TITLE = "新しいチャット"
    }
}
