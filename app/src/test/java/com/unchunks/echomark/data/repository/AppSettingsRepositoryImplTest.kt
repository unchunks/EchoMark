package com.unchunks.echomark.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.unchunks.echomark.domain.provider.ApiProvider
import com.unchunks.echomark.domain.repository.AiTask
import com.unchunks.echomark.domain.repository.AiTaskSetting
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.domain.repository.RediscoverSettings
import com.unchunks.echomark.domain.repository.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

/** 表示・オンボーディング・AI の設定の既定値・保存・初期化を確かめる。 */
class AppSettingsRepositoryImplTest {

    // 実ファイルの DataStore は Windows 上の JVM で上書き rename に失敗することがあるため、メモリ上の実装を使う
    private fun createRepository(dataStore: DataStore<Preferences> = InMemorySettingsDataStore()) =
        AppSettingsRepositoryImpl(dataStore)

    @Test
    fun 既定値は端末に合わせたテーマ_ダイナミックカラーなし_オンボーディング未完了() = runTest {
        val repository = createRepository()
        assertEquals(ThemeMode.SYSTEM, repository.themeMode.first())
        assertFalse(repository.dynamicColor.first())
        assertFalse(repository.onboardingCompleted.first())
    }

    @Test
    fun 保存した値が読み出せる() = runTest {
        val repository = createRepository()
        repository.setThemeMode(ThemeMode.DARK)
        repository.setDynamicColor(true)
        repository.setOnboardingCompleted(true)

        assertEquals(ThemeMode.DARK, repository.themeMode.first())
        assertTrue(repository.dynamicColor.first())
        assertTrue(repository.onboardingCompleted.first())
    }

    @Test
    fun 初期化するとオンボーディング済み以外が既定値に戻る() = runTest {
        val repository = createRepository()
        repository.setThemeMode(ThemeMode.LIGHT)
        repository.setDynamicColor(true)
        repository.setOnboardingCompleted(true)
        repository.setLlmBackend(AiTask.CHAT, LlmBackend.API)
        repository.setApiProvider(AiTask.CHAT, ApiProvider.OPENAI)
        repository.setRediscoverEnabled(true)
        repository.setRediscoverSchedule(DayOfWeek.MONDAY, 7, 30)

        repository.resetToDefaults()

        assertEquals(ThemeMode.SYSTEM, repository.themeMode.first())
        assertFalse(repository.dynamicColor.first())
        val chat = repository.aiTaskSettings.first().getValue(AiTask.CHAT)
        assertEquals(LlmBackend.LOCAL, chat.backend)
        assertEquals(ApiProvider.CLAUDE, chat.apiProvider)
        assertEquals(RediscoverSettings(), repository.rediscoverSettings.first())
        assertTrue(repository.onboardingCompleted.first())
    }

    @Test
    fun AIの既定値は全用途で端末内_Claudeの既定モデル() = runTest {
        val settings = createRepository().aiTaskSettings.first()

        assertEquals(AiTask.entries.toSet(), settings.keys)
        settings.values.forEach {
            assertEquals(LlmBackend.LOCAL, it.backend)
            assertEquals(ApiProvider.CLAUDE, it.apiProvider)
            assertEquals(ApiProvider.CLAUDE.defaultModel, it.apiModel)
        }
    }

    @Test
    fun AIの設定は用途ごとに保存し_ほかの用途に影響しない() = runTest {
        val repository = createRepository()
        repository.setLlmBackend(AiTask.SUMMARY, LlmBackend.API)
        repository.setApiProvider(AiTask.SUMMARY, ApiProvider.GEMINI)
        repository.setApiModel(AiTask.SUMMARY, ApiProvider.GEMINI, " gemini-3.5-flash-lite ")
        repository.setApiProvider(AiTask.CHAT, ApiProvider.GEMINI)

        val settings = repository.aiTaskSettings.first()
        val summary = settings.getValue(AiTask.SUMMARY)
        assertEquals(LlmBackend.API, summary.backend)
        assertEquals("gemini-3.5-flash-lite", summary.apiModel)
        assertEquals(AiTaskSetting(), settings.getValue(AiTask.TAGGING).copy(apiModels = emptyMap()))
        assertEquals(LlmBackend.LOCAL, settings.getValue(AiTask.CHAT).backend)
        assertEquals(ApiProvider.GEMINI.defaultModel, settings.getValue(AiTask.CHAT).apiModel)

        // 空白のモデル ID は既定値に戻す
        repository.setApiModel(AiTask.SUMMARY, ApiProvider.GEMINI, " ")
        assertEquals(ApiProvider.GEMINI.defaultModel, repository.aiTaskSettings.first().getValue(AiTask.SUMMARY).apiModel)
    }

    @Test
    fun 用途を分ける前の共通の設定を全用途に引き継ぐ() = runTest {
        val dataStore = InMemorySettingsDataStore()
        dataStore.edit {
            it[stringPreferencesKey("llm_backend")] = "API"
            it[stringPreferencesKey("api_provider")] = "OPENAI"
            it[stringPreferencesKey("api_model_openai")] = "gpt-6-luna"
        }
        val repository = createRepository(dataStore)

        repository.aiTaskSettings.first().values.forEach {
            assertEquals(LlmBackend.API, it.backend)
            assertEquals(ApiProvider.OPENAI, it.apiProvider)
            assertEquals("gpt-6-luna", it.apiModel)
        }

        // 用途ごとに変えた値が優先され、既定値に戻したモデルは共通の設定に戻らない
        repository.setLlmBackend(AiTask.CHAT, LlmBackend.LOCAL)
        repository.setApiModel(AiTask.SUMMARY, ApiProvider.OPENAI, "")
        val settings = repository.aiTaskSettings.first()
        assertEquals(LlmBackend.LOCAL, settings.getValue(AiTask.CHAT).backend)
        assertEquals(ApiProvider.OPENAI.defaultModel, settings.getValue(AiTask.SUMMARY).apiModel)
        assertEquals("gpt-6-luna", settings.getValue(AiTask.TAGGING).apiModel)
    }

    @Test
    fun 未完了のまま初期化してもオンボーディングは未完了のまま() = runTest {
        val repository = createRepository()
        repository.setThemeMode(ThemeMode.DARK)

        repository.resetToDefaults()

        assertFalse(repository.onboardingCompleted.first())
    }
}

private class InMemorySettingsDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    private val mutex = Mutex()
    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        mutex.withLock { transform(state.value).also { state.value = it } }
}
