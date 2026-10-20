package com.unchunks.echomark.ui.chat

import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.model.ChatRole
import com.unchunks.echomark.domain.model.Conversation
import com.unchunks.echomark.domain.model.ConversationPreview
import com.unchunks.echomark.domain.repository.AiSetupState
import com.unchunks.echomark.testing.FakeAiSetupRepository
import com.unchunks.echomark.testing.FakeChatRepository
import com.unchunks.echomark.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationListViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val repository = FakeChatRepository()
    private val aiSetup = FakeAiSetupRepository()

    private fun TestScope.collectDeletedEvents(viewModel: ConversationListViewModel): MutableList<ConversationPreview> {
        val events = mutableListOf<ConversationPreview>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.deletedEvents.toList(events) }
        return events
    }

    private fun preview(id: Long, updatedAt: Long = id) = ConversationPreview(
        conversation = Conversation(id = id, title = "会話$id", createdAt = 0L, updatedAt = updatedAt),
        lastMessage = "最後$id",
        lastMessageRole = ChatRole.ASSISTANT
    )

    @Test
    fun 読み込み後に会話とAIの準備状態を出す() = runTest {
        repository.previews.value = listOf(preview(2), preview(1))
        aiSetup.state.value = AiSetupState.LOCAL_MODEL_MISSING
        val viewModel = ConversationListViewModel(repository, aiSetup)
        assertTrue(viewModel.uiState.value.isLoading)
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(listOf(2L, 1L), state.conversations.map { it.conversation.id })
        assertEquals(AiSetupState.LOCAL_MODEL_MISSING, state.aiSetup)
    }

    @Test
    fun 削除すると取り消しの知らせを出し_元に戻すとメッセージごと復元する() = runTest {
        val target = preview(1)
        val messages = listOf(
            ChatMessage(id = 10, conversationId = 1, role = ChatRole.USER, content = "q", createdAt = 1),
            ChatMessage(id = 11, conversationId = 1, role = ChatRole.ASSISTANT, content = "a", createdAt = 2)
        )
        repository.previews.value = listOf(target)
        repository.messages.value = messages
        val viewModel = ConversationListViewModel(repository, aiSetup)
        backgroundScope.launch { viewModel.uiState.collect {} }
        val events = collectDeletedEvents(viewModel)

        viewModel.delete(target)
        advanceUntilIdle()
        assertEquals(listOf(1L), repository.deletedIds)
        assertEquals(listOf(target), events)
        assertTrue(viewModel.uiState.value.conversations.isEmpty())

        viewModel.undoDelete(target.conversation.id)
        advanceUntilIdle()
        assertEquals(listOf(target.conversation to messages), repository.restored)
        assertEquals(listOf(1L), viewModel.uiState.value.conversations.map { it.conversation.id })
    }

    @Test
    fun 削除の知らせは一度きりで_画面に戻って購読し直しても再送しない() = runTest {
        val target = preview(1)
        repository.previews.value = listOf(target)
        val viewModel = ConversationListViewModel(repository, aiSetup)
        val first = mutableListOf<ConversationPreview>()
        val firstJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.deletedEvents.toList(first) }

        viewModel.delete(target)
        advanceUntilIdle()
        assertEquals(listOf(target), first)

        // Snackbar の表示中に別の画面へ移り(購読が止まり)、戻ってきた
        firstJob.cancel()
        val second = collectDeletedEvents(viewModel)
        advanceUntilIdle()
        assertTrue(second.isEmpty())
    }

    @Test
    fun Snackbarを閉じたら控えを捨てて復元しない() = runTest {
        val target = preview(1)
        repository.previews.value = listOf(target)
        val viewModel = ConversationListViewModel(repository, aiSetup)
        viewModel.delete(target)
        advanceUntilIdle()

        viewModel.clearRecentlyDeleted(target.conversation.id)
        viewModel.undoDelete(target.conversation.id)
        advanceUntilIdle()

        assertTrue(repository.restored.isEmpty())
    }

    @Test
    fun 続けて削除したとき前のSnackbarが閉じても後の会話は元に戻せる() = runTest {
        val first = preview(1)
        val second = preview(2)
        repository.previews.value = listOf(second, first)
        val viewModel = ConversationListViewModel(repository, aiSetup)
        viewModel.delete(first)
        advanceUntilIdle()
        viewModel.delete(second)
        advanceUntilIdle()

        // 前の Snackbar は新しい Snackbar に置き換えられて閉じる
        viewModel.clearRecentlyDeleted(first.conversation.id)
        viewModel.undoDelete(first.conversation.id)
        viewModel.undoDelete(second.conversation.id)
        advanceUntilIdle()

        assertEquals(listOf(second.conversation), repository.restored.map { it.first })
    }

    @Test
    fun 空白の名前ではリネームしない() = runTest {
        val viewModel = ConversationListViewModel(repository, aiSetup)

        viewModel.rename(1L, "  ")
        viewModel.rename(1L, "新しい名前")
        advanceUntilIdle()

        assertEquals(listOf(1L to "新しい名前"), repository.renamed)
    }

    @Test
    fun 抜粋はMarkdownを外して1行にし_自分の発言にはあなたを付ける() {
        val assistant = ConversationPreview(
            conversation = Conversation(id = 1, title = "t", createdAt = 0, updatedAt = 0),
            lastMessage = "## 要点\n- **Compose** は宣言的 [1]\n- `remember` を使う",
            lastMessageRole = ChatRole.ASSISTANT
        )
        assertEquals("要点 • Compose は宣言的 [1] • remember を使う", conversationExcerpt(assistant))

        val user = assistant.copy(lastMessage = "最近の\n記事は？", lastMessageRole = ChatRole.USER)
        assertEquals("あなた: 最近の 記事は？", conversationExcerpt(user))

        assertNull(conversationExcerpt(assistant.copy(lastMessage = null)))
    }
}
