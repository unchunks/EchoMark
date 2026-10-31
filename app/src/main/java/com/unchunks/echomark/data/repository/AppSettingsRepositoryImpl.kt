package com.unchunks.echomark.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.unchunks.components.llm.ApiProvider
import com.unchunks.echomark.domain.bookmark.duplicates.DuplicatePair
import com.unchunks.echomark.domain.bookmark.duplicates.DuplicateSensitivity
import com.unchunks.echomark.domain.bookmark.model.BookmarkListDisplay
import com.unchunks.echomark.domain.bookmark.model.BookmarkSortOrder
import com.unchunks.echomark.domain.bookmark.model.ReadingFont
import com.unchunks.echomark.domain.bookmark.model.ReadingFontSize
import com.unchunks.echomark.domain.bookmark.model.ReadingStyle
import com.unchunks.echomark.domain.bookmark.model.ReadingTheme
import com.unchunks.echomark.domain.model.ChatTemplate
import com.unchunks.echomark.domain.rediscover.RediscoverSelector
import com.unchunks.echomark.domain.repository.AiTask
import com.unchunks.echomark.domain.repository.AiTaskSetting
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import com.unchunks.echomark.domain.repository.CLOUD_AI_ENABLED
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.domain.model.SummaryFormat
import com.unchunks.echomark.domain.model.SummaryLanguage
import com.unchunks.echomark.domain.model.SummaryStyle
import com.unchunks.echomark.domain.repository.ProcessingSettings
import com.unchunks.echomark.domain.repository.RediscoverSettings
import com.unchunks.echomark.domain.repository.ThemeMode
import com.unchunks.echomark.domain.repository.defaultApiModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.time.DayOfWeek
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppSettingsRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>
) : AppSettingsRepository {

    // 読み込み失敗(ファイル破損など)は既定値で続行する
    private val data: Flow<Preferences> = dataStore.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    // 用途ごとの値が無ければ、用途を分ける前の共通の設定(旧キー)を引き継ぐ
    override val aiTaskSettings: Flow<Map<AiTask, AiTaskSetting>> = data.map { prefs ->
        AiTask.entries.associateWith { task ->
            AiTaskSetting(
                // クラウドを無効にしている間は、保存された値に関係なく端末内で動かす(読む側はここだけを見る)
                backend = if (!CLOUD_AI_ENABLED) LlmBackend.LOCAL else {
                    (prefs[llmBackendKey(task)] ?: prefs[LEGACY_KEY_LLM_BACKEND])
                        ?.let { name -> LlmBackend.entries.firstOrNull { it.name == name } }
                        ?: LlmBackend.LOCAL
                },
                apiProvider = (prefs[apiProviderKey(task)] ?: prefs[LEGACY_KEY_API_PROVIDER])
                    ?.let { name -> ApiProvider.entries.firstOrNull { it.name == name } }
                    ?: ApiProvider.CLAUDE,
                apiModels = ApiProvider.entries.associateWith { provider ->
                    (prefs[apiModelKey(task, provider)] ?: prefs[legacyApiModelKey(provider)])
                        ?.takeIf { it.isNotBlank() } ?: task.defaultApiModel(provider)
                },
                localModelId = prefs[localModelKey(task)]?.takeIf { it.isNotBlank() }
            )
        }
    }

    override val rediscoverSettings: Flow<RediscoverSettings> = data.map { prefs ->
        val defaults = RediscoverSettings()
        RediscoverSettings(
            enabled = prefs[KEY_REDISCOVER_ENABLED] ?: defaults.enabled,
            dayOfWeek = prefs[KEY_REDISCOVER_DAY]
                ?.takeIf { it in 1..7 }?.let { DayOfWeek.of(it) } ?: defaults.dayOfWeek,
            hour = prefs[KEY_REDISCOVER_HOUR]?.coerceIn(0, 23) ?: defaults.hour,
            minute = prefs[KEY_REDISCOVER_MINUTE]?.coerceIn(0, 59) ?: defaults.minute
        )
    }

    override val themeMode: Flow<ThemeMode> = data.map { prefs ->
        prefs[KEY_THEME_MODE]?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
            ?: ThemeMode.SYSTEM
    }

    override val dynamicColor: Flow<Boolean> = data.map { it[KEY_DYNAMIC_COLOR] ?: false }

    override val onboardingCompleted: Flow<Boolean> = data.map { it[KEY_ONBOARDING_COMPLETED] ?: false }

    override val sendFilesToCloud: Flow<Boolean> = data.map { it[KEY_SEND_FILES_TO_CLOUD] ?: true }

    override suspend fun setSendFilesToCloud(enabled: Boolean) {
        dataStore.edit { it[KEY_SEND_FILES_TO_CLOUD] = enabled }
    }

    override val localLlmUseGpu: Flow<Boolean> = data.map { it[KEY_LOCAL_LLM_USE_GPU] ?: false }

    override suspend fun setLocalLlmUseGpu(enabled: Boolean) {
        dataStore.edit { it[KEY_LOCAL_LLM_USE_GPU] = enabled }
    }

    // 検索語は1行で入力するので、改行で区切って持つ
    override val searchHistory: Flow<List<String>> = data.map { prefs ->
        prefs[KEY_SEARCH_HISTORY]?.lines()?.filter { it.isNotBlank() }.orEmpty()
    }.distinctUntilChanged()

    override suspend fun updateSearchHistory(transform: (List<String>) -> List<String>) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_SEARCH_HISTORY]?.lines()?.filter { it.isNotBlank() }.orEmpty()
            val updated = transform(current).map { it.replace('\n', ' ') }.filter { it.isNotBlank() }
            if (updated.isEmpty()) prefs.remove(KEY_SEARCH_HISTORY) else prefs[KEY_SEARCH_HISTORY] = updated.joinToString("\n")
        }
    }

    // 名前の変わった・無くなった厳しさが保存されていたら既定に戻す
    override val duplicateSensitivity: Flow<DuplicateSensitivity> = data.map { prefs ->
        prefs[KEY_DUPLICATE_SENSITIVITY]?.let { name -> DuplicateSensitivity.entries.firstOrNull { it.name == name } }
            ?: DuplicateSensitivity.DEFAULT
    }.distinctUntilChanged()

    override suspend fun setDuplicateSensitivity(sensitivity: DuplicateSensitivity) {
        dataStore.edit { it[KEY_DUPLICATE_SENSITIVITY] = sensitivity.name }
    }

    // 組は「小さい ID:大きい ID」を改行で区切って持つ
    override val dismissedDuplicates: Flow<Set<DuplicatePair>> = data.map { prefs ->
        decodeDismissedDuplicates(prefs[KEY_DISMISSED_DUPLICATES])
    }.distinctUntilChanged()

    override suspend fun updateDismissedDuplicates(transform: (Set<DuplicatePair>) -> Set<DuplicatePair>) {
        dataStore.edit { prefs ->
            val updated = transform(decodeDismissedDuplicates(prefs[KEY_DISMISSED_DUPLICATES]))
            if (updated.isEmpty()) {
                prefs.remove(KEY_DISMISSED_DUPLICATES)
            } else {
                prefs[KEY_DISMISSED_DUPLICATES] = updated
                    .sortedWith(compareBy({ it.firstId }, { it.secondId }))
                    .joinToString("\n") { it.key }
            }
        }
    }

    private fun decodeDismissedDuplicates(text: String?): Set<DuplicatePair> =
        text?.lines()?.mapNotNull { DuplicatePair.parse(it.trim()) }?.toSet().orEmpty()

    override val chatTemplates: Flow<List<ChatTemplate>> = data.map { prefs ->
        prefs[KEY_CHAT_TEMPLATES]?.let(ChatTemplateCodec::decode).orEmpty()
    }.distinctUntilChanged()

    override suspend fun updateChatTemplates(transform: (List<ChatTemplate>) -> List<ChatTemplate>) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_CHAT_TEMPLATES]?.let(ChatTemplateCodec::decode).orEmpty()
            prefs[KEY_CHAT_TEMPLATES] = ChatTemplateCodec.encode(transform(current))
        }
    }

    // 名前の変わった・無くなった並び順が保存されていたら既定に戻す
    override val bookmarkSortOrder: Flow<BookmarkSortOrder> = data.map { prefs ->
        prefs[KEY_BOOKMARK_SORT_ORDER]?.let { name -> BookmarkSortOrder.entries.firstOrNull { it.name == name } }
            ?: BookmarkSortOrder.NEWEST
    }.distinctUntilChanged()

    override suspend fun setBookmarkSortOrder(sortOrder: BookmarkSortOrder) {
        dataStore.edit { it[KEY_BOOKMARK_SORT_ORDER] = sortOrder.name }
    }

    // 名前の変わった・無くなった見せ方が保存されていたら既定(カード)に戻す
    override val bookmarkListDisplay: Flow<BookmarkListDisplay> = data.map { prefs ->
        prefs[KEY_BOOKMARK_LIST_DISPLAY]?.let { name -> BookmarkListDisplay.entries.firstOrNull { it.name == name } }
            ?: BookmarkListDisplay.CARD
    }.distinctUntilChanged()

    override suspend fun setBookmarkListDisplay(display: BookmarkListDisplay) {
        dataStore.edit { it[KEY_BOOKMARK_LIST_DISPLAY] = display.name }
    }

    // 読めない値(新しい版で増えたもの)は、その項目だけ既定に戻す
    override val readingStyle: Flow<ReadingStyle> = data.map { prefs ->
        ReadingStyle(
            fontSize = prefs[KEY_READING_FONT_SIZE]?.let { name -> ReadingFontSize.entries.firstOrNull { it.name == name } }
                ?: ReadingFontSize.MEDIUM,
            theme = prefs[KEY_READING_THEME]?.let { name -> ReadingTheme.entries.firstOrNull { it.name == name } }
                ?: ReadingTheme.SYSTEM,
            font = prefs[KEY_READING_FONT]?.let { name -> ReadingFont.entries.firstOrNull { it.name == name } }
                ?: ReadingFont.SANS
        )
    }.distinctUntilChanged()

    override suspend fun setReadingStyle(style: ReadingStyle) {
        dataStore.edit {
            it[KEY_READING_FONT_SIZE] = style.fontSize.name
            it[KEY_READING_THEME] = style.theme.name
            it[KEY_READING_FONT] = style.font.name
        }
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[KEY_THEME_MODE] = mode.name }
    }

    override suspend fun setDynamicColor(enabled: Boolean) {
        dataStore.edit { it[KEY_DYNAMIC_COLOR] = enabled }
    }

    override suspend fun setOnboardingCompleted(completed: Boolean) {
        dataStore.edit { it[KEY_ONBOARDING_COMPLETED] = completed }
    }

    override suspend fun setLlmBackend(task: AiTask, backend: LlmBackend) {
        dataStore.edit { it[llmBackendKey(task)] = backend.name }
    }

    override suspend fun setApiProvider(task: AiTask, provider: ApiProvider) {
        dataStore.edit { it[apiProviderKey(task)] = provider.name }
    }

    override suspend fun setApiModel(task: AiTask, provider: ApiProvider, modelId: String) {
        val trimmed = modelId.trim()
        val key = apiModelKey(task, provider)
        // 空白なら既定値に戻す(旧キーの値が残っていても引き継がないよう、空文字で上書きする)
        dataStore.edit { it[key] = trimmed }
    }

    override suspend fun setLocalModel(task: AiTask, modelId: String?) {
        dataStore.edit { prefs ->
            if (modelId.isNullOrBlank()) prefs.remove(localModelKey(task)) else prefs[localModelKey(task)] = modelId
        }
    }

    override suspend fun setRediscoverEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_REDISCOVER_ENABLED] = enabled }
    }

    override suspend fun setRediscoverSchedule(dayOfWeek: DayOfWeek, hour: Int, minute: Int) {
        dataStore.edit {
            it[KEY_REDISCOVER_DAY] = dayOfWeek.value
            it[KEY_REDISCOVER_HOUR] = hour.coerceIn(0, 23)
            it[KEY_REDISCOVER_MINUTE] = minute.coerceIn(0, 59)
        }
    }

    override suspend fun getRediscoverNotified(): Map<Long, Long> =
        decodeNotified(data.first()[KEY_REDISCOVER_NOTIFIED].orEmpty())

    override suspend fun recordRediscoverNotified(ids: List<Long>, notifiedAt: Long) {
        dataStore.edit { prefs ->
            val cooldownMs = TimeUnit.DAYS.toMillis(RediscoverSelector.COOLDOWN_DAYS)
            val merged = decodeNotified(prefs[KEY_REDISCOVER_NOTIFIED].orEmpty())
                .filterValues { notifiedAt - it < cooldownMs } + ids.associateWith { notifiedAt }
            prefs[KEY_REDISCOVER_NOTIFIED] = encodeNotified(merged)
        }
    }

    override val processingSettings: Flow<ProcessingSettings> = data.map { it.toProcessingSettings() }

    override suspend fun updateProcessingSettings(transform: (ProcessingSettings) -> ProcessingSettings) {
        dataStore.edit { prefs ->
            val next = transform(prefs.toProcessingSettings())
            prefs[KEY_PROCESSING_CHARGING_ONLY] = next.chargingOnly
            prefs[KEY_PROCESSING_UNMETERED_ONLY] = next.unmeteredOnly
            prefs[KEY_PROCESSING_NIGHTLY] = next.nightlyBatch
            prefs[KEY_PROCESSING_NIGHTLY_HOUR] = next.nightlyHour.coerceIn(0, 23)
            prefs[KEY_PROCESSING_MAX_CONCURRENT] = next.maxConcurrent.coerceIn(1, ProcessingSettings.MAX_CONCURRENT_LIMIT)
            prefs[KEY_PROCESSING_API_RATE] = next.apiRequestsPerMinute.coerceAtLeast(0)
        }
    }

    private fun Preferences.toProcessingSettings() = ProcessingSettings(
        chargingOnly = this[KEY_PROCESSING_CHARGING_ONLY] ?: false,
        unmeteredOnly = this[KEY_PROCESSING_UNMETERED_ONLY] ?: false,
        nightlyBatch = this[KEY_PROCESSING_NIGHTLY] ?: false,
        nightlyHour = this[KEY_PROCESSING_NIGHTLY_HOUR] ?: ProcessingSettings.DEFAULT_NIGHTLY_HOUR,
        maxConcurrent = this[KEY_PROCESSING_MAX_CONCURRENT] ?: ProcessingSettings.DEFAULT_MAX_CONCURRENT,
        apiRequestsPerMinute = this[KEY_PROCESSING_API_RATE] ?: 0
    )

    override val summaryStyle: Flow<SummaryStyle> = data.map { it.toSummaryStyle() }

    override suspend fun updateSummaryStyle(transform: (SummaryStyle) -> SummaryStyle) {
        dataStore.edit { prefs ->
            val next = transform(prefs.toSummaryStyle())
            prefs[KEY_SUMMARY_LANGUAGE] = next.language.name
            prefs[KEY_SUMMARY_FORMAT] = next.format.name
            prefs[KEY_SUMMARY_QUALITY_CHECK] = next.qualityCheck
        }
    }

    // 読めない値(新しい版で増えたもの)は既定に戻す
    private fun Preferences.toSummaryStyle() = SummaryStyle(
        language = SummaryLanguage.entries.firstOrNull { it.name == this[KEY_SUMMARY_LANGUAGE] } ?: SummaryLanguage.DEFAULT,
        format = SummaryFormat.entries.firstOrNull { it.name == this[KEY_SUMMARY_FORMAT] } ?: SummaryFormat.DEFAULT,
        qualityCheck = this[KEY_SUMMARY_QUALITY_CHECK] ?: true
    )

    override suspend fun resetToDefaults() {
        dataStore.edit { prefs ->
            val onboardingCompleted = prefs[KEY_ONBOARDING_COMPLETED]
            prefs.clear()
            if (onboardingCompleted != null) prefs[KEY_ONBOARDING_COMPLETED] = onboardingCompleted
        }
    }

    private companion object {
        fun llmBackendKey(task: AiTask) = stringPreferencesKey("llm_backend_${task.name.lowercase()}")
        fun apiProviderKey(task: AiTask) = stringPreferencesKey("api_provider_${task.name.lowercase()}")
        fun localModelKey(task: AiTask) = stringPreferencesKey("local_model_${task.name.lowercase()}")
        fun apiModelKey(task: AiTask, provider: ApiProvider) =
            stringPreferencesKey("api_model_${task.name.lowercase()}_${provider.name.lowercase()}")

        /** 用途を分ける前の、全用途で共通の設定。読むだけで書かない(用途ごとの値が無いときに引き継ぐ) */
        val LEGACY_KEY_LLM_BACKEND = stringPreferencesKey("llm_backend")
        val LEGACY_KEY_API_PROVIDER = stringPreferencesKey("api_provider")
        fun legacyApiModelKey(provider: ApiProvider) = stringPreferencesKey("api_model_${provider.name.lowercase()}")
        val KEY_REDISCOVER_ENABLED = booleanPreferencesKey("rediscover_enabled")
        val KEY_REDISCOVER_DAY = intPreferencesKey("rediscover_day_of_week")
        val KEY_REDISCOVER_HOUR = intPreferencesKey("rediscover_hour")
        val KEY_REDISCOVER_MINUTE = intPreferencesKey("rediscover_minute")
        val KEY_REDISCOVER_NOTIFIED = stringSetPreferencesKey("rediscover_notified")
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val KEY_SEND_FILES_TO_CLOUD = booleanPreferencesKey("send_files_to_cloud")
        val KEY_LOCAL_LLM_USE_GPU = booleanPreferencesKey("local_llm_use_gpu")
        val KEY_BOOKMARK_SORT_ORDER = stringPreferencesKey("bookmark_sort_order")
        val KEY_CHAT_TEMPLATES = stringPreferencesKey("chat_templates")
        val KEY_SEARCH_HISTORY = stringPreferencesKey("search_history")
        val KEY_DUPLICATE_SENSITIVITY = stringPreferencesKey("duplicate_sensitivity")
        val KEY_DISMISSED_DUPLICATES = stringPreferencesKey("dismissed_duplicates")
        val KEY_BOOKMARK_LIST_DISPLAY = stringPreferencesKey("bookmark_list_display")
        val KEY_READING_FONT_SIZE = stringPreferencesKey("reading_font_size")
        val KEY_READING_THEME = stringPreferencesKey("reading_theme")
        val KEY_READING_FONT = stringPreferencesKey("reading_font")
        val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val KEY_PROCESSING_CHARGING_ONLY = booleanPreferencesKey("processing_charging_only")
        val KEY_PROCESSING_UNMETERED_ONLY = booleanPreferencesKey("processing_unmetered_only")
        val KEY_PROCESSING_NIGHTLY = booleanPreferencesKey("processing_nightly_batch")
        val KEY_PROCESSING_NIGHTLY_HOUR = intPreferencesKey("processing_nightly_hour")
        val KEY_PROCESSING_MAX_CONCURRENT = intPreferencesKey("processing_max_concurrent")
        val KEY_PROCESSING_API_RATE = intPreferencesKey("processing_api_requests_per_minute")
        val KEY_SUMMARY_LANGUAGE = stringPreferencesKey("summary_language")
        val KEY_SUMMARY_FORMAT = stringPreferencesKey("summary_format")
        val KEY_SUMMARY_QUALITY_CHECK = booleanPreferencesKey("summary_quality_check")
    }
}

/** "id:epochMillis" 形式の文字列集合に変換する。 */
internal fun encodeNotified(map: Map<Long, Long>): Set<String> =
    map.map { (id, at) -> "$id:$at" }.toSet()

/** [encodeNotified] の逆変換。壊れた要素は無視する。 */
internal fun decodeNotified(entries: Set<String>): Map<Long, Long> =
    entries.mapNotNull { entry ->
        val parts = entry.split(':')
        val id = parts.getOrNull(0)?.toLongOrNull()
        val at = parts.getOrNull(1)?.toLongOrNull()
        if (parts.size == 2 && id != null && at != null) id to at else null
    }.toMap()
