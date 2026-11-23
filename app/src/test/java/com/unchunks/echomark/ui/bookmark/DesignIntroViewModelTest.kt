package com.unchunks.echomark.ui.bookmark

import com.unchunks.components.testing.MainDispatcherRule
import com.unchunks.echomark.domain.repository.DesignStyle
import com.unchunks.echomark.testing.FakeAppSettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DesignIntroViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val settings = FakeAppSettingsRepository()

    @Test
    fun クラシックのまま見ていなければ案内を出す() = runTest {
        val viewModel = DesignIntroViewModel(settings)
        backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()
        assertTrue(viewModel.state.value.show)

        // 設定で別のデザインを選んでいる人には出さない
        settings.designStyleFlow.value = DesignStyle.PAPER
        advanceUntilIdle()
        assertFalse(viewModel.state.value.show)
    }

    @Test
    fun 選んだデザインを使い案内は見終えたことにする() = runTest {
        val viewModel = DesignIntroViewModel(settings)
        backgroundScope.launch { viewModel.state.collect {} }
        viewModel.choose(DesignStyle.ECHO)
        advanceUntilIdle()
        assertEquals(DesignStyle.ECHO, settings.designStyleFlow.value)
        assertTrue(settings.designIntroSeenFlow.value)
        assertFalse(viewModel.state.value.show)
    }

    @Test
    fun クラシックを選び直しても閉じても二度と出さない() = runTest {
        val viewModel = DesignIntroViewModel(settings)
        backgroundScope.launch { viewModel.state.collect {} }
        viewModel.choose(DesignStyle.CLASSIC)
        advanceUntilIdle()
        assertFalse(viewModel.state.value.show)

        val another = FakeAppSettingsRepository()
        val dismissed = DesignIntroViewModel(another)
        backgroundScope.launch { dismissed.state.collect {} }
        dismissed.dismiss()
        advanceUntilIdle()
        assertTrue(another.designIntroSeenFlow.value)
        assertFalse(dismissed.state.value.show)
    }

    @Test
    fun ダイナミックカラーの状態を伝える() = runTest {
        settings.dynamicColorFlow.value = true
        val viewModel = DesignIntroViewModel(settings)
        backgroundScope.launch { viewModel.state.collect {} }
        advanceUntilIdle()
        assertTrue(viewModel.state.value.dynamicColor)
    }
}
