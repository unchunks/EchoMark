package com.unchunks.echomark.domain.repository

import com.unchunks.echomark.domain.provider.ApiProvider
import kotlinx.coroutines.flow.Flow
import java.time.DayOfWeek

/** AI の実行場所。 */
enum class LlmBackend { LOCAL, API }

/** 再発見ダイジェスト通知の設定。 */
data class RediscoverSettings(
    val enabled: Boolean = false,
    val dayOfWeek: DayOfWeek = DayOfWeek.SUNDAY,
    val hour: Int = 20,
    val minute: Int = 0
)

/** アプリ設定(DataStore)。 */
interface AppSettingsRepository {
    val llmBackend: Flow<LlmBackend>
    val rediscoverSettings: Flow<RediscoverSettings>

    /** クラウド API 選択時に使う提供元。既定は Claude。 */
    val apiProvider: Flow<ApiProvider>

    /** 提供元ごとのモデル ID(未設定なら [ApiProvider.defaultModel])。API キーは [ApiKeyRepository] 側で管理する。 */
    val apiModels: Flow<Map<ApiProvider, String>>

    suspend fun setLlmBackend(backend: LlmBackend)
    suspend fun setApiProvider(provider: ApiProvider)

    /** モデル ID を保存する。空白なら既定値に戻す。 */
    suspend fun setApiModel(provider: ApiProvider, modelId: String)
    suspend fun setRediscoverEnabled(enabled: Boolean)
    suspend fun setRediscoverSchedule(dayOfWeek: DayOfWeek, hour: Int, minute: Int)

    /** 再発見通知に出したブックマークの bookmarkId -> 通知時刻(epoch millis)。 */
    suspend fun getRediscoverNotified(): Map<Long, Long>

    /** 通知したブックマークを記録する。クールダウンを過ぎた古い記録はここで掃除する。 */
    suspend fun recordRediscoverNotified(ids: List<Long>, notifiedAt: Long)
}
