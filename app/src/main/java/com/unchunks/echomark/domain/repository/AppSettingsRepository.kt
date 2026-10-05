package com.unchunks.echomark.domain.repository

import com.unchunks.echomark.domain.provider.ApiProvider
import kotlinx.coroutines.flow.Flow
import java.time.DayOfWeek

/** AI の実行場所。 */
enum class LlmBackend { LOCAL, API }

/** 画面の明暗。SYSTEM は端末の設定に合わせる。 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

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

    /** テーマ(明暗)。既定は端末の設定に合わせる。 */
    val themeMode: Flow<ThemeMode>

    /** 壁紙の色を使うダイナミックカラー(Android 12+)。既定はブランド配色(false)。 */
    val dynamicColor: Flow<Boolean>

    /** 初回オンボーディングを終えた(スキップを含む)か。 */
    val onboardingCompleted: Flow<Boolean>

    /**
     * クラウド API 選択時に、保存したファイル(画像・PDF・音声・動画)そのものを提供元へ送って解析してよいか。
     * false なら端末内で取り出したテキスト(OCR・文字起こしなど)だけを送る。既定は true。
     */
    val sendFilesToCloud: Flow<Boolean>

    /** クラウド API 選択時に使う提供元。既定は Claude。 */
    val apiProvider: Flow<ApiProvider>

    /** 提供元ごとのモデル ID(未設定なら [ApiProvider.defaultModel])。API キーは [ApiKeyRepository] 側で管理する。 */
    val apiModels: Flow<Map<ApiProvider, String>>

    suspend fun setLlmBackend(backend: LlmBackend)
    suspend fun setApiProvider(provider: ApiProvider)

    /** モデル ID を保存する。空白なら既定値に戻す。 */
    suspend fun setApiModel(provider: ApiProvider, modelId: String)
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setDynamicColor(enabled: Boolean)
    suspend fun setOnboardingCompleted(completed: Boolean)
    suspend fun setSendFilesToCloud(enabled: Boolean)
    suspend fun setRediscoverEnabled(enabled: Boolean)
    suspend fun setRediscoverSchedule(dayOfWeek: DayOfWeek, hour: Int, minute: Int)

    /** 再発見通知に出したブックマークの bookmarkId -> 通知時刻(epoch millis)。 */
    suspend fun getRediscoverNotified(): Map<Long, Long>

    /** 通知したブックマークを記録する。クールダウンを過ぎた古い記録はここで掃除する。 */
    suspend fun recordRediscoverNotified(ids: List<Long>, notifiedAt: Long)

    /**
     * 設定を既定値に戻す(全データ削除で「設定も初期化」を選んだとき)。
     * オンボーディング済みの記録だけは残す(削除直後に案内が再表示されないように)。
     */
    suspend fun resetToDefaults()
}
