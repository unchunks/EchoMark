package com.unchunks.echomark.data.podcast

import com.unchunks.components.speechtext.ReadingEntry
import com.unchunks.echomark.data.ai.LlmProviderResolver
import com.unchunks.echomark.data.ai.StructuredOutput
import com.unchunks.echomark.domain.chat.ChatContextLimits
import com.unchunks.echomark.domain.podcast.PodcastFormat
import com.unchunks.echomark.domain.podcast.PodcastScript
import com.unchunks.echomark.domain.podcast.PodcastSource
import com.unchunks.echomark.domain.podcast.PodcastTemplates
import com.unchunks.echomark.domain.podcast.ScriptGenerator
import com.unchunks.echomark.domain.podcast.ScriptLine
import com.unchunks.echomark.domain.provider.LlmProvider
import com.unchunks.echomark.domain.repository.AiTask
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import javax.inject.Inject

/** 台本を書く進み具合(画面・通知に出す)。 */
sealed interface ScriptProgress {
    /** [index] 件目(1 始まり)の紹介を書いている */
    data class Writing(val index: Int, val total: Int) : ScriptProgress

    /** [index] 件目の紹介の、読み間違えやすい語を確かめている */
    data class CheckingReadings(val index: Int, val total: Int) : ScriptProgress
}

/**
 * ブックマークから、ポッドキャストの台本を書く。
 *
 * 1. 始まり・出典の紹介・終わりは決まった文([PodcastTemplates])。出典は AI に任せず必ず入れる
 * 2. 1件ずつ、AI に紹介の台本を JSON で書かせる(形が崩れたら1回作り直させる)。書けなければ、その件は要約をそのまま読む
 * 3. 1件ずつ、書いた台本(出典の紹介を含む)から読み間違えやすい語の読みを AI に挙げさせる([PodcastScriptPrompts.parseReadings])
 *
 * AI を使えない(モデルが無い・キーが無いなど)ときと、途中で AI が失敗したときは、残りを要約の読み上げにする
 * (端末内 AI の時間切れを何度も待たないため)。台本の文はそのまま残し、読みの整えは読み上げの直前に行う。
 */
class PodcastScriptWriter @Inject constructor(private val resolver: LlmProviderResolver) {

    suspend fun write(
        sources: List<PodcastSource>,
        format: PodcastFormat,
        onProgress: (ScriptProgress) -> Unit = {}
    ): PodcastScript {
        require(sources.isNotEmpty()) { "ブックマークがありません" }
        var provider: LlmProvider? = try {
            resolver.resolve(AiTask.CHAT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.i("ポッドキャストの台本に AI を使えないため、要約を読む: %s", e.javaClass.simpleName)
            null
        }
        val budget = budgetFor(provider)
        val lines = mutableListOf<ScriptLine>()
        val readings = mutableListOf<ReadingEntry>()
        var aiSegments = 0
        lines += PodcastTemplates.opening(format, sources)
        for (source in sources) {
            onProgress(ScriptProgress.Writing(source.index, sources.size))
            val attribution = PodcastTemplates.attribution(source, sources.size)
            val written = provider?.let { ai ->
                try {
                    writeSegment(ai, source, format, budget)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w("ポッドキャストの台本を AI で書けなかったため、残りは要約を読む: %s", e.javaClass.simpleName)
                    provider = null
                    null
                }
            }
            if (written != null) aiSegments++
            val segment = written ?: PodcastTemplates.summaryRead(source)
            lines += attribution
            lines += segment
            provider?.let { ai ->
                onProgress(ScriptProgress.CheckingReadings(source.index, sources.size))
                readings += findReadings(ai, listOf(attribution) + segment, budget)
            }
        }
        lines += PodcastTemplates.closing(format, sources)
        return PodcastScript(
            title = PodcastTemplates.episodeTitle(sources),
            format = format,
            lines = lines,
            readings = readings.distinctBy { it.matchKey },
            generator = if (aiSegments > 0) ScriptGenerator.AI else ScriptGenerator.SUMMARY_READ
        )
    }

    private class Budget(val summaryChars: Int, val excerptChars: Int, val readingChars: Int)

    private suspend fun budgetFor(provider: LlmProvider?): Budget {
        val limits = provider?.let { runCatching { it.chatContextLimits() }.getOrNull() } ?: ChatContextLimits.COMPACT
        // 端末内 AI はプロンプト全体で 1,800 文字ほど(指示を除いた分を、要約と本文に分ける)
        return if (limits == ChatContextLimits.COMPACT) Budget(summaryChars = 500, excerptChars = 500, readingChars = 1_200)
        else Budget(summaryChars = 1_500, excerptChars = 3_000, readingChars = 4_000)
    }

    private suspend fun writeSegment(provider: LlmProvider, source: PodcastSource, format: PodcastFormat, budget: Budget): List<ScriptLine>? {
        val response = StructuredOutput.generateWithRepair(
            maxRepairs = 1,
            label = "ポッドキャストの台本",
            validate = { PodcastScriptPrompts.segmentProblem(it, format) }
        ) { problem, _ ->
            provider.complete(PodcastScriptPrompts.segmentPrompt(source, format, budget.summaryChars, budget.excerptChars, problem))
        }
        return PodcastScriptPrompts.parseSegment(response, format, source.index)
    }

    /** 読みを挙げさせる。失敗しても台本は作れるので、空にして続ける */
    private suspend fun findReadings(provider: LlmProvider, lines: List<ScriptLine>, budget: Budget): List<ReadingEntry> =
        try {
            val texts = lines.map { it.text }
            val response = StructuredOutput.generateWithRepair(
                maxRepairs = 1,
                label = "ポッドキャストの読み",
                validate = PodcastScriptPrompts::readingsProblem
            ) { problem, _ -> provider.complete(PodcastScriptPrompts.readingsPrompt(texts, budget.readingChars, problem)) }
            PodcastScriptPrompts.parseReadings(response, texts.joinToString("\n"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("ポッドキャストの読みを AI で確かめられなかった: %s", e.javaClass.simpleName)
            emptyList()
        }
}
