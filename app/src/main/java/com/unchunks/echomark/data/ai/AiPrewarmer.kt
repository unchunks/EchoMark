package com.unchunks.echomark.data.ai

import com.unchunks.echomark.domain.model.AnalysisScope
import com.unchunks.echomark.domain.repository.AiTask
import com.unchunks.echomark.domain.repository.AiTaskSetting
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import com.unchunks.echomark.domain.repository.LlmBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject

/**
 * AI 処理の前段(本文取得・中身の取り出し・AI 処理の開始)で、端末内モデルの読み込み(数秒〜十数秒)を先に始めておく。
 * 読み込みはバックグラウンドで進み、本文取得などと重なるため、AI 処理が始まったときには読み込み済みのことが多い。
 * 要約・タグ付けのどちらにも端末内 AI を使わない設定なら何もしない。
 * 用途ごとに別の端末内モデルを選んでいるときは、最初に動くモデル([analysisLocalModelToPrewarm])だけを読み込む
 * (同時に読み込むモデルは1つのため)。
 */
class AiPrewarmer @Inject constructor(
    private val localProviders: LocalLlmProviders,
    private val appSettings: AppSettingsRepository
) {

    /** ブックマークの AI 処理の [scope] の範囲(要約・タグ付け)に端末内 AI を使う設定か。読めなければ false。 */
    suspend fun usesLocalLlm(scope: AnalysisScope = AnalysisScope.ALL): Boolean =
        readSettings()?.let { usesLocalLlmForAnalysis(it, scope) } ?: false

    /** [scope] の範囲に端末内 AI を使う設定なら読み込みを始める(完了は待たない)。始めたら true。 */
    suspend fun prewarmLocalLlmIfUsed(scope: AnalysisScope = AnalysisScope.ALL): Boolean {
        val settings = readSettings() ?: return false
        if (!usesLocalLlmForAnalysis(settings, scope)) return false
        localProviders.forModel(analysisLocalModelToPrewarm(settings, scope)).prewarm()
        return true
    }

    private suspend fun readSettings(): Map<AiTask, AiTaskSetting>? = try {
        appSettings.aiTaskSettings.first()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "AI の設定を読めないため、端末内モデルの先読みをしない")
        null
    }
}

/**
 * [scope] の範囲で動く用途の、端末内で動かすものの設定を、動く順に返す。BookmarkAnalyzer は端末内の推論を先にし、
 * タグ付けと要約が別々の端末内モデルならタグ付けから動かす。要約だけ・タグだけの再処理ではその用途だけが動く。
 * 設定が無い用途は既定(端末内)として扱う。
 */
private fun localAnalysisSettings(settings: Map<AiTask, AiTaskSetting>, scope: AnalysisScope): List<AiTaskSetting> =
    when (scope) {
        AnalysisScope.ALL -> listOf(AiTask.TAGGING, AiTask.SUMMARY)
        AnalysisScope.SUMMARY -> listOf(AiTask.SUMMARY)
        AnalysisScope.TAGS -> listOf(AiTask.TAGGING)
    }.map { settings[it] ?: AiTaskSetting() }.filter { it.backend == LlmBackend.LOCAL }

/**
 * ブックマークの AI 処理の [scope] の範囲で端末内 AI を使うか。タグ付け・要約のどちらかが端末内なら使う
 * (別々の AI を選んでいても、端末内の側の推論は走る)。
 */
internal fun usesLocalLlmForAnalysis(settings: Map<AiTask, AiTaskSetting>, scope: AnalysisScope = AnalysisScope.ALL): Boolean =
    localAnalysisSettings(settings, scope).isNotEmpty()

/** ブックマークの AI 処理の [scope] の範囲で最初に動く端末内モデルの id(null は既定のモデル)。 */
internal fun analysisLocalModelToPrewarm(settings: Map<AiTask, AiTaskSetting>, scope: AnalysisScope = AnalysisScope.ALL): String? =
    localAnalysisSettings(settings, scope).firstOrNull()?.localModelId
