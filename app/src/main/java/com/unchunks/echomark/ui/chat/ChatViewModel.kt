package com.unchunks.echomark.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unchunks.components.core.DispatcherProvider
import com.unchunks.components.llm.LlmException
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkQuery
import com.unchunks.echomark.domain.chat.RagSupport
import com.unchunks.echomark.domain.model.ChatAttachmentContext
import com.unchunks.echomark.domain.model.ChatAttachmentException
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.model.ChatScopeChoices
import com.unchunks.echomark.domain.model.ChatTemplate
import com.unchunks.components.llm.ChatRole
import com.unchunks.echomark.domain.model.Conversation
import com.unchunks.echomark.domain.model.ConversationBranch
import com.unchunks.echomark.domain.provider.ModelNotAvailableException
import com.unchunks.echomark.domain.provider.toLlmUserMessage
import com.unchunks.echomark.domain.repository.AiSetupRepository
import com.unchunks.echomark.domain.repository.AiSetupState
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import com.unchunks.echomark.domain.repository.ChatAttachmentSource
import com.unchunks.echomark.domain.repository.ChatSpeechOutput
import com.unchunks.echomark.domain.repository.ChatVoiceInput
import com.unchunks.echomark.domain.repository.VoiceInputResult
import com.unchunks.echomark.domain.repository.ChatModelChoices
import com.unchunks.echomark.domain.repository.ChatModelOption
import com.unchunks.echomark.domain.repository.ChatModelRepository
import com.unchunks.echomark.domain.repository.ChatRepository
import com.unchunks.echomark.domain.repository.ChatStreamEvent
import com.unchunks.echomark.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val repository: ChatRepository,
    aiSetupRepository: AiSetupRepository,
    private val savedStateHandle: SavedStateHandle,
    private val attachmentSource: ChatAttachmentSource,
    private val chatModels: ChatModelRepository,
    private val dispatchers: DispatcherProvider,
    private val appSettings: AppSettingsRepository,
    private val voiceInput: ChatVoiceInput,
    private val speechOutput: ChatSpeechOutput
) : ViewModel() {

    // 既存会話は "chat/{conversationId}" の引数で渡る。"chat/new" では未設定(null)で、
    // 最初の送信時に作成した ID を SavedStateHandle に保存する(プロセス破棄後も同じ会話に戻れる)
    private val conversationIdFlow: StateFlow<Long?> =
        savedStateHandle.getStateFlow<Long?>(ARG_CONVERSATION_ID, null)

    /** 表示中の会話の ID(新しいチャットで、まだ送信していなければ null)。2 画面表示で一覧の選択表示に使う */
    val conversationId: StateFlow<Long?> = conversationIdFlow

    /** 「このブックマークについて質問」で新しく始めるときの対象("chat/new/about/{id}" の引数)。 */
    private val navAboutBookmarkId: Long? = savedStateHandle.get<Long>(Routes.ARG_ABOUT_BOOKMARK_ID)

    private val isSendingFlow = MutableStateFlow(false)
    private val streamingTextFlow = MutableStateFlow<String?>(null)
    private val pendingReferenceCountFlow = MutableStateFlow<Int?>(null)
    private val errorFlow = MutableStateFlow<ChatError?>(null)

    /** 次の質問に添付するファイルの内容(添付していなければ null)。送信すると外れる。 */
    private val attachmentFlow = MutableStateFlow<ChatAttachmentContext?>(null)
    private val isAttachingFlow = MutableStateFlow(false)
    private val attachmentErrorFlow = MutableStateFlow<String?>(null)

    /** 直近の送信で使った添付。失敗した質問を送り直すときに同じ添付を使う。 */
    private var lastAttachment: ChatAttachmentContext? = null

    /** 生成中の送信処理。停止ボタンでキャンセルする。 */
    private var sendJob: Job? = null

    /** 今の送信を停止ボタンで止めたか。 */
    private var stopRequested = false

    private val voiceStateFlow = MutableStateFlow(ChatVoiceState.IDLE)
    private val voiceErrorFlow = MutableStateFlow<String?>(null)

    /** 録音の処理。キャンセルすると録音を捨てる。 */
    private var voiceJob: Job? = null

    /** 録音を今の分で終えて文字に起こす合図。 */
    private var finishVoice: CompletableDeferred<Unit>? = null

    /** 今の送信を声で質問したか。声で聞いた質問の回答は読み上げる。 */
    private var speakNextAnswer = false

    private val answerReviewsFlow = MutableStateFlow<Map<Long, AnswerReview>>(emptyMap())

    /** 確かめ終わった回答。回答を確かめ直さない(新しい回答が保存されるたびに、会話全体を確かめ直すことになるため) */
    private val reviewCache = ConcurrentHashMap<ReviewKey, AnswerReview>()
    private var reviewJob: Job? = null

    private val conversationFlow: Flow<Conversation?> = conversationIdFlow.flatMapLatest { id ->
        if (id == null) flowOf(null) else repository.observeConversation(id)
    }

    /**
     * 質問の対象のブックマーク ID。新しく始めるときはナビゲーション引数、
     * 既存の会話(会話一覧から開き直したとき)は会話に保存した ID を使う。
     */
    private val aboutBookmarkIdFlow: Flow<Long?> =
        if (navAboutBookmarkId != null) flowOf(navAboutBookmarkId)
        else conversationFlow.map { it?.aboutBookmarkId }.distinctUntilChanged()

    /** 対象のブックマーク(通常の会話、または対象が削除済みなら null)。 */
    private val aboutBookmarkFlow: Flow<Bookmark?> = aboutBookmarkIdFlow.flatMapLatest { id ->
        if (id == null) flowOf(null) else flow { emit(repository.getBookmarks(listOf(id))[id]) }
    }

    /** 対象のブックマークがあればそれ向け、無ければ(削除済みを含む)保存済みのタグから作る質問の例。 */
    private val suggestionsFlow: Flow<List<String>> = aboutBookmarkFlow
        .map { it != null }
        .distinctUntilChanged()
        .flatMapLatest { isAbout -> if (isAbout) flowOf(ChatSuggestions.forBookmark) else librarySuggestions() }

    private fun librarySuggestions(): Flow<List<String>> = flow {
        emit(ChatSuggestions.forLibrary(emptyList()))
        val recent = try {
            repository.getRecentBookmarks(SUGGESTION_SOURCE_LIMIT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "質問の例の元になるブックマークを取得できなかった")
            emptyList()
        }
        emit(ChatSuggestions.forLibrary(recent))
    }

    /** メッセージ本文と引用カード用のブックマーク。読み込み中は null。 */
    private val messagesWithReferences: Flow<Pair<List<ChatMessage>, Map<Long, Bookmark>>?> =
        conversationIdFlow.flatMapLatest { id ->
            if (id == null) flowOf(emptyList<ChatMessage>())
            else repository.observeMessages(id)
        }.map<List<ChatMessage>, Pair<List<ChatMessage>, Map<Long, Bookmark>>?> { messages ->
            val ids = messages.flatMap { it.referencedBookmarkIds }.distinct()
            messages to repository.getBookmarks(ids)
        }.onEach { loaded -> loaded?.let { (messages, bookmarks) -> reviewAnswers(messages, bookmarks) } }
            .onStart { if (conversationIdFlow.value != null) emit(null) }

    private val sendingState = combine(
        isSendingFlow,
        streamingTextFlow,
        pendingReferenceCountFlow,
        errorFlow
    ) { sending, streaming, pendingCount, error -> SendingState(sending, streaming, pendingCount, error) }
        .combine(combine(voiceStateFlow, voiceErrorFlow, speechOutput.speakingMessageId, ::VoiceUiState)) { sending, voice -> sending to voice }

    private val contextState = combine(
        conversationFlow,
        aboutBookmarkFlow,
        suggestionsFlow,
        aiSetupRepository.setupState,
        chatModels.choices
    ) { conversation, about, suggestions, setup, models -> ContextState(conversation, about, suggestions, setup, models) }

    private val attachmentState = combine(attachmentFlow, isAttachingFlow, attachmentErrorFlow) { attachment, attaching, error ->
        AttachmentState(attachment?.name, attaching, error)
    }

    /** 参照する範囲。この画面を開いている間だけ使い、会話には保存しない。 */
    private val scopeFlow = MutableStateFlow(ChatScopeChoices.ALL)
    private val scopeLabelFlow = MutableStateFlow<String?>(null)

    /** 今の参照する範囲(範囲のシートに出す)。 */
    val scope: StateFlow<BookmarkQuery> = scopeFlow

    /** 範囲の選択肢。保存全体を読むため、シートを開いている間だけ集める。 */
    val scopeChoices: StateFlow<ChatScopeChoices> = repository.observeScopeChoices()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatScopeChoices())

    /** 今の範囲に入る保存の数。シートを開いている間だけ集める。 */
    val scopeCount: StateFlow<Int?> = scopeFlow.flatMapLatest { query ->
        repository.observeScopeCount(query).map<Int, Int?> { it }.onStart { emit(null) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 同じブックマークについて前に質問した会話。 */
    private val previousAboutConversationFlow: Flow<Long?> = flow {
        emit(null)
        navAboutBookmarkId?.let { emit(repository.latestConversationAbout(it)) }
    }

    /** この会話を分けた元の会話。 */
    private val branchParentFlow: Flow<ConversationBranch?> = conversationFlow.flatMapLatest { conversation ->
        val parentId = conversation?.branchedFromId
        if (parentId == null) {
            flowOf(null)
        } else {
            repository.observeConversation(parentId).map { parent ->
                parent?.let { ConversationBranch(it.id, it.title, conversation.branchedAt ?: 0L) }
            }
        }
    }

    /** この会話から分けた会話。 */
    private val branchesFlow: Flow<List<ConversationBranch>> = conversationIdFlow.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else repository.observeBranches(id)
    }

    private val branchState = combine(branchParentFlow, branchesFlow) { parent, branches -> BranchState(parent, branches) }

    private val composerState = combine(
        attachmentState,
        scopeLabelFlow,
        previousAboutConversationFlow,
        appSettings.chatTemplates,
        branchState
    ) { attachment, scopeLabel, previousAbout, templates, branch ->
        ComposerState(attachment, scopeLabel, previousAbout, templates, branch)
    }

    val uiState: StateFlow<ChatUiState> = combine(
        messagesWithReferences,
        sendingState,
        contextState,
        composerState,
        answerReviewsFlow
    ) { messagesAndRefs, sending, context, composer, reviews ->
        val attachment = composer.attachment
        val (sendingValues, voice) = sending
        val messages = messagesAndRefs?.first.orEmpty()
        val bookmarks = messagesAndRefs?.second.orEmpty()
        ChatUiState(
            title = context.conversation?.title,
            hasConversation = context.conversation != null,
            isLoading = messagesAndRefs == null,
            messages = messages,
            referencedBookmarks = bookmarks,
            answerReviews = reviews,
            followUps = ChatSuggestions.forAnswer(
                messages,
                messages.lastOrNull()?.let { reviews[it.id] },
                bookmarks,
                isAboutBookmark = context.aboutBookmark != null
            ),
            scopeLabel = composer.scopeLabel,
            previousAboutConversationId = composer.previousAboutConversationId,
            userTemplates = composer.userTemplates,
            branchParent = composer.branch.parent,
            branches = composer.branch.branches,
            aboutBookmark = context.aboutBookmark,
            suggestions = context.suggestions,
            isSending = sendingValues.isSending,
            streamingText = sendingValues.streamingText,
            pendingReferenceCount = sendingValues.pendingReferenceCount,
            error = sendingValues.error,
            voiceState = voice.state,
            voiceError = voice.error,
            speakingMessageId = voice.speakingMessageId,
            aiSetup = context.aiSetup,
            modelChoices = context.modelChoices,
            attachmentName = attachment.name,
            isAttaching = attachment.isAttaching,
            attachmentError = attachment.error
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ChatUiState(isLoading = conversationIdFlow.value != null)
    )

    fun sendMessage(text: String) {
        send(text.trim(), isRetry = false)
    }

    /** 失敗した最後の質問を送り直す(保存済みのユーザー発言は使い回す)。 */
    fun retry() {
        val failed = errorFlow.value?.failedMessage ?: return
        send(failed, isRetry = true)
    }

    /** 音声で質問を始める。マイクの権限は画面で確かめてから呼ぶ。話し終わると文字に起こして送り、回答を読み上げる。 */
    fun startVoiceInput() {
        if (isSendingFlow.value || voiceJob?.isActive == true) return
        speechOutput.stop()
        voiceErrorFlow.value = null
        voiceStateFlow.value = ChatVoiceState.LISTENING
        val finish = CompletableDeferred<Unit>().also { finishVoice = it }
        voiceJob = viewModelScope.launch {
            try {
                val result = voiceInput.listen(finish) { voiceStateFlow.value = ChatVoiceState.TRANSCRIBING }
                when (result) {
                    is VoiceInputResult.Text -> {
                        speakNextAnswer = true
                        sendMessage(result.text)
                    }
                    VoiceInputResult.NoSpeech -> voiceErrorFlow.value = "聞き取れませんでした"
                    VoiceInputResult.MicrophoneUnavailable -> voiceErrorFlow.value = "マイクを使えませんでした"
                    VoiceInputResult.TranscriptionUnavailable ->
                        voiceErrorFlow.value = "この端末では音声を文字にできませんでした"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "音声入力に失敗")
                voiceErrorFlow.value = "音声を文字にできませんでした"
            } finally {
                voiceStateFlow.value = ChatVoiceState.IDLE
            }
        }
    }

    /** 話し終わったことを伝える。録音を今の分で終えて文字に起こす。 */
    fun finishVoiceInput() {
        finishVoice?.complete(Unit)
    }

    /** 録音を捨てて音声入力をやめる。 */
    fun cancelVoiceInput() {
        voiceJob?.cancel()
    }

    /** マイクの許可が得られなかった。 */
    fun onMicrophoneDenied() {
        voiceErrorFlow.value = "マイクの許可が無いため、音声で質問できません。端末の設定で許可できます"
    }

    /** [message] の読み上げを始める。読み上げ中なら止める。 */
    fun toggleSpeaking(message: ChatMessage) {
        if (speechOutput.speakingMessageId.value == message.id) speechOutput.stop() else speak(message.id, message.content)
    }

    private fun speak(messageId: Long, answer: String) {
        voiceErrorFlow.value = null
        viewModelScope.launch {
            if (!speechOutput.speak(messageId, AnswerSpeech.partsOf(answer))) {
                voiceErrorFlow.value = "読み上げを使えませんでした。端末の読み上げの設定を確かめてください"
            }
        }
    }

    /** 画面が見えなくなった。マイクと読み上げを止める(見えていない間に録音し続けない)。 */
    fun onScreenHidden() {
        cancelVoiceInput()
        speechOutput.stop()
    }

    override fun onCleared() {
        speechOutput.stop()
    }

    /** 回答を資料と照らし合わせる。本文の長い資料では時間がかかるため、画面のスレッドの外で行う。 */
    private fun reviewAnswers(messages: List<ChatMessage>, bookmarks: Map<Long, Bookmark>) {
        reviewJob?.cancel()
        reviewJob = viewModelScope.launch(dispatchers.default) {
            answerReviewsFlow.value = messages.filter { it.role == ChatRole.ASSISTANT }.associate { message ->
                val ids = message.referencedBookmarkIds
                val key = ReviewKey(message.id, ids.filter { it in bookmarks })
                message.id to reviewCache.getOrPut(key) { AnswerReviewer.review(message.content, ids, bookmarks) }
            }
        }
    }

    /** 参照する範囲を [query] にする。 */
    fun setScope(query: BookmarkQuery) {
        scopeFlow.value = query
        scopeLabelFlow.value = scopeChoices.value.describe(query)
    }

    /** 同じブックマークについて前に質問した会話を開く。 */
    fun resumeAboutConversation() {
        val id = uiState.value.previousAboutConversationId ?: return
        if (conversationIdFlow.value == null) savedStateHandle[ARG_CONVERSATION_ID] = id
    }

    /** この画面のまま会話 [id] に切り替える(分けた先・元の会話を開く)。生成中は切り替えない。 */
    fun openConversation(id: Long) {
        if (isSendingFlow.value) return
        errorFlow.value = null
        savedStateHandle[ARG_CONVERSATION_ID] = id
    }

    /**
     * [message] の時点から会話を分けて、分けた会話を開く。元の会話はそのまま残る。
     * 質問から分けたときは、その質問を直して送れるよう [onQuestionRestored] に渡す。
     */
    fun branchFrom(message: ChatMessage, onQuestionRestored: (String) -> Unit) {
        if (isSendingFlow.value) return
        viewModelScope.launch {
            val id = repository.branchConversation(message) ?: return@launch
            openConversation(id)
            if (message.role == ChatRole.USER) onQuestionRestored(message.content)
        }
    }

    /** 回答をメモとして保存する。保存したブックマークの ID を [onSaved] に渡す。 */
    fun saveAsNote(message: ChatMessage, onSaved: (Long) -> Unit) {
        val state = uiState.value
        val question = state.messages.takeWhile { it.id != message.id }.lastOrNull { it.role == ChatRole.USER }?.content
        val sources = state.answerReviews[message.id]?.sources.orEmpty().mapNotNull { state.referencedBookmarks[it.bookmarkId] }
        val note = ChatNotes.from(question, message, sources)
        viewModelScope.launch { onSaved(repository.saveNote(note.title, note.content)) }
    }

    /** 入力中の [text] を自分のテンプレートに保存する。 */
    fun saveTemplate(text: String) {
        viewModelScope.launch {
            appSettings.updateChatTemplates { ChatTemplates.add(it, text, UUID.randomUUID().toString()) }
        }
    }

    fun deleteTemplate(template: ChatTemplate) {
        viewModelScope.launch {
            appSettings.updateChatTemplates { list -> list.filterNot { it.id == template.id } }
        }
    }

    /** 次の質問から [option] の AI を使う。 */
    fun selectModel(option: ChatModelOption) {
        viewModelScope.launch { chatModels.select(option) }
    }

    /**
     * ファイルを読み込んで、次の質問に添付する。読めなければ理由を [ChatUiState.attachmentError] に出す。
     * 添付は回答の根拠にだけ使い、会話には保存しない。
     */
    fun attachFile(uri: String) {
        if (isAttachingFlow.value) return
        isAttachingFlow.value = true
        attachmentErrorFlow.value = null
        viewModelScope.launch {
            try {
                attachmentFlow.value = attachmentSource.load(uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ChatAttachmentException) {
                attachmentErrorFlow.value = e.message
            } catch (e: Exception) {
                Timber.w(e, "添付を読み込めなかった")
                attachmentErrorFlow.value = "添付を読み込めませんでした"
            } finally {
                isAttachingFlow.value = false
            }
        }
    }

    fun removeAttachment() {
        attachmentFlow.value = null
        attachmentErrorFlow.value = null
    }

    private fun send(text: String, isRetry: Boolean) {
        if (text.isBlank() || isSendingFlow.value) return
        // 添付は、この質問に使ったら外す。失敗して送り直すときは、同じ添付を使う
        val attachment = if (isRetry) lastAttachment else attachmentFlow.value
        lastAttachment = attachment
        attachmentFlow.value = null
        isSendingFlow.value = true
        errorFlow.value = null
        voiceErrorFlow.value = null
        pendingReferenceCountFlow.value = null
        stopRequested = false
        // 声で聞いた質問だけを読み上げる。送り直しや手入力の質問は読み上げない
        val speakAnswer = speakNextAnswer
        speakNextAnswer = false
        sendJob = viewModelScope.launch {
            try {
                // 会話は最初の送信時に遅延作成する(空の会話を増やさない)。
                // ブックマークについての質問なら、そのブックマーク名を既定のタイトルにし、対象を会話に保存する。
                // 対象が削除済みなら通常の会話として扱う
                val about = resolveAboutBookmark()
                val id = conversationIdFlow.value
                    ?: repository.createConversation(
                        title = about?.let { RagSupport.aboutBookmarkTitle(it.title) },
                        aboutBookmarkId = about?.id
                    ).also { savedStateHandle[ARG_CONVERSATION_ID] = it }
                streamingTextFlow.value = ""
                repository.sendMessageStream(id, text, about?.id, isRetry, attachment, scopeFlow.value.takeIf { it != ChatScopeChoices.ALL })
                    .collect { event ->
                    when (event) {
                        is ChatStreamEvent.Started ->
                            pendingReferenceCountFlow.value = event.referencedBookmarkIds.size
                        is ChatStreamEvent.Delta -> streamingTextFlow.value = event.textSoFar
                        is ChatStreamEvent.Completed -> {
                            val answer = streamingTextFlow.value
                            streamingTextFlow.value = null
                            if (speakAnswer && !answer.isNullOrBlank()) speak(event.messageId, answer)
                        }
                        is ChatStreamEvent.Failed -> {
                            Timber.w(event.error, "sendMessageStream failed")
                            errorFlow.value = event.error.toChatError(text)
                        }
                    }
                }
            } catch (e: CancellationException) {
                // 最初の文字が届く前に止めると回答は残らないため、同じ質問を送り直せるようにする
                if (stopRequested && streamingTextFlow.value.isNullOrBlank()) {
                    errorFlow.value = ChatError(STOPPED_BEFORE_ANSWER_MESSAGE, text)
                }
                throw e
            } catch (e: Exception) {
                Timber.e(e, "sendMessage failed")
                errorFlow.value = e.toChatError(text)
            } finally {
                streamingTextFlow.value = null
                pendingReferenceCountFlow.value = null
                isSendingFlow.value = false
            }
        }
    }

    /** 送信時点の質問の対象(通常の会話・削除済みなら null)。 */
    private suspend fun resolveAboutBookmark(): Bookmark? {
        val id = navAboutBookmarkId
            ?: conversationIdFlow.value?.let { repository.observeConversation(it).first()?.aboutBookmarkId }
            ?: return null
        return repository.getBookmarks(listOf(id))[id]
    }

    /** 回答の作り直し・質問の編集で、古いメッセージを消している処理(二重に押されたときに重ねない)。 */
    private var rewriteJob: Job? = null

    /** 最後の回答を消して、同じ質問への回答を作り直す。最後のメッセージが回答でなければ何もしない。 */
    fun regenerate() {
        val id = conversationIdFlow.value ?: return
        if (isSendingFlow.value || rewriteJob?.isActive == true) return
        rewriteJob = viewModelScope.launch {
            val messages = repository.observeMessages(id).first()
            val last = messages.lastOrNull()?.takeIf { it.role == ChatRole.ASSISTANT } ?: return@launch
            val question = messages.lastOrNull { it.role == ChatRole.USER } ?: return@launch
            repository.deleteMessagesFrom(last)
            // 最後に残った同じ質問を使い回して送る(質問を二重に保存しない)
            send(question.content, isRetry = true)
        }
    }

    /** 最後の質問を [text] に直して送り直す。その質問から先の会話(回答)は消す。 */
    fun editLastMessage(text: String) {
        val trimmed = text.trim()
        val id = conversationIdFlow.value ?: return
        if (trimmed.isBlank() || isSendingFlow.value || rewriteJob?.isActive == true) return
        rewriteJob = viewModelScope.launch {
            val question = repository.observeMessages(id).first().lastOrNull { it.role == ChatRole.USER } ?: return@launch
            repository.deleteMessagesFrom(question)
            send(trimmed, isRetry = false)
        }
    }

    /** 生成を止める。途中までの回答は「(停止)」付きで保存される。 */
    fun stopGenerating() {
        stopRequested = true
        sendJob?.cancel()
    }

    fun rename(title: String) {
        val id = conversationIdFlow.value ?: return
        if (title.isBlank()) return
        viewModelScope.launch { repository.renameConversation(id, title) }
    }

    /** 会話を削除する。完了後に [onDeleted] を呼ぶ(画面を閉じる)。 */
    fun deleteConversation(onDeleted: () -> Unit) {
        val id = conversationIdFlow.value ?: return
        sendJob?.cancel()
        viewModelScope.launch {
            repository.deleteConversation(id)
            onDeleted()
        }
    }

    private fun Throwable.toChatError(failedMessage: String) = ChatError(
        message = toLlmUserMessage(SEND_FAILED_PREFIX),
        failedMessage = failedMessage,
        needsAiSettings = needsAiSettings()
    )

    /** 回答の結果を使い回せるか見分ける。資料が削除されたら確かめ直す。 */
    private data class ReviewKey(val messageId: Long, val availableIds: List<Long>)

    private class VoiceUiState(val state: ChatVoiceState, val error: String?, val speakingMessageId: Long?)

    private class SendingState(
        val isSending: Boolean,
        val streamingText: String?,
        val pendingReferenceCount: Int?,
        val error: ChatError?
    )

    private class AttachmentState(
        val name: String?,
        val isAttaching: Boolean,
        val error: String?
    )

    private class BranchState(val parent: ConversationBranch?, val branches: List<ConversationBranch>)

    private class ComposerState(
        val attachment: AttachmentState,
        val scopeLabel: String?,
        val previousAboutConversationId: Long?,
        val userTemplates: List<ChatTemplate>,
        val branch: BranchState
    )

    private class ContextState(
        val conversation: Conversation?,
        val aboutBookmark: Bookmark?,
        val suggestions: List<String>,
        val aiSetup: AiSetupState,
        val modelChoices: ChatModelChoices
    )

    companion object {
        /** ナビゲーション引数(と SavedStateHandle)のキー。 */
        const val ARG_CONVERSATION_ID = "conversationId"

        private const val SEND_FAILED_PREFIX = "送信に失敗しました"

        private const val STOPPED_BEFORE_ANSWER_MESSAGE = "回答が始まる前に止めました"

        /** 質問の例を作るときに見る、最近のブックマークの件数。 */
        private const val SUGGESTION_SOURCE_LIMIT = 50
    }
}

/** AI 設定を見直せば直る失敗か(キー未設定・無効、モデル未取り込み、モデル ID の誤りなど)。 */
internal fun Throwable.needsAiSettings(): Boolean = when (this) {
    is ModelNotAvailableException,
    is LlmException.ApiKeyMissing,
    is LlmException.InvalidApiKey,
    is LlmException.BadRequest,
    // 端末内 AI がこの端末で起動できない。クラウド API に切り替えれば使える
    is LlmException.EngineUnavailable -> true
    else -> false
}
