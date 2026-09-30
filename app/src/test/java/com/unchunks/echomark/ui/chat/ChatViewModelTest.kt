package com.unchunks.echomark.ui.chat

import androidx.lifecycle.SavedStateHandle
import com.unchunks.echomark.domain.provider.ApiProvider
import com.unchunks.echomark.domain.provider.LlmException
import com.unchunks.echomark.domain.provider.ModelNotAvailableException
import com.unchunks.echomark.domain.repository.ChatStreamEvent
import com.unchunks.echomark.testing.FakeChatRepository
import com.unchunks.echomark.testing.MainDispatcherRule
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
            override fun sendMessageStream(conversationId: Long, userMessage: String): Flow<ChatStreamEvent> = flow {
                sent += conversationId to userMessage
                gate.await()
                emit(ChatStreamEvent.Completed(1L))
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

    @Test
    fun 生成中のテキストがstreamingTextに出て完了で消える() = runTest {
        val gate = CompletableDeferred<Unit>()
        val streamingRepository = object : FakeChatRepository() {
            override fun sendMessageStream(conversationId: Long, userMessage: String): Flow<ChatStreamEvent> = flow {
                emit(ChatStreamEvent.Started(listOf(1L)))
                emit(ChatStreamEvent.Delta("こんに"))
                emit(ChatStreamEvent.Delta("こんにちは"))
                gate.await()
                emit(ChatStreamEvent.Completed(10L))
            }
        }
        val viewModel = ChatViewModel(streamingRepository, SavedStateHandle())
        backgroundScope.launch { viewModel.uiState.collect {} }

        viewModel.sendMessage("hi")
        runCurrent()
        assertEquals("こんにちは", viewModel.uiState.value.streamingText)
        assertTrue(viewModel.uiState.value.isSending)

        gate.complete(Unit)
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.streamingText)
        assertFalse(viewModel.uiState.value.isSending)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun 停止すると収集がキャンセルされ送信中が解除される() = runTest {
        var cancelled = false
        val endlessRepository = object : FakeChatRepository() {
            override fun sendMessageStream(conversationId: Long, userMessage: String): Flow<ChatStreamEvent> = flow {
                emit(ChatStreamEvent.Delta("途中"))
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
        }
        val viewModel = ChatViewModel(endlessRepository, SavedStateHandle())
        backgroundScope.launch { viewModel.uiState.collect {} }

        viewModel.sendMessage("hi")
        runCurrent()
        assertEquals("途中", viewModel.uiState.value.streamingText)

        viewModel.stopGenerating()
        advanceUntilIdle()
        assertTrue(cancelled)
        assertFalse(viewModel.uiState.value.isSending)
        assertNull(viewModel.uiState.value.streamingText)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun APIの失敗は種類ごとの日本語メッセージになる() = runTest {
        repository.sendFailure = LlmException.ApiKeyMissing(ApiProvider.GEMINI)
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }

        viewModel.sendMessage("hi")
        advanceUntilIdle()

        assertEquals(LlmException.ApiKeyMissing(ApiProvider.GEMINI).userMessage, viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.isSending)
    }
}
