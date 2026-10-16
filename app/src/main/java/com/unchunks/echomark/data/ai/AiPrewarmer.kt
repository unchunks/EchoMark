package com.unchunks.echomark.data.ai

import com.unchunks.echomark.di.qualifier.LocalAi
import com.unchunks.echomark.domain.provider.LlmProvider
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
 */
class AiPrewarmer @Inject constructor(
    @param:LocalAi private val localProvider: LlmProvider,
    private val appSettings: AppSettingsRepository
) {
    /** ブックマークの AI 処理(要約・タグ付け)に端末内 AI を使う設定か。読めなければ false。 */
    suspend fun usesLocalLlm(): Boolean = try {
        usesLocalLlmForAnalysis(appSettings.aiTaskSettings.first())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "AI の設定を読めないため、端末内モデルの先読みをしない")
        false
    }

    /** 端末内 AI を使う設定なら読み込みを始める(完了は待たない)。始めたら true。 */
    suspend fun prewarmLocalLlmIfUsed(): Boolean {
        if (!usesLocalLlm()) return false
        localProvider.prewarm()
        return true
    }
}

/**
 * ブックマークの AI 処理で端末内 AI を使うか。タグ付け・要約のどちらかが端末内なら使う
 * (別々の AI を選んでいても、端末内の側の推論は走る)。設定が無い用途は既定(端末内)として扱う。
 */
internal fun usesLocalLlmForAnalysis(settings: Map<AiTask, AiTaskSetting>): Boolean =
    listOf(AiTask.TAGGING, AiTask.SUMMARY).any { (settings[it] ?: AiTaskSetting()).backend == LlmBackend.LOCAL }
