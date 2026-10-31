package com.unchunks.echomark.data.repository

import com.unchunks.echomark.domain.model.ChatTemplate
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.unchunks.components.llm.ApiProvider
import com.unchunks.echomark.domain.bookmark.model.BookmarkListDisplay
import com.unchunks.echomark.domain.bookmark.model.BookmarkSortOrder
import com.unchunks.echomark.domain.bookmark.model.ReadingFont
import com.unchunks.echomark.domain.bookmark.model.ReadingFontSize
import com.unchunks.echomark.domain.bookmark.model.ReadingStyle
import com.unchunks.echomark.domain.bookmark.model.ReadingTheme
import com.unchunks.echomark.domain.model.SummaryFormat
import com.unchunks.echomark.domain.model.SummaryLanguage
import com.unchunks.echomark.domain.model.SummaryStyle
import com.unchunks.echomark.domain.repository.AiTask
import com.unchunks.echomark.domain.repository.AiTaskSetting
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.domain.repository.ProcessingSettings
import com.unchunks.echomark.domain.repository.RediscoverSettings
import com.unchunks.echomark.domain.repository.SUMMARY_DEFAULT_CLAUDE_MODEL
import com.unchunks.echomark.domain.repository.ThemeMode
import com.unchunks.echomark.domain.repository.defaultApiModel
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
    fun 処理の設定は既定値から書き換えられ_範囲の外の値は収めて保存する() = runTest {
        val repository = createRepository()
        assertEquals(ProcessingSettings(), repository.processingSettings.first())

        repository.updateProcessingSettings {
            it.copy(chargingOnly = true, nightlyBatch = true, nightlyHour = 30, maxConcurrent = 99, apiRequestsPerMinute = -1)
        }

        assertEquals(
            ProcessingSettings(
                chargingOnly = true, nightlyBatch = true, nightlyHour = 23,
                maxConcurrent = ProcessingSettings.MAX_CONCURRENT_LIMIT, apiRequestsPerMinute = 0
            ),
            repository.processingSettings.first()
        )
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
    fun 一覧の並び順は既定が新しい順で保存した順を読み出せる() = runTest {
        val dataStore = InMemorySettingsDataStore()
        val repository = createRepository(dataStore)
        assertEquals(BookmarkSortOrder.NEWEST, repository.bookmarkSortOrder.first())

        repository.setBookmarkSortOrder(BookmarkSortOrder.RECENTLY_OPENED)
        assertEquals(BookmarkSortOrder.RECENTLY_OPENED, repository.bookmarkSortOrder.first())
        // 同じ保存先を読む別のインスタンス(アプリを開き直したとき)でも同じ
        assertEquals(BookmarkSortOrder.RECENTLY_OPENED, createRepository(dataStore).bookmarkSortOrder.first())

        // 知らない並び順(古い版・新しい版で名前が変わったもの)は新しい順として扱う
        dataStore.edit { it[stringPreferencesKey("bookmark_sort_order")] = "UNKNOWN" }
        assertEquals(BookmarkSortOrder.NEWEST, repository.bookmarkSortOrder.first())
    }

    @Test
    fun 一覧の見せ方は既定がカードで保存したものを読み出せる() = runTest {
        val dataStore = InMemorySettingsDataStore()
        val repository = createRepository(dataStore)
        assertEquals(BookmarkListDisplay.CARD, repository.bookmarkListDisplay.first())

        repository.setBookmarkListDisplay(BookmarkListDisplay.GRID)
        assertEquals(BookmarkListDisplay.GRID, repository.bookmarkListDisplay.first())
        assertEquals(BookmarkListDisplay.GRID, createRepository(dataStore).bookmarkListDisplay.first())

        // 知らない見せ方(新しい版で増えたもの)はカードとして扱う
        dataStore.edit { it[stringPreferencesKey("bookmark_list_display")] = "MOSAIC" }
        assertEquals(BookmarkListDisplay.CARD, repository.bookmarkListDisplay.first())
    }

    @Test
    fun 読書モードの見た目は既定があり_保存でき_読めない項目だけ既定に戻る() = runTest {
        val dataStore = InMemorySettingsDataStore()
        val repository = createRepository(dataStore)
        assertEquals(ReadingStyle(), repository.readingStyle.first())

        val style = ReadingStyle(ReadingFontSize.XLARGE, ReadingTheme.PAPER, ReadingFont.SERIF)
        repository.setReadingStyle(style)
        assertEquals(style, repository.readingStyle.first())
        assertEquals(style, createRepository(dataStore).readingStyle.first())

        dataStore.edit { it[stringPreferencesKey("reading_theme")] = "SEPIA2" }
        assertEquals(style.copy(theme = ReadingTheme.SYSTEM), repository.readingStyle.first())
    }

    @Test
    fun 検索の履歴を保存して読み出せ_空にすると消える() = runTest {
        val repository = createRepository()
        assertEquals(emptyList<String>(), repository.searchHistory.first())

        repository.updateSearchHistory { listOf("東京 ラーメン", "kotlin") }
        assertEquals(listOf("東京 ラーメン", "kotlin"), repository.searchHistory.first())

        repository.updateSearchHistory { it + "改行\nを含む" }
        assertEquals(listOf("東京 ラーメン", "kotlin", "改行 を含む"), repository.searchHistory.first())

        repository.updateSearchHistory { emptyList() }
        assertEquals(emptyList<String>(), repository.searchHistory.first())
    }

    @Test
    fun チャットのテンプレートを保存して読み出せる() = runTest {
        val repository = createRepository()
        val template = ChatTemplate("1", "会議", "会議の「議題」を\nまとめて")
        assertEquals(emptyList<ChatTemplate>(), repository.chatTemplates.first())

        repository.updateChatTemplates { it + template }

        assertEquals(listOf(template), repository.chatTemplates.first())
    }

    @Test
    fun 読めないテンプレートは空として扱う() {
        assertEquals(emptyList<ChatTemplate>(), ChatTemplateCodec.decode("壊れた値"))
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
    fun AIの既定値は全用途で端末内_Claudeの既定モデル_要約だけHaiku() = runTest {
        val settings = createRepository().aiTaskSettings.first()

        assertEquals(AiTask.entries.toSet(), settings.keys)
        settings.values.forEach {
            assertEquals(LlmBackend.LOCAL, it.backend)
            assertEquals(ApiProvider.CLAUDE, it.apiProvider)
        }
        assertEquals(SUMMARY_DEFAULT_CLAUDE_MODEL, settings.getValue(AiTask.SUMMARY).apiModel)
        assertEquals(ApiProvider.CLAUDE.defaultModel, settings.getValue(AiTask.TAGGING).apiModel)
        assertEquals(ApiProvider.CLAUDE.defaultModel, settings.getValue(AiTask.CHAT).apiModel)
    }

    @Test
    fun 要約の既定モデルはClaudeのときだけHaiku_ほかの提供元は提供元の既定() = runTest {
        val repository = createRepository()
        repository.setApiProvider(AiTask.SUMMARY, ApiProvider.GEMINI)
        assertEquals(ApiProvider.GEMINI.defaultModel, repository.aiTaskSettings.first().getValue(AiTask.SUMMARY).apiModel)

        // 選んだモデルが優先され、空白に戻すと要約の既定(Haiku)に戻る
        repository.setApiProvider(AiTask.SUMMARY, ApiProvider.CLAUDE)
        repository.setApiModel(AiTask.SUMMARY, ApiProvider.CLAUDE, "claude-opus-5-5")
        assertEquals("claude-opus-5-5", repository.aiTaskSettings.first().getValue(AiTask.SUMMARY).apiModel)
        repository.setApiModel(AiTask.SUMMARY, ApiProvider.CLAUDE, " ")
        assertEquals(SUMMARY_DEFAULT_CLAUDE_MODEL, repository.aiTaskSettings.first().getValue(AiTask.SUMMARY).apiModel)
    }

    @Test
    fun AiTaskのdefaultApiModelは要約とClaudeの組み合わせだけHaiku() {
        AiTask.entries.forEach { task ->
            ApiProvider.entries.forEach { provider ->
                val expected = if (task == AiTask.SUMMARY && provider == ApiProvider.CLAUDE) {
                    "claude-haiku-5-5"
                } else {
                    provider.defaultModel
                }
                assertEquals("$task/$provider", expected, task.defaultApiModel(provider))
            }
        }
    }

    @Test
    fun 端末内AIのGPUは既定でオフ_オンにでき_初期化でオフに戻る() = runTest {
        val repository = createRepository()
        assertFalse(repository.localLlmUseGpu.first())

        repository.setLocalLlmUseGpu(true)
        assertTrue(repository.localLlmUseGpu.first())

        repository.resetToDefaults()
        assertFalse(repository.localLlmUseGpu.first())
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
        // クラウドを無効にしている間は、保存した API の設定も端末内として読む(提供元・モデルは保存されたまま)
        assertEquals(LlmBackend.LOCAL, summary.backend)
        assertEquals("gemini-3.5-flash-lite", summary.apiModel)
        assertEquals(AiTaskSetting(), settings.getValue(AiTask.TAGGING).copy(apiModels = emptyMap()))
        assertEquals(LlmBackend.LOCAL, settings.getValue(AiTask.CHAT).backend)
        assertEquals(ApiProvider.GEMINI.defaultModel, settings.getValue(AiTask.CHAT).apiModel)

        // 空白のモデル ID は既定値に戻す
        repository.setApiModel(AiTask.SUMMARY, ApiProvider.GEMINI, " ")
        assertEquals(ApiProvider.GEMINI.defaultModel, repository.aiTaskSettings.first().getValue(AiTask.SUMMARY).apiModel)
    }

    @Test
    fun 端末内モデルは用途ごとに選べ_既定は既定のモデル_nullで戻す() = runTest {
        val repository = createRepository()
        assertEquals(null, repository.aiTaskSettings.first().getValue(AiTask.CHAT).localModelId)

        repository.setLocalModel(AiTask.CHAT, "local_llm_123")
        val settings = repository.aiTaskSettings.first()
        assertEquals("local_llm_123", settings.getValue(AiTask.CHAT).localModelId)
        assertEquals(null, settings.getValue(AiTask.SUMMARY).localModelId)

        repository.setLocalModel(AiTask.CHAT, null)
        assertEquals(null, repository.aiTaskSettings.first().getValue(AiTask.CHAT).localModelId)
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
            assertEquals(LlmBackend.LOCAL, it.backend)
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

    @Test
    fun 要約の言語と書き方は既定が日本語の文章で_書き換えて保存し_読めない値は既定に戻す() = runTest {
        val dataStore = InMemorySettingsDataStore()
        val repository = createRepository(dataStore)
        assertEquals(SummaryStyle(), repository.summaryStyle.first())

        repository.updateSummaryStyle { it.copy(language = SummaryLanguage.ENGLISH) }
        repository.updateSummaryStyle { it.copy(format = SummaryFormat.BULLETS) }

        assertEquals(SummaryStyle(SummaryLanguage.ENGLISH, SummaryFormat.BULLETS), createRepository(dataStore).summaryStyle.first())

        // 新しい版で増えた値などで読めなければ既定に戻す(どちらか一方だけ)
        dataStore.edit { it[stringPreferencesKey("summary_language")] = "KLINGON" }
        assertEquals(SummaryStyle(SummaryLanguage.JAPANESE, SummaryFormat.BULLETS), repository.summaryStyle.first())
    }

    @Test
    fun 初期化すると要約の言語と書き方も既定に戻る() = runTest {
        val repository = createRepository()
        repository.updateSummaryStyle { SummaryStyle(SummaryLanguage.KOREAN, SummaryFormat.ONE_LINE) }

        repository.resetToDefaults()

        assertEquals(SummaryStyle(), repository.summaryStyle.first())
    }
    @Test
    fun 要約の出来の確認は既定でオン_オフにして保存できる() = runTest {
        val repository = createRepository()
        assertTrue(repository.summaryStyle.first().qualityCheck)

        repository.updateSummaryStyle { it.copy(qualityCheck = false) }

        assertFalse(repository.summaryStyle.first().qualityCheck)
        // 言語・書き方には触れない
        assertEquals(SummaryStyle(qualityCheck = false), repository.summaryStyle.first())
        repository.resetToDefaults()
        assertTrue(repository.summaryStyle.first().qualityCheck)
    }

}

private class InMemorySettingsDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    private val mutex = Mutex()
    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        mutex.withLock { transform(state.value).also { state.value = it } }
}
