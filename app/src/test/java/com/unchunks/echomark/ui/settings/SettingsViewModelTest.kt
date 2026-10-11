package com.unchunks.echomark.ui.settings

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.data.ai.model.ModelManager
import com.unchunks.echomark.domain.provider.ApiProvider
import com.unchunks.echomark.domain.repository.AiTask
import com.unchunks.echomark.domain.repository.DataOperationException
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.domain.repository.ThemeMode
import com.unchunks.echomark.testing.FakeApiKeyRepository
import com.unchunks.echomark.testing.FakeAppSettingsRepository
import com.unchunks.echomark.testing.FakeBookmarkRepository
import com.unchunks.echomark.testing.FakeDataManagementRepository
import com.unchunks.echomark.testing.FakeRediscoverScheduleController
import com.unchunks.echomark.testing.MainDispatcherRule
import com.unchunks.echomark.testing.TestDispatcherProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek

/** ModelManager が Context を使うため Robolectric で動かす。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SettingsViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val settings = FakeAppSettingsRepository()
    private val apiKeys = FakeApiKeyRepository()
    private val data = FakeDataManagementRepository()
    private val scheduler = FakeRediscoverScheduleController()

    private fun createViewModel() = SettingsViewModel(
        appSettings = settings,
        apiKeyRepository = apiKeys,
        modelManager = ModelManager(
            ApplicationProvider.getApplicationContext(),
            FakeBookmarkRepository(),
            TestDispatcherProvider(mainRule.dispatcher)
        ),
        dataRepository = data,
        scheduleController = scheduler
    )

    @Test
    fun 読み込み後にAIの状態_表示設定_ストレージが反映される() = runTest {
        settings.setBackendForAll(LlmBackend.API)
        settings.themeModeFlow.value = ThemeMode.DARK
        apiKeys.setKey(ApiProvider.CLAUDE, "sk-test")
        val viewModel = createViewModel()
        assertFalse(viewModel.uiState.value.isLoaded)

        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.isLoaded)
        assertTrue(state.ai.isReady)
        assertEquals("Claude API(claude-opus-5-5)", state.ai.label)
        assertEquals(ThemeMode.DARK, state.themeMode)
        assertEquals(data.storage, state.storage)
    }

    @Test
    fun AIが未設定なら未設定と表示する() = runTest {
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.ai.isReady)
        assertEquals("未設定(端末内モデルが未取り込み)", viewModel.uiState.value.ai.label)

        settings.setBackendForAll(LlmBackend.API)
        AiTask.entries.forEach { settings.setApiProvider(it, ApiProvider.GEMINI) }
        advanceUntilIdle()
        assertEquals("未設定(Gemini の API キーが未入力)", viewModel.uiState.value.ai.label)
    }

    @Test
    fun 用途ごとにAIが違えば用途ごとに表示し_すべて使えるときだけ準備完了() = runTest {
        apiKeys.setKey(ApiProvider.CLAUDE, "sk-test")
        settings.setLlmBackend(AiTask.SUMMARY, LlmBackend.API)
        settings.setLlmBackend(AiTask.CHAT, LlmBackend.API)
        settings.setApiProvider(AiTask.CHAT, ApiProvider.OPENAI)
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        val ai = viewModel.uiState.value.ai
        assertFalse(ai.isReady)
        assertEquals(setOf(LlmBackend.LOCAL, LlmBackend.API), ai.backends)
        assertEquals(
            "タグ付け: 未設定(端末内モデルが未取り込み) / 要約: Claude(claude-opus-5-5) / " +
                "チャット: 未設定(OpenAI の API キーが未入力)",
            ai.label
        )
    }

    @Test
    fun テーマとダイナミックカラーを保存する() = runTest {
        val viewModel = createViewModel()
        viewModel.setThemeMode(ThemeMode.LIGHT)
        viewModel.setDynamicColor(true)
        advanceUntilIdle()
        assertEquals(ThemeMode.LIGHT, settings.themeModeFlow.value)
        assertTrue(settings.dynamicColorFlow.value)
    }

    @Test
    fun 再発見通知の変更を保存してスケジュールに反映する() = runTest {
        val viewModel = createViewModel()
        viewModel.setRediscoverEnabled(true)
        advanceUntilIdle()
        viewModel.setRediscoverSchedule(DayOfWeek.MONDAY, 7, 30)
        advanceUntilIdle()

        assertEquals(2, scheduler.applied.size)
        val last = scheduler.applied.last()
        assertTrue(last.enabled)
        assertEquals(DayOfWeek.MONDAY, last.dayOfWeek)
        assertEquals(7, last.hour)
        assertEquals(30, last.minute)
    }

    @Test
    fun 読み込みが成功すると結果を出し_閉じると元に戻る() = runTest {
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()
        val storageCallsBefore = data.storageCalls

        viewModel.importBackup("content://backup.json")
        advanceUntilIdle()

        assertEquals(listOf("content://backup.json"), data.importedUris)
        assertEquals(DataOperationState.Imported(data.importSummary), viewModel.uiState.value.dataOperation)
        // データが変わったのでストレージ使用量を計算し直す
        assertTrue(data.storageCalls > storageCallsBefore)

        viewModel.onDataResultDismissed()
        advanceUntilIdle()
        assertEquals(DataOperationState.Idle, viewModel.uiState.value.dataOperation)
    }

    @Test
    fun 書き出しに失敗するとエラーの文言を出す() = runTest {
        data.failure = DataOperationException("保存先にアクセスできませんでした")
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }

        viewModel.exportBackup("content://out.json")
        advanceUntilIdle()

        assertEquals(
            DataOperationState.Failed("書き出せませんでした", "保存先にアクセスできませんでした"),
            viewModel.uiState.value.dataOperation
        )
    }

    @Test
    fun 書き出しが成功すると件数をメッセージで出す() = runTest {
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }

        viewModel.exportBackup("content://out.json")
        advanceUntilIdle()

        assertEquals(DataOperationState.Idle, viewModel.uiState.value.dataOperation)
        assertEquals("3 件のブックマークと 1 件の会話を書き出しました", viewModel.message.value)
    }

    @Test
    fun 全データ削除は設定の初期化の有無を渡す() = runTest {
        val viewModel = createViewModel()
        viewModel.deleteAllData(resetSettings = true)
        advanceUntilIdle()

        assertEquals(listOf(true), data.deleteCalls)
        assertEquals("すべてのデータと設定を初期化しました", viewModel.message.value)
    }

    @Test
    fun サイズと表示名の整形() {
        assertEquals("0 KB", formatStorageSize(0))
        assertEquals("1 KB", formatStorageSize(100))
        assertEquals("410 KB", formatStorageSize(420_000))
        assertEquals("1.2 MB", formatStorageSize(1_300_000))
        assertEquals("530 MB", formatStorageSize(556_000_000))
        assertEquals("1.5 GB", formatStorageSize(1_610_612_736))
        assertEquals("gemma3-1b-it-int4", modelDisplayName("gemma3-1b-it-int4.task"))
    }
}
