package com.unchunks.echomark.ui.chat

import androidx.lifecycle.SavedStateHandle
import com.unchunks.components.llm.ApiProvider
import com.unchunks.components.llm.ChatRole
import com.unchunks.components.llm.LlmException
import com.unchunks.components.testing.MainDispatcherRule
import com.unchunks.echomark.domain.model.ChatAttachmentContext
import com.unchunks.echomark.domain.model.ChatAttachmentException
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.model.Conversation
import com.unchunks.echomark.domain.repository.ChatAttachmentSource
import com.unchunks.echomark.domain.provider.MODEL_NOT_AVAILABLE_USER_MESSAGE
import com.unchunks.echomark.domain.provider.ModelNotAvailableException
import com.unchunks.echomark.domain.repository.AiSetupState
import com.unchunks.echomark.domain.repository.ChatModelChoices
import com.unchunks.echomark.domain.repository.ChatModelOption
import com.unchunks.echomark.domain.repository.ChatStreamEvent
import com.unchunks.echomark.testing.FakeAiSetupRepository
import com.unchunks.echomark.testing.FakeChatModelRepository
import com.unchunks.echomark.testing.FakeChatRepository
import com.unchunks.echomark.testing.testBookmark
import com.unchunks.echomark.ui.navigation.Routes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val repository = FakeChatRepository()
    private val aiSetup = FakeAiSetupRepository()
    private val chatModels = FakeChatModelRepository()
    private val attachmentSource = object : ChatAttachmentSource {
        override suspend fun load(uri: String): ChatAttachmentContext = ChatAttachmentContext("資料.txt", "添付の本文")
    }

    private fun createViewModel(
        savedState: SavedStateHandle = SavedStateHandle(),
        repo: FakeChatRepository = repository
    ) = ChatViewModel(repo, aiSetup, savedState, attachmentSource, chatModels)

    @Test
    fun モデル未取得のときは専用メッセージとAI設定への導線を出しisSendingが戻る() = runTest {
        repository.sendFailure = ModelNotAvailableException()
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }

        viewModel.sendMessage("こんにちは")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(MODEL_NOT_AVAILABLE_USER_MESSAGE, state.error?.message)
        val error = state.error!!
        assertTrue(error.needsAiSettings)
        assertEquals("こんにちは", error.failedMessage)
        assertFalse(state.isSending)
    }

    @Test
    fun 一般的な失敗ではエラーメッセージを出しisSendingが戻る() = runTest {
        repository.sendFailure = IllegalStateException("boom")
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }

        viewModel.sendMessage("こんにちは")
        advanceUntilIdle()

        val error = viewModel.uiState.value.error!!
        assertTrue(error.message.startsWith("送信に失敗しました"))
        assertTrue(error.message.contains("boom"))
        assertFalse(error.needsAiSettings)
        assertFalse(viewModel.uiState.value.isSending)
    }

    @Test
    fun 通信エラーはAI設定への導線を出さない() = runTest {
        repository.sendFailure = LlmException.Network()
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }

        viewModel.sendMessage("hi")
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.error!!.needsAiSettings)
    }

    @Test
    fun 失敗後の再送信ではエラーが消えて成功できる() = runTest {
        repository.sendFailure = ModelNotAvailableException()
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        viewModel.sendMessage("1回目")
        advanceUntilIdle()

        repository.sendFailure = null
        viewModel.sendMessage("2回目")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.error)
        assertFalse(state.isSending)
        assertEquals(listOf("1回目", "2回目"), repository.sent.map { it.second })
    }

    @Test
    fun 再試行は最後の質問を再試行として送り直す() = runTest {
        repository.sendFailure = LlmException.Network()
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        viewModel.sendMessage("質問")
        advanceUntilIdle()

        repository.sendFailure = null
        viewModel.retry()
        advanceUntilIdle()

        assertEquals(
            listOf(
                FakeChatRepository.SendCall(42L, "質問", null, isRetry = false),
                FakeChatRepository.SendCall(42L, "質問", null, isRetry = true)
            ),
            repository.sendCalls
        )
        assertNull(viewModel.uiState.value.error)
        // 会話は作り直さない
        assertEquals(1, repository.createCalls)
    }

    @Test
    fun エラーが無ければ再試行しても何も送らない() = runTest {
        val viewModel = createViewModel()

        viewModel.retry()
        advanceUntilIdle()

        assertTrue(repository.sent.isEmpty())
    }

    @Test
    fun 送信中は二重送信されない() = runTest {
        val gate = CompletableDeferred<Unit>()
        val slowRepository = object : FakeChatRepository() {
            override fun sendMessageStream(
                conversationId: Long,
                userMessage: String,
                pinnedBookmarkId: Long?,
                isRetry: Boolean,
                attachment: ChatAttachmentContext?
            ): Flow<ChatStreamEvent> = flow {
                sent += conversationId to userMessage
                gate.await()
                emit(ChatStreamEvent.Completed(1L))
            }
        }
        val viewModel = createViewModel(repo = slowRepository)
        backgroundScope.launch { viewModel.uiState.collect {} }

        viewModel.sendMessage("a")
        runCurrent()
        assertTrue(viewModel.uiState.value.isSending)
        viewModel.sendMessage("b")
        runCurrent()

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("a"), slowRepository.sent.map { it.second })
        assertFalse(viewModel.uiState.value.isSending)
    }

    @Test
    fun 会話は初回送信時に1度だけ作られIDが保存される() = runTest {
        val savedState = SavedStateHandle()
        val viewModel = createViewModel(savedState)
        backgroundScope.launch { viewModel.uiState.collect {} }

        viewModel.sendMessage("1")
        advanceUntilIdle()
        viewModel.sendMessage("2")
        advanceUntilIdle()

        assertEquals(1, repository.createCalls)
        assertEquals(listOf<String?>(null), repository.createdTitles)
        assertEquals(42L, savedState.get<Long>(ChatViewModel.ARG_CONVERSATION_ID))
        assertEquals(listOf(42L, 42L), repository.sent.map { it.first })
    }

    @Test
    fun 既存会話ならcreateConversationを呼ばずタイトルを出す() = runTest {
        repository.conversation.value = Conversation(id = 7L, title = "Kotlin の話", createdAt = 0L, updatedAt = 0L)
        val viewModel = createViewModel(SavedStateHandle(mapOf(ChatViewModel.ARG_CONVERSATION_ID to 7L)))
        backgroundScope.launch { viewModel.uiState.collect {} }

        viewModel.sendMessage("hi")
        advanceUntilIdle()

        assertEquals(0, repository.createCalls)
        assertEquals(listOf(7L to "hi"), repository.sent)
        assertEquals("Kotlin の話", viewModel.uiState.value.title)
        assertTrue(viewModel.uiState.value.hasConversation)
    }

    @Test
    fun 空白のメッセージは送信されない() = runTest {
        val viewModel = createViewModel()

        viewModel.sendMessage("  ")
        advanceUntilIdle()

        assertTrue(repository.sent.isEmpty())
        assertEquals(0, repository.createCalls)
    }

    @Test
    fun 生成中のテキストと参照件数が出て完了で消える() = runTest {
        val gate = CompletableDeferred<Unit>()
        val streamingRepository = object : FakeChatRepository() {
            override fun sendMessageStream(
                conversationId: Long,
                userMessage: String,
                pinnedBookmarkId: Long?,
                isRetry: Boolean,
                attachment: ChatAttachmentContext?
            ): Flow<ChatStreamEvent> = flow {
                emit(ChatStreamEvent.Started(listOf(1L, 2L)))
                emit(ChatStreamEvent.Delta("こんに"))
                emit(ChatStreamEvent.Delta("こんにちは"))
                gate.await()
                emit(ChatStreamEvent.Completed(10L))
            }
        }
        val viewModel = createViewModel(repo = streamingRepository)
        backgroundScope.launch { viewModel.uiState.collect {} }

        viewModel.sendMessage("hi")
        runCurrent()
        assertEquals("こんにちは", viewModel.uiState.value.streamingText)
        assertEquals(2, viewModel.uiState.value.pendingReferenceCount)
        assertTrue(viewModel.uiState.value.isSending)

        gate.complete(Unit)
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.streamingText)
        assertNull(viewModel.uiState.value.pendingReferenceCount)
        assertFalse(viewModel.uiState.value.isSending)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun 停止すると収集がキャンセルされ送信中が解除される() = runTest {
        var cancelled = false
        val endlessRepository = object : FakeChatRepository() {
            override fun sendMessageStream(
                conversationId: Long,
                userMessage: String,
                pinnedBookmarkId: Long?,
                isRetry: Boolean,
                attachment: ChatAttachmentContext?
            ): Flow<ChatStreamEvent> = flow {
                emit(ChatStreamEvent.Delta("途中"))
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
        }
        val viewModel = createViewModel(repo = endlessRepository)
        backgroundScope.launch { viewModel.uiState.collect {} }

        viewModel.sendMessage("hi")
        runCurrent()
        assertEquals("途中", viewModel.uiState.value.streamingText)

        viewModel.stopGenerating()
        advanceUntilIdle()
        assertTrue(cancelled)
        assertFalse(viewModel.uiState.value.isSending)
        assertNull(viewModel.uiState.value.streamingText)
        assertNull(viewModel.uiState.value.error)
    }

    /** 回答の文字が届かないまま止まり続ける送信。 */
    private fun silentRepository() = object : FakeChatRepository() {
        override fun sendMessageStream(
            conversationId: Long,
            userMessage: String,
            pinnedBookmarkId: Long?,
            isRetry: Boolean,
            attachment: ChatAttachmentContext?
        ): Flow<ChatStreamEvent> = flow {
            sendCalls += SendCall(conversationId, userMessage, pinnedBookmarkId, isRetry)
            emit(ChatStreamEvent.Started(emptyList()))
            awaitCancellation()
        }
    }

    @Test
    fun 最初の文字が届く前に止めると同じ質問を送り直せる() = runTest {
        val silent = silentRepository()
        val viewModel = createViewModel(repo = silent)
        backgroundScope.launch { viewModel.uiState.collect {} }

        viewModel.sendMessage("質問")
        runCurrent()
        viewModel.stopGenerating()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isSending)
        assertEquals("質問", state.error?.failedMessage)
        assertFalse(state.error!!.needsAiSettings)

        viewModel.retry()
        runCurrent()
        assertEquals(true, silent.sendCalls.last().isRetry)
        assertNull(viewModel.uiState.value.error)
        viewModel.stopGenerating()
        advanceUntilIdle()
    }

    @Test
    fun 会話を削除して止めたときは送り直しを出さない() = runTest {
        val viewModel = createViewModel(repo = silentRepository())
        backgroundScope.launch { viewModel.uiState.collect {} }

        viewModel.sendMessage("質問")
        runCurrent()
        viewModel.deleteConversation(onDeleted = {})
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun 使えるAIが画面の状態に出て_選ぶと保存先に渡す() = runTest {
        val local = ChatModelOption.Local("gemma", "Gemma.litertlm")
        val other = ChatModelOption.Local("qwen", "Qwen.task")
        chatModels.state.value = ChatModelChoices(listOf(local, other), selected = local)
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()
        assertEquals(local, viewModel.uiState.value.modelChoices.selected)

        viewModel.selectModel(other)
        advanceUntilIdle()

        assertEquals(listOf<ChatModelOption>(other), chatModels.selections)
        assertEquals(other, viewModel.uiState.value.modelChoices.selected)
    }

    @Test
    fun APIの失敗は種類ごとの日本語メッセージになる() = runTest {
        repository.sendFailure = LlmException.ApiKeyMissing(ApiProvider.GEMINI)
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }

        viewModel.sendMessage("hi")
        advanceUntilIdle()

        val error = viewModel.uiState.value.error!!
        assertEquals("Gemini (Google) の API キーが未設定です。設定 > AI 設定 で入力してください", error.message)
        assertTrue(error.needsAiSettings)
        assertFalse(viewModel.uiState.value.isSending)
    }

    @Test
    fun ブックマークについての質問では対象を固定しタイトルにブックマーク名を使う() = runTest {
        repository.bookmarks = listOf(testBookmark(id = 7L, title = "Compose のヒント"))
        val viewModel = createViewModel(SavedStateHandle(mapOf(Routes.ARG_ABOUT_BOOKMARK_ID to 7L)))
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(7L, state.aboutBookmark?.id)
        assertEquals(ChatSuggestions.forBookmark, state.suggestions)
        assertTrue(state.isEmptyConversation)

        viewModel.sendMessage("要約して")
        advanceUntilIdle()

        assertEquals(listOf<String?>("Compose のヒントについて"), repository.createdTitles)
        assertEquals(listOf<Long?>(7L), repository.createdAboutBookmarkIds)
        assertEquals(7L, repository.sendCalls.single().pinnedBookmarkId)
    }

    @Test
    fun 通常の会話は対象なしで作られる() = runTest {
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }

        viewModel.sendMessage("hi")
        advanceUntilIdle()

        assertEquals(listOf<Long?>(null), repository.createdAboutBookmarkIds)
        assertNull(repository.sendCalls.single().pinnedBookmarkId)
    }

    @Test
    fun 会話一覧から開き直すと保存した対象を固定する() = runTest {
        repository.bookmarks = listOf(testBookmark(id = 7L, title = "Compose のヒント"))
        repository.conversation.value = Conversation(
            id = 9L, title = "Compose のヒントについて", isTitleManuallySet = true,
            createdAt = 0L, updatedAt = 0L, aboutBookmarkId = 7L
        )
        val viewModel = createViewModel(SavedStateHandle(mapOf(ChatViewModel.ARG_CONVERSATION_ID to 9L)))
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(7L, state.aboutBookmark?.id)
        assertEquals(ChatSuggestions.forBookmark, state.suggestions)

        viewModel.sendMessage("続きを教えて")
        advanceUntilIdle()

        assertEquals(0, repository.createCalls)
        assertEquals(FakeChatRepository.SendCall(9L, "続きを教えて", 7L, isRetry = false), repository.sendCalls.single())
    }

    @Test
    fun 保存した対象が削除済みなら通常の会話として扱う() = runTest {
        repository.bookmarks = listOf(testBookmark(id = 1L).copy(tags = listOf("Kotlin")))
        repository.conversation.value = Conversation(
            id = 9L, title = "消えた記事について", isTitleManuallySet = true,
            createdAt = 0L, updatedAt = 0L, aboutBookmarkId = 7L
        )
        val viewModel = createViewModel(SavedStateHandle(mapOf(ChatViewModel.ARG_CONVERSATION_ID to 9L)))
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.aboutBookmark)
        assertEquals(ChatSuggestions.forLibrary(repository.bookmarks), state.suggestions)

        viewModel.sendMessage("質問")
        advanceUntilIdle()

        assertNull(repository.sendCalls.single().pinnedBookmarkId)
    }

    @Test
    fun 対象のブックマークが削除済みなら新しい会話も通常の会話として作る() = runTest {
        val viewModel = createViewModel(SavedStateHandle(mapOf(Routes.ARG_ABOUT_BOOKMARK_ID to 7L)))
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.aboutBookmark)

        viewModel.sendMessage("質問")
        advanceUntilIdle()

        assertEquals(listOf<String?>(null), repository.createdTitles)
        assertEquals(listOf<Long?>(null), repository.createdAboutBookmarkIds)
        assertNull(repository.sendCalls.single().pinnedBookmarkId)
    }

    @Test
    fun 通常の会話では保存済みのタグから質問の例を作る() = runTest {
        repository.bookmarks = listOf(
            testBookmark(id = 1L).copy(tags = listOf("Kotlin")),
            testBookmark(id = 2L).copy(tags = listOf("Kotlin", "Android"))
        )
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.aboutBookmark)
        assertEquals(ChatSuggestions.forLibrary(repository.bookmarks), state.suggestions)
        assertTrue(state.suggestions.any { "Kotlin" in it })
    }

    @Test
    fun AIの準備状態を画面に伝える() = runTest {
        aiSetup.state.value = AiSetupState.API_KEY_MISSING
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        assertEquals(AiSetupState.API_KEY_MISSING, viewModel.uiState.value.aiSetup)

        aiSetup.state.value = AiSetupState.READY
        advanceUntilIdle()
        assertEquals(AiSetupState.READY, viewModel.uiState.value.aiSetup)
    }

    @Test
    fun 名前変更と削除は作成済みの会話に対して行う() = runTest {
        val viewModel = createViewModel(SavedStateHandle(mapOf(ChatViewModel.ARG_CONVERSATION_ID to 7L)))
        var closed = false

        viewModel.rename("新しい名前")
        viewModel.deleteConversation { closed = true }
        advanceUntilIdle()

        assertEquals(listOf(7L to "新しい名前"), repository.renamed)
        assertEquals(listOf(7L), repository.deletedIds)
        assertTrue(closed)
    }

    private fun conversationMessages() = listOf(
        ChatMessage(id = 1, conversationId = 7, role = ChatRole.USER, content = "最初の質問", createdAt = 1),
        ChatMessage(id = 2, conversationId = 7, role = ChatRole.ASSISTANT, content = "最初の回答", createdAt = 2),
        ChatMessage(id = 3, conversationId = 7, role = ChatRole.USER, content = "次の質問", createdAt = 3),
        ChatMessage(id = 4, conversationId = 7, role = ChatRole.ASSISTANT, content = "次の回答", createdAt = 4)
    )

    @Test
    fun 回答を作り直すと最後の回答を消して同じ質問を再試行として送る() = runTest {
        repository.messages.value = conversationMessages()
        val viewModel = createViewModel(SavedStateHandle(mapOf(ChatViewModel.ARG_CONVERSATION_ID to 7L)))

        viewModel.regenerate()
        advanceUntilIdle()

        assertEquals(listOf(4L), repository.deletedFrom.map { it.id })
        // 残った最後の質問を使い回す(isRetry)
        assertEquals(listOf(FakeChatRepository.SendCall(7L, "次の質問", null, true)), repository.sendCalls)
    }

    @Test
    fun 最後が質問なら作り直さない() = runTest {
        repository.messages.value = conversationMessages().dropLast(1)
        val viewModel = createViewModel(SavedStateHandle(mapOf(ChatViewModel.ARG_CONVERSATION_ID to 7L)))

        viewModel.regenerate()
        advanceUntilIdle()

        assertTrue(repository.deletedFrom.isEmpty())
        assertTrue(repository.sendCalls.isEmpty())
    }

    @Test
    fun 最後の質問を編集するとその質問から先を消して直した質問を送る() = runTest {
        repository.messages.value = conversationMessages()
        val viewModel = createViewModel(SavedStateHandle(mapOf(ChatViewModel.ARG_CONVERSATION_ID to 7L)))

        viewModel.editLastMessage("  直した質問 ")
        advanceUntilIdle()

        assertEquals(listOf(3L), repository.deletedFrom.map { it.id })
        assertEquals(listOf(FakeChatRepository.SendCall(7L, "直した質問", null, false)), repository.sendCalls)
    }

    @Test
    fun 未作成の会話では名前変更も削除もしない() = runTest {
        val viewModel = createViewModel()

        viewModel.rename("名前")
        viewModel.deleteConversation {}
        advanceUntilIdle()

        assertTrue(repository.renamed.isEmpty())
        assertTrue(repository.deletedIds.isEmpty())
    }

    @Test
    fun 添付したファイルは次の質問にだけ使い_送信すると入力欄から外れる() = runTest {
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }

        viewModel.attachFile("content://docs/1")
        advanceUntilIdle()
        assertEquals("資料.txt", viewModel.uiState.value.attachmentName)

        viewModel.sendMessage("この資料の要点は?")
        advanceUntilIdle()

        assertEquals(ChatAttachmentContext("資料.txt", "添付の本文"), repository.sentAttachments.last())
        assertNull(viewModel.uiState.value.attachmentName)
    }

    @Test
    fun 失敗した質問を送り直すときは同じ添付を使う() = runTest {
        repository.sendFailure = IllegalStateException("boom")
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        viewModel.attachFile("content://docs/1")
        advanceUntilIdle()
        viewModel.sendMessage("この資料の要点は?")
        advanceUntilIdle()

        repository.sendFailure = null
        viewModel.retry()
        advanceUntilIdle()

        assertEquals(2, repository.sentAttachments.size)
        assertEquals(repository.sentAttachments[0], repository.sentAttachments[1])
        assertEquals(ChatAttachmentContext("資料.txt", "添付の本文"), repository.sentAttachments[1])
    }

    @Test
    fun 添付を読めないときは理由を出し_質問は添付なしで送れる() = runTest {
        val viewModel = createViewModelWithAttachmentFailure()
        backgroundScope.launch { viewModel.uiState.collect {} }

        viewModel.attachFile("content://docs/broken")
        advanceUntilIdle()

        assertEquals("「壊れた.bin」の中身を読み取れませんでした", viewModel.uiState.value.attachmentError)
        assertNull(viewModel.uiState.value.attachmentName)
        assertFalse(viewModel.uiState.value.isAttaching)
    }

    private fun createViewModelWithAttachmentFailure() = ChatViewModel(
        repository,
        aiSetup,
        SavedStateHandle(),
        object : ChatAttachmentSource {
            override suspend fun load(uri: String): ChatAttachmentContext =
                throw ChatAttachmentException("「壊れた.bin」の中身を読み取れませんでした")
        },
        chatModels
    )
}
