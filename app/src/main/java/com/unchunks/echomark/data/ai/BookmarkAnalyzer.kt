package com.unchunks.echomark.data.ai

import com.unchunks.echomark.domain.model.AnalysisInput
import com.unchunks.echomark.domain.model.AnalysisScope
import com.unchunks.echomark.domain.model.BookmarkAnalysis
import com.unchunks.echomark.domain.provider.LlmProvider
import com.unchunks.echomark.domain.repository.AiTask
import com.unchunks.echomark.domain.repository.AiTaskSetting
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import com.unchunks.echomark.domain.repository.LlmBackend
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * ブックマークの要約・タグ・カテゴリを、用途ごとの AI の設定に従って作る。
 * タグ付けと要約が同じ AI なら1回の推論にまとめ、違えば要約は「要約」の設定で、タグとカテゴリは「タグ付け」の設定で別々に作る。
 */
class BookmarkAnalyzer @Inject constructor(
    private val resolver: LlmProviderResolver,
    private val appSettings: AppSettingsRepository
) {
    /**
     * 推論を始める前に両方の用途の準備(API キー)を確かめる。どちらかが失敗したら全体を失敗にし、
     * 要約だけ・タグだけが保存されることはない(再処理で両方を作り直す)。
     * 例外は [LlmProvider.analyze] と同じ。
     */
    suspend fun analyze(input: AnalysisInput, existingTags: List<String>): BookmarkAnalysis {
        val settings = appSettings.aiTaskSettings.first()
        val summary = settings[AiTask.SUMMARY] ?: AiTaskSetting()
        val tagging = settings[AiTask.TAGGING] ?: AiTaskSetting()
        if (summary.sameEngineAs(tagging)) return resolver.resolve(AiTask.SUMMARY).analyze(input, existingTags)

        val summarizer = resolver.resolve(AiTask.SUMMARY)
        val tagger = resolver.resolve(AiTask.TAGGING)
        // 端末内の推論を先にする。モデル未取り込み・ファイルを読めないなどで失敗しても、
        // クラウド API の呼び出し(料金)を無駄にしない(片方の結果だけでは保存しないため)
        return if (tagging.backend == LlmBackend.LOCAL) {
            val tagged = tagger.analyze(input, existingTags, AnalysisScope.TAGS)
            val summarized = summarizer.analyze(input, existingTags, AnalysisScope.SUMMARY)
            BookmarkAnalysis(summary = summarized.summary, tags = tagged.tags, category = tagged.category)
        } else {
            val summarized = summarizer.analyze(input, existingTags, AnalysisScope.SUMMARY)
            val tagged = tagger.analyze(input, existingTags, AnalysisScope.TAGS)
            BookmarkAnalysis(summary = summarized.summary, tags = tagged.tags, category = tagged.category)
        }
    }
}
