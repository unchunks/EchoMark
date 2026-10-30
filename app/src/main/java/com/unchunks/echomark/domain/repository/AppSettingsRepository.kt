package com.unchunks.echomark.domain.repository

import com.unchunks.components.llm.ApiProvider
import com.unchunks.echomark.domain.bookmark.model.BookmarkSortOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.DayOfWeek

/** AI の実行場所。 */
enum class LlmBackend { LOCAL, API }

/** AI を使う用途。用途ごとに実行場所・提供元・モデルを選べる。 */
enum class AiTask(val label: String) {
    TAGGING("タグ付け"),
    SUMMARY("要約"),
    CHAT("チャット")
}

/** 要約に Claude を使うときの既定モデル。要約は件数が多く待ち時間が目立つため、速さを優先する */
const val SUMMARY_DEFAULT_CLAUDE_MODEL = "claude-haiku-5-5"

/**
 * この用途で [provider] を選んだときの既定モデル。モデルを選んでいない(または空白に戻した)ときに使う。
 * 要約で Claude を使うときだけ [SUMMARY_DEFAULT_CLAUDE_MODEL]、それ以外は提供元の既定([ApiProvider.defaultModel])。
 */
fun AiTask.defaultApiModel(provider: ApiProvider): String =
    if (this == AiTask.SUMMARY && provider == ApiProvider.CLAUDE) SUMMARY_DEFAULT_CLAUDE_MODEL else provider.defaultModel

/**
 * クラウド API の経路を使うか。false の間は、保存された設定に関係なく全用途を端末内 AI で動かす。
 * クラウドのコード(API クライアント・設定画面)は残してあり、true に戻せば元の挙動になる。
 */
const val CLOUD_AI_ENABLED = false

/**
 * 1つの用途の AI の設定。
 * @property apiModels 提供元ごとのモデル ID(提供元を切り替えても、提供元ごとの選択を覚えておく)
 * @property localModelId 端末内で動かすときのモデル(取り込んだモデルの id)。null なら既定のモデル。
 *   選んだモデルを削除したときも既定のモデルを使う
 */
data class AiTaskSetting(
    val backend: LlmBackend = LlmBackend.LOCAL,
    val apiProvider: ApiProvider = ApiProvider.CLAUDE,
    val apiModels: Map<ApiProvider, String> = emptyMap(),
    val localModelId: String? = null
) {
    /** 選択中の提供元で使うモデル ID */
    val apiModel: String get() = apiModels[apiProvider]?.takeIf { it.isNotBlank() } ?: apiProvider.defaultModel

    /**
     * 同じ AI(実行場所が同じで、クラウドなら提供元とモデル、端末内なら選んだモデルも同じ)で動くか。同じなら1回の推論にまとめられる。
     * 端末内のモデルは選んだ id で比べる(片方が「既定」で、もう片方がたまたま既定と同じモデルを選んでいるときは、別々に推論する)
     */
    fun sameEngineAs(other: AiTaskSetting): Boolean =
        backend == other.backend &&
            when (backend) {
                LlmBackend.LOCAL -> localModelId == other.localModelId
                LlmBackend.API -> apiProvider == other.apiProvider && apiModel == other.apiModel
            }
}

/** 画面の明暗。SYSTEM は端末の設定に合わせる。 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** 再発見ダイジェスト通知の設定。 */
data class RediscoverSettings(
    val enabled: Boolean = false,
    val dayOfWeek: DayOfWeek = DayOfWeek.SUNDAY,
    val hour: Int = 20,
    val minute: Int = 0
)

/**
 * ブックマークの処理(本文の取得・中身の取り出し・AI 処理)をいつ・どれだけ動かすか。
 * 端末への負担(電池・通信量・発熱)と、クラウド API の使いすぎを抑えるための設定。
 */
data class ProcessingSettings(
    /** 充電中だけ、中身の取り出し(文字起こしなど)と AI 処理を動かす(#8) */
    val chargingOnly: Boolean = false,
    /** Wi-Fi など従量課金でない接続のときだけ、本文の取得とクラウド API の処理を動かす */
    val unmeteredOnly: Boolean = false,
    /** 保存したもの・まとめての再処理を、すぐに処理せず夜間にまとめて処理する(#9) */
    val nightlyBatch: Boolean = false,
    /** 夜間の処理を始める時刻(時) */
    val nightlyHour: Int = DEFAULT_NIGHTLY_HOUR,
    /** 同時に動かす、中身の取り出し・AI 処理の数(#70) */
    val maxConcurrent: Int = DEFAULT_MAX_CONCURRENT,
    /** 処理でクラウド API を呼ぶ回数の上限(1 分あたり)。0 なら制限しない(#70) */
    val apiRequestsPerMinute: Int = 0
) {
    companion object {
        const val DEFAULT_NIGHTLY_HOUR = 2
        const val DEFAULT_MAX_CONCURRENT = 2
        const val MAX_CONCURRENT_LIMIT = 4
        /** 設定画面で選べる、クラウド API の呼び出しの上限(1 分あたり、0 は制限なし) */
        val API_RATE_CHOICES = listOf(0, 5, 10, 20, 60)
    }
}

/** アプリ設定(DataStore)。 */
interface AppSettingsRepository {
    /** 処理をいつ・どれだけ動かすか */
    val processingSettings: Flow<ProcessingSettings>

    /** 処理の設定を書き換える(値は範囲に収めて保存する) */
    suspend fun updateProcessingSettings(transform: (ProcessingSettings) -> ProcessingSettings)

    /** 用途ごとの AI の設定(全用途がそろったマップ)。API キーは [ApiKeyRepository] 側で管理する。 */
    val aiTaskSettings: Flow<Map<AiTask, AiTaskSetting>>
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

    /**
     * 端末内 AI(LLM)を GPU で動かしてよいか(実験的)。速くなるが、端末・モデルによっては GPU のドライバの中で落ちて
     * アプリごと終了することがあるため、既定は false(CPU)。オンでも GPU で落ちたモデルは自動で CPU に戻す。
     */
    val localLlmUseGpu: Flow<Boolean>

    /** 一覧の並び順。アプリを開き直しても前回選んだ順で出す。既定は新しい順。 */
    val bookmarkSortOrder: Flow<BookmarkSortOrder>

    suspend fun setLlmBackend(task: AiTask, backend: LlmBackend)
    suspend fun setApiProvider(task: AiTask, provider: ApiProvider)

    /** [task] で [provider] を使うときのモデル ID を保存する。空白なら既定値に戻す。 */
    suspend fun setApiModel(task: AiTask, provider: ApiProvider, modelId: String)

    /** [task] を端末内で動かすときのモデル([AiTaskSetting.localModelId])を保存する。null なら既定のモデルに戻す。 */
    suspend fun setLocalModel(task: AiTask, modelId: String?)
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setDynamicColor(enabled: Boolean)
    suspend fun setOnboardingCompleted(completed: Boolean)
    suspend fun setSendFilesToCloud(enabled: Boolean)
    suspend fun setLocalLlmUseGpu(enabled: Boolean)
    suspend fun setBookmarkSortOrder(sortOrder: BookmarkSortOrder)
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

/** 1つの用途の AI の設定。 */
fun AppSettingsRepository.aiTaskSetting(task: AiTask): Flow<AiTaskSetting> =
    aiTaskSettings.map { it[task] ?: AiTaskSetting() }
