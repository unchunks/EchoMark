package com.unchunks.echomark.ui.help

import com.unchunks.components.testing.MainDispatcherRule
import com.unchunks.echomark.domain.help.AppAction
import com.unchunks.echomark.domain.help.AppActionRunner
import com.unchunks.echomark.domain.help.AppActionSpec
import com.unchunks.echomark.domain.help.AppHelpAssistant
import com.unchunks.echomark.domain.help.AppScreen
import com.unchunks.echomark.domain.repository.ThemeMode
import kotlinx.coroutines.flow.first
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
        val actionLists = mutableListOf<List<AppActionSpec>>()
        override fun answer(
            question: String,
            guide: List<GuideSection>,
            actions: List<AppActionSpec>,
            history: List<ChatMessage>
        ): Flow<String> {
            calls += Triple(question, guide, history)
            actionLists += actions
            return respond()
        }
    }

    /** 実行した操作を覚え、[failure] があれば失敗する */
    private class FakeRunner(var failure: Exception? = null) : AppActionRunner {
        val ran = mutableListOf<AppAction>()
        override suspend fun run(action: AppAction): String {
            failure?.let { throw it }
            ran += action
            return "実行しました"
        }
    }

    private val runner = FakeRunner()

    @Test
    fun 質問を送ると案内を添えて聞き_回答を増分でつなげる() = runTest {
        val assistant = FakeAssistant { flowOf("共有メニューから", "保存します") }
        val viewModel = AppHelpViewModel(assistant, runner)

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
        val viewModel = AppHelpViewModel(assistant, runner)

        viewModel.askSuggestion("1つ目")
        advanceUntilIdle()
        viewModel.askSuggestion("2つ目")
        advanceUntilIdle()

        assertEquals(listOf("1つ目", "回答"), assistant.calls[1].third.map { it.content })
    }

    @Test
    fun 空の質問は送らない() = runTest {
        val assistant = FakeAssistant()
        val viewModel = AppHelpViewModel(assistant, runner)

        viewModel.onInputChange("   ")
        viewModel.send()
        advanceUntilIdle()

        assertTrue(assistant.calls.isEmpty())
        assertFalse(viewModel.uiState.value.canSend)
    }

    @Test
    fun モデルが無いとAI設定へ案内し_回答の代わりに近い画面を開くボタンを残す() = runTest {
        val viewModel = AppHelpViewModel(FakeAssistant { flow { throw ModelNotAvailableException() } }, runner)

        viewModel.askSuggestion("保存するには?")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.error!!.needsAiSettings)
        assertEquals(listOf("保存するには?", ""), state.messages.map { it.text })
        assertEquals(listOf(AppAction.Open(AppScreen.ADD_BOOKMARK)), state.messages.last().actions)
        assertFalse(state.answering)

        viewModel.dismissError()
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun 止めるとそこまでの回答を残す() = runTest {
        val viewModel = AppHelpViewModel(FakeAssistant { flow { emit("途中まで"); awaitCancellation() } }, runner)

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

    @Test
    fun AIが書いた操作の行は隠してボタンにする() = runTest {
        val assistant = FakeAssistant { flowOf("ダークにします。\n操", "作: theme_dark") }
        val viewModel = AppHelpViewModel(assistant, runner)

        viewModel.askSuggestion("ダークモードにして")
        advanceUntilIdle()

        val answer = viewModel.uiState.value.messages.last()
        assertEquals("ダークにします。", answer.text)
        assertEquals(listOf(AppAction.SetTheme(ThemeMode.DARK)), answer.actions)
        assertTrue(assistant.actionLists.single().any { it.id == "theme_dark" })
    }

    @Test
    fun 操作のボタンを押すと実行し_結果を足してボタンを消す() = runTest {
        val viewModel = AppHelpViewModel(FakeAssistant { flowOf("ダークにします。\n操作: theme_dark") }, runner)
        viewModel.askSuggestion("ダークモードにして")
        advanceUntilIdle()
        val answer = viewModel.uiState.value.messages.last()

        viewModel.runAction(answer.id, AppAction.SetTheme(ThemeMode.DARK))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf(AppAction.SetTheme(ThemeMode.DARK)), runner.ran)
        assertTrue(state.messages.first { it.id == answer.id }.actions.isEmpty())
        assertTrue(state.messages.last().isResult)
        assertFalse(state.runningAction)
    }

    @Test
    fun 結果の知らせは次の質問の履歴に入れない() = runTest {
        val assistant = FakeAssistant { flowOf("ダークにします。\n操作: theme_dark") }
        val viewModel = AppHelpViewModel(assistant, runner)
        viewModel.askSuggestion("ダークモードにして")
        advanceUntilIdle()
        viewModel.runAction(viewModel.uiState.value.messages.last().id, AppAction.SetTheme(ThemeMode.DARK))
        advanceUntilIdle()

        viewModel.askSuggestion("ありがとう")
        advanceUntilIdle()

        assertEquals(listOf("ダークモードにして", "ダークにします。"), assistant.calls[1].third.map { it.content })
    }

    @Test
    fun 画面を開く操作は実行せずに画面側へ渡す() = runTest {
        val viewModel = AppHelpViewModel(FakeAssistant(), runner)

        viewModel.runAction(1, AppAction.Open(AppScreen.AI_SETTINGS))

        // 画面側が集める前に送っても、取りこぼさない
        assertEquals(AppAction.Open(AppScreen.AI_SETTINGS), viewModel.navigation.first())
        assertTrue(runner.ran.isEmpty())
    }

    @Test
    fun 操作に失敗したら知らせる() = runTest {
        runner.failure = IllegalStateException("x")
        val viewModel = AppHelpViewModel(FakeAssistant(), runner)

        viewModel.runAction(1, AppAction.ReprocessFailed)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.error!!.message.contains("操作できませんでした"))
        assertFalse(viewModel.uiState.value.runningAction)
    }

    @Test
    fun モデルが無くても質問のURLは保存のボタンにする() = runTest {
        val viewModel = AppHelpViewModel(FakeAssistant { flow { throw ModelNotAvailableException() } }, runner)

        viewModel.askSuggestion("https://example.com/a を保存して")
        advanceUntilIdle()

        val answer = viewModel.uiState.value.messages.last()
        assertEquals(AppAction.SaveLink("https://example.com/a"), answer.actions.first())
        assertTrue(viewModel.uiState.value.error!!.needsAiSettings)
    }
}
