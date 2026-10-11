package com.unchunks.echomark.ui.onboarding

import com.unchunks.echomark.domain.repository.AiTask
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.testing.FakeAppSettingsRepository
import com.unchunks.echomark.testing.FakeRediscoverScheduleController
import com.unchunks.echomark.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val settings = FakeAppSettingsRepository(backend = LlmBackend.API)
    private val scheduler = FakeRediscoverScheduleController()

    private fun createViewModel() = OnboardingViewModel(settings, scheduler)

    @Test
    fun 端末内を選ぶと実行場所を保存し_最後にAI設定へ進む() = runTest {
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }

        viewModel.chooseAi(AiSetupChoice.LOCAL)
        advanceUntilIdle()

        AiTask.entries.forEach { assertEquals(LlmBackend.LOCAL, settings.setting(it).backend) }
        assertEquals(OnboardingExit.AI_SETTINGS, viewModel.uiState.value.exit)
    }

    @Test
    fun クラウドAPIを選ぶと実行場所をAPIにする() = runTest {
        settings.setBackendForAll(LlmBackend.LOCAL)
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }

        viewModel.chooseAi(AiSetupChoice.API)
        advanceUntilIdle()

        AiTask.entries.forEach { assertEquals(LlmBackend.API, settings.setting(it).backend) }
        assertEquals(OnboardingExit.AI_SETTINGS, viewModel.uiState.value.exit)
    }

    @Test
    fun あとでを選ぶと設定を変えず_最後は一覧へ() = runTest {
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }

        viewModel.chooseAi(AiSetupChoice.LATER)
        advanceUntilIdle()

        AiTask.entries.forEach { assertEquals(LlmBackend.API, settings.setting(it).backend) }
        assertEquals(OnboardingExit.LIST, viewModel.uiState.value.exit)
    }

    @Test
    fun 何も選ばなければ一覧へ() {
        assertEquals(OnboardingExit.LIST, OnboardingUiState().exit)
    }

    @Test
    fun 通知をオンにすると保存して既定の曜日時刻で予定を登録する() = runTest {
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }

        viewModel.enableRediscover()
        advanceUntilIdle()

        assertTrue(settings.rediscoverFlow.value.enabled)
        assertEquals(listOf(settings.rediscoverFlow.value), scheduler.applied)
        assertTrue(viewModel.uiState.value.rediscover.enabled)
    }

    @Test
    fun 完了するとオンボーディング済みを保存する() = runTest {
        val viewModel = createViewModel()
        viewModel.complete()
        advanceUntilIdle()
        assertTrue(settings.onboardingCompletedFlow.value)
    }
}
