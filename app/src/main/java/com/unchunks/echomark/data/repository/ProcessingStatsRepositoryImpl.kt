package com.unchunks.echomark.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import com.unchunks.echomark.di.qualifier.ProcessingStatsStore
import com.unchunks.echomark.domain.bookmark.model.ContentKind
import com.unchunks.echomark.domain.bookmark.model.ProcessingStage
import com.unchunks.echomark.domain.bookmark.model.StageDurationKey
import com.unchunks.echomark.domain.repository.ProcessingStatsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong

/**
 * 段ごとの所要時間を、最近の記録を重く見た平均(指数移動平均)で DataStore に持つ。
 * キーは「段:中身の種類」(例: "avg_ms:ANALYZE:WEB_PAGE")。読めないキーは無視する。
 */
@Singleton
class ProcessingStatsRepositoryImpl @Inject constructor(
    @param:ProcessingStatsStore private val dataStore: DataStore<Preferences>
) : ProcessingStatsRepository {

    override val averageStageDurations: Flow<Map<StageDurationKey, Long>> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            prefs.asMap().mapNotNull { (key, value) ->
                val parsed = parseKey(key.name) ?: return@mapNotNull null
                (value as? Long)?.let { parsed to it }
            }.toMap()
        }

    override suspend fun recordStageDuration(key: StageDurationKey, millis: Long) {
        if (millis < 0) return
        val prefKey = longPreferencesKey(keyName(key))
        dataStore.edit { prefs ->
            val previous = prefs[prefKey]
            prefs[prefKey] = if (previous == null) millis else (previous * (1 - WEIGHT) + millis * WEIGHT).roundToLong()
        }
    }

    internal companion object {
        /** 新しい記録の重み。数回で端末・AI の変化に追いつき、1回の外れ値には引きずられすぎない */
        const val WEIGHT = 0.3
        private const val PREFIX = "avg_ms:"

        fun keyName(key: StageDurationKey) = "$PREFIX${key.stage.name}:${key.kind.name}"

        fun parseKey(name: String): StageDurationKey? {
            if (!name.startsWith(PREFIX)) return null
            val (stage, kind) = name.removePrefix(PREFIX).split(':').takeIf { it.size == 2 } ?: return null
            return StageDurationKey(
                ProcessingStage.entries.firstOrNull { it.name == stage } ?: return null,
                ContentKind.entries.firstOrNull { it.name == kind } ?: return null
            )
        }
    }
}
