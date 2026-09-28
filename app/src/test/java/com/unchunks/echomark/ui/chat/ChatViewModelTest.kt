package com.unchunks.echomark.ui.chat

import androidx.lifecycle.SavedStateHandle
import com.unchunks.echomark.domain.provider.ModelNotAvailableException
import com.unchunks.echomark.testing.FakeChatRepository
import com.unchunks.echomark.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    private fun createViewModel(savedState: SavedStateHandle = SavedStateHandle()) =
        ChatViewModel(repository, savedState)

    @Test
    fun モデル未取得のときは専用メッセージを出しisSendingが戻る() = runTest {
        repository.sendFailure = ModelNotAvailableException()
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }

        viewModel.sendMessage("こんにちは")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(ChatViewModel.MODEL_NOT_AVAILABLE_MESSAGE, state.errorMessage)
        assertFalse(state.isSending)
    }

    @Test
    fun 一般的な失敗ではエラーメッセージを出しisSendingが戻る() = runTest {
        repository.sendFailure = IllegalStateException("boom")
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }

        viewModel.sendMessage("こんにちは")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.errorMessage!!.startsWith("送信に失敗しました"))
        assertTrue(state.errorMessage!!.contains("boom"))
        assertFalse(state.isSending)
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
        assertNull(state.errorMessage)
        assertFalse(state.isSending)
        assertEquals(listOf("1回目", "2回目"), repository.sent.map { it.second })
    }

    @Test
    fun 送信中は二重送信されない() = runTest {
        val gate = CompletableDeferred<Unit>()
        val slowRepository = object : FakeChatRepository() {
            override suspend fun sendMessage(conversationId: Long, userMessage: String) {
                sent += conversationId to userMessage
                gate.await()
            }
        }
        val viewModel = ChatViewModel(slowRepository, SavedStateHandle())
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
        assertEquals(42L, savedState.get<Long>(ChatViewModel.ARG_CONVERSATION_ID))
        assertEquals(listOf(42L, 42L), repository.sent.map { it.first })
    }

    @Test
    fun 既存会話ならcreateConversationを呼ばない() = runTest {
        val viewModel = createViewModel(SavedStateHandle(mapOf(ChatViewModel.ARG_CONVERSATION_ID to 7L)))
        backgroundScope.launch { viewModel.uiState.collect {} }

        viewModel.sendMessage("hi")
        advanceUntilIdle()

        assertEquals(0, repository.createCalls)
        assertEquals(listOf(7L to "hi"), repository.sent)
    }

    @Test
    fun 空白のメッセージは送信されない() = runTest {
        val viewModel = createViewModel()

        viewModel.sendMessage("  ")
        advanceUntilIdle()

        assertTrue(repository.sent.isEmpty())
        assertEquals(0, repository.createCalls)
    }
}
