package com.unchunks.echomark.ui.podcast

import com.unchunks.components.speechtext.ReadingEntry
import com.unchunks.components.testing.MainDispatcherRule
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.testing.FakeAppSettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadingDictionaryViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val settings = FakeAppSettingsRepository(LlmBackend.LOCAL)

    private fun TestScope.start(viewModel: ReadingDictionaryViewModel): MutableList<ReadingDictionaryMessage> {
        val messages = mutableListOf<ReadingDictionaryMessage>()
        backgroundScope.launch { viewModel.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.messages.toList(messages) }
        return messages
    }

    @Test
    fun 入れた読みが一覧に出る() = runTest {
        settings.readingDictionaryFlow.value = listOf(ReadingEntry("東京", "とうきょう"))
        val viewModel = ReadingDictionaryViewModel(settings)
        assertTrue(viewModel.uiState.value.isLoading)
        start(viewModel)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(listOf(ReadingEntry("東京", "とうきょう")), viewModel.uiState.value.entries)
    }

    @Test
    fun 入れる_直す_消す_戻す() = runTest {
        val viewModel = ReadingDictionaryViewModel(settings)
        val messages = start(viewModel)

        viewModel.save("日本橋", "にっぽんばし")
        advanceUntilIdle()
        viewModel.save("日本橋駅", "にほんばしえき", original = "日本橋")
        advanceUntilIdle()
        assertEquals(listOf(ReadingEntry("日本橋駅", "にほんばしえき")), settings.readingDictionaryFlow.value)

        val entry = settings.readingDictionaryFlow.value.single()
        viewModel.remove(entry)
        advanceUntilIdle()
        assertEquals(emptyList<ReadingEntry>(), settings.readingDictionaryFlow.value)

        viewModel.restore(entry)
        advanceUntilIdle()
        assertEquals(listOf(entry), settings.readingDictionaryFlow.value)
        assertEquals(
            listOf(
                ReadingDictionaryMessage.Saved("日本橋"),
                ReadingDictionaryMessage.Saved("日本橋駅"),
                ReadingDictionaryMessage.Removed(entry),
                ReadingDictionaryMessage.Saved("日本橋駅")
            ),
            messages
        )
    }

    @Test
    fun 誤りのある1件は入れずに理由を知らせる() = runTest {
        val viewModel = ReadingDictionaryViewModel(settings)
        val messages = start(viewModel)

        viewModel.save("GitHub", "github")
        advanceUntilIdle()

        assertEquals(emptyList<ReadingEntry>(), settings.readingDictionaryFlow.value)
        assertEquals(listOf(ReadingDictionaryMessage.Failed("読みが表記と同じです")), messages)
    }

    @Test
    fun 確かめる文に辞書を当てて見せる() = runTest {
        settings.readingDictionaryFlow.value = listOf(ReadingEntry("日本橋", "にほんばし"))
        val viewModel = ReadingDictionaryViewModel(settings)
        start(viewModel)

        viewModel.setSample("2026/11/20 に日本橋で")
        advanceUntilIdle()

        assertEquals("2026年11月20日ににほんばしで", viewModel.uiState.value.samplePreview)
    }
}
