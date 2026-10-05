package com.unchunks.echomark.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.unchunks.echomark.domain.provider.ApiProvider
import com.unchunks.echomark.domain.rediscover.RediscoverSelector
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import com.unchunks.echomark.domain.repository.LlmBackend
import com.unchunks.echomark.domain.repository.RediscoverSettings
import com.unchunks.echomark.domain.repository.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
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

    override val llmBackend: Flow<LlmBackend> = data.map { prefs ->
        prefs[KEY_LLM_BACKEND]?.let { name -> LlmBackend.entries.firstOrNull { it.name == name } }
            ?: LlmBackend.LOCAL
    }

    override val apiProvider: Flow<ApiProvider> = data.map { prefs ->
        prefs[KEY_API_PROVIDER]?.let { name -> ApiProvider.entries.firstOrNull { it.name == name } }
            ?: ApiProvider.CLAUDE
    }

    override val apiModels: Flow<Map<ApiProvider, String>> = data.map { prefs ->
        ApiProvider.entries.associateWith { provider ->
            prefs[apiModelKey(provider)]?.takeIf { it.isNotBlank() } ?: provider.defaultModel
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

    override suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[KEY_THEME_MODE] = mode.name }
    }

    override suspend fun setDynamicColor(enabled: Boolean) {
        dataStore.edit { it[KEY_DYNAMIC_COLOR] = enabled }
    }

    override suspend fun setOnboardingCompleted(completed: Boolean) {
        dataStore.edit { it[KEY_ONBOARDING_COMPLETED] = completed }
    }

    override suspend fun setLlmBackend(backend: LlmBackend) {
        dataStore.edit { it[KEY_LLM_BACKEND] = backend.name }
    }

    override suspend fun setApiProvider(provider: ApiProvider) {
        dataStore.edit { it[KEY_API_PROVIDER] = provider.name }
    }

    override suspend fun setApiModel(provider: ApiProvider, modelId: String) {
        val trimmed = modelId.trim()
        dataStore.edit {
            if (trimmed.isEmpty()) it.remove(apiModelKey(provider)) else it[apiModelKey(provider)] = trimmed
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

    override suspend fun resetToDefaults() {
        dataStore.edit { prefs ->
            val onboardingCompleted = prefs[KEY_ONBOARDING_COMPLETED]
            prefs.clear()
            if (onboardingCompleted != null) prefs[KEY_ONBOARDING_COMPLETED] = onboardingCompleted
        }
    }

    private companion object {
        val KEY_LLM_BACKEND = stringPreferencesKey("llm_backend")
        val KEY_API_PROVIDER = stringPreferencesKey("api_provider")
        fun apiModelKey(provider: ApiProvider) = stringPreferencesKey("api_model_${provider.name.lowercase()}")
        val KEY_REDISCOVER_ENABLED = booleanPreferencesKey("rediscover_enabled")
        val KEY_REDISCOVER_DAY = intPreferencesKey("rediscover_day_of_week")
        val KEY_REDISCOVER_HOUR = intPreferencesKey("rediscover_hour")
        val KEY_REDISCOVER_MINUTE = intPreferencesKey("rediscover_minute")
        val KEY_REDISCOVER_NOTIFIED = stringSetPreferencesKey("rediscover_notified")
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val KEY_SEND_FILES_TO_CLOUD = booleanPreferencesKey("send_files_to_cloud")
        val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
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
