package com.unchunks.echomark.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.unchunks.echomark.domain.provider.ApiProvider
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

/** 表示・オンボーディング設定の既定値・保存・初期化を確かめる。 */
class AppSettingsRepositoryImplTest {

    // 実ファイルの DataStore は Windows 上の JVM で上書き rename に失敗することがあるため、メモリ上の実装を使う
    private fun createRepository() = AppSettingsRepositoryImpl(InMemorySettingsDataStore())

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
        repository.setLlmBackend(LlmBackend.API)
        repository.setApiProvider(ApiProvider.OPENAI)
        repository.setRediscoverEnabled(true)
        repository.setRediscoverSchedule(DayOfWeek.MONDAY, 7, 30)

        repository.resetToDefaults()

        assertEquals(ThemeMode.SYSTEM, repository.themeMode.first())
        assertFalse(repository.dynamicColor.first())
        assertEquals(LlmBackend.LOCAL, repository.llmBackend.first())
        assertEquals(ApiProvider.CLAUDE, repository.apiProvider.first())
        assertEquals(RediscoverSettings(), repository.rediscoverSettings.first())
        assertTrue(repository.onboardingCompleted.first())
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
