package com.unchunks.echomark.ui.help

import com.unchunks.components.testing.MainDispatcherRule
import com.unchunks.echomark.domain.help.AppHelpAssistant
import com.unchunks.echomark.domain.help.GuideSection
import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.echomark.domain.provider.ModelNotAvailableException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppHelpViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    /** 呼ばれた引数を覚え、[respond] の結果を返す */
    private class FakeAssistant(var respond: () -> Flow<String> = { flowOf("回答") }) : AppHelpAssistant {
        val calls = mutableListOf<Triple<String, List<GuideSection>, List<ChatMessage>>>()
        override fun answer(question: String, guide: List<GuideSection>, history: List<ChatMessage>): Flow<String> {
            calls += Triple(question, guide, history)
            return respond()
        }
    }

    @Test
    fun 質問を送ると案内を添えて聞き_回答を増分でつなげる() = runTest {
        val assistant = FakeAssistant { flowOf("共有メニューから", "保存します") }
        val viewModel = AppHelpViewModel(assistant)

        viewModel.onInputChange("  保存するには?  ")
        viewModel.send()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("保存するには?", assistant.calls.single().first)
        assertTrue(assistant.calls.single().second.isNotEmpty())
        assertEquals(listOf("保存するには?", "共有メニューから保存します"), state.messages.map { it.text })
        assertTrue(state.messages.last().sourceTitles.isNotEmpty())
        assertEquals("", state.input)
        assertFalse(state.answering)
    }

    @Test
    fun 前のやり取りを履歴として渡す() = runTest {
        val assistant = FakeAssistant()
        val viewModel = AppHelpViewModel(assistant)

        viewModel.askSuggestion("1つ目")
        advanceUntilIdle()
        viewModel.askSuggestion("2つ目")
        advanceUntilIdle()

        assertEquals(listOf("1つ目", "回答"), assistant.calls[1].third.map { it.content })
    }

    @Test
    fun 空の質問は送らない() = runTest {
        val assistant = FakeAssistant()
        val viewModel = AppHelpViewModel(assistant)

        viewModel.onInputChange("   ")
        viewModel.send()
        advanceUntilIdle()

        assertTrue(assistant.calls.isEmpty())
        assertFalse(viewModel.uiState.value.canSend)
    }

    @Test
    fun モデルが無いとAI設定へ案内し_空の回答を残さない() = runTest {
        val viewModel = AppHelpViewModel(FakeAssistant { flow { throw ModelNotAvailableException() } })

        viewModel.askSuggestion("保存するには?")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.error!!.needsAiSettings)
        assertEquals(listOf("保存するには?"), state.messages.map { it.text })
        assertFalse(state.answering)

        viewModel.dismissError()
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun 止めるとそこまでの回答を残す() = runTest {
        val viewModel = AppHelpViewModel(FakeAssistant { flow { emit("途中まで"); awaitCancellation() } })

        viewModel.askSuggestion("保存するには?")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.answering)

        viewModel.stop()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.answering)
        assertEquals("途中まで", state.messages.last().text)
        assertNull(state.error)
    }
}
