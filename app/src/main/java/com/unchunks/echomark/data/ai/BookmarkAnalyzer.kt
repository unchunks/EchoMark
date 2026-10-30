package com.unchunks.echomark.data.ai

import com.unchunks.echomark.domain.bookmark.model.ContentKind
import com.unchunks.echomark.domain.model.AnalysisInput
import com.unchunks.echomark.domain.model.AnalysisScope
import com.unchunks.echomark.domain.model.BookmarkAnalysis
import com.unchunks.echomark.domain.provider.LlmProvider
import com.unchunks.echomark.domain.repository.AiTask
import com.unchunks.echomark.domain.repository.AiTaskSetting
import com.unchunks.echomark.domain.repository.AppSettingsRepository
import com.unchunks.echomark.domain.repository.LlmBackend
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * ブックマークの要約・タグ・カテゴリを、用途ごとの AI の設定に従って作る。
 * タグ付けと要約が同じ AI なら1回の推論にまとめ、違えば要約は「要約」の設定で、タグとカテゴリは「タグ付け」の設定で別々に作る。
 * 別々に作るとき、両方がクラウド API なら同時に呼ぶ(待ち時間が長い方だけで済む)。
 * 端末内の推論が混ざるときは、端末内を先に1つずつ進める。
 */
class BookmarkAnalyzer @Inject constructor(
    private val resolver: LlmProviderResolver,
    private val appSettings: AppSettingsRepository
) {
    /**
     * 推論を始める前に両方の用途の準備(API キー)を確かめる。どちらかが失敗したら全体を失敗にし、
     * 要約だけ・タグだけが保存されることはない(再処理で両方を作り直す)。
     * 動画の本文は [VideoAnalysisText] で整えてから渡す。
     * 要約からは、書き出しに写されたタイトル・チャンネル名・「この動画では」のような前置きを除く([SummaryCleaner])。
     * [scope] が [AnalysisScope.SUMMARY] なら「要約」の設定の AI で要約だけを、[AnalysisScope.TAGS] なら「タグ付け」の
     * 設定の AI でタグとカテゴリだけを作る(要約だけ・タグだけの再処理。もう片方の結果は使わない)。
     * 例外は [LlmProvider.analyze] と同じ。
     */
    suspend fun analyze(
        input: AnalysisInput,
        existingTags: List<String>,
        scope: AnalysisScope = AnalysisScope.ALL
    ): BookmarkAnalysis {
        // 動画の本文は、字幕の効果音・つなぎ言葉や説明文の宣伝を除いてから渡す(同じ長さに入る話の量を増やす)
        val withText = if (input.kind == ContentKind.VIDEO) input.copy(text = VideoAnalysisText.prepare(input.text)) else input
        // 要約の言語と書き方は設定から(呼び出しのたびに読むため、変えたら次の処理から反映される)
        val prepared = withText.copy(style = appSettings.summaryStyle.first())
        val analysis = when (scope) {
            AnalysisScope.ALL -> analyzeWithTaskSettings(prepared, existingTags)
            AnalysisScope.SUMMARY -> resolver.resolve(AiTask.SUMMARY).analyze(prepared, existingTags, AnalysisScope.SUMMARY)
            AnalysisScope.TAGS -> resolver.resolve(AiTask.TAGGING).analyze(prepared, existingTags, AnalysisScope.TAGS)
        }
        val summary = SummaryCleaner.clean(analysis.summary, input.title, SummaryCleaner.channelOf(input.text))
        return analysis.copy(summary = summary)
    }

    private suspend fun analyzeWithTaskSettings(input: AnalysisInput, existingTags: List<String>): BookmarkAnalysis {
        val settings = appSettings.aiTaskSettings.first()
        val summary = settings[AiTask.SUMMARY] ?: AiTaskSetting()
        val tagging = settings[AiTask.TAGGING] ?: AiTaskSetting()
        if (summary.sameEngineAs(tagging)) return resolver.resolve(AiTask.SUMMARY).analyze(input, existingTags)

        val summarizer = resolver.resolve(AiTask.SUMMARY)
        val tagger = resolver.resolve(AiTask.TAGGING)
        // 両方がクラウド API なら同時に呼ぶ。どちらかが失敗したらもう片方はキャンセルする
        if (summary.backend == LlmBackend.API && tagging.backend == LlmBackend.API) {
            return coroutineScope {
                val summarizeJob = async { summarizer.analyze(input, existingTags, AnalysisScope.SUMMARY) }
                val tagJob = async { tagger.analyze(input, existingTags, AnalysisScope.TAGS) }
                val summarized = summarizeJob.await()
                val tagged = tagJob.await()
                BookmarkAnalysis(summary = summarized.summary, tags = tagged.tags, category = tagged.category)
            }
        }
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
