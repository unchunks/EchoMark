package com.unchunks.echomark.data.discover

import com.unchunks.echomark.data.ai.LlmProviderResolver
import com.unchunks.echomark.domain.repository.AiTask
import com.unchunks.echomark.domain.repository.BookmarkRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject

/** 資料を探す処理の進み具合。 */
enum class DiscoveryStep { SEARCHING, SELECTING }

/**
 * 見つかった資料。
 * @property isRecommended AI が選んだもの(画面では最初から選択した状態にする)
 * @property reason AI が書いた、選んだ理由
 * @property isSaved すでにブックマークに保存してある
 */
data class DiscoveredResource(
    val candidate: ResourceCandidate,
    val isRecommended: Boolean,
    val reason: String? = null,
    val isSaved: Boolean = false
)

/**
 * 資料を探した結果。AI が選んだものを先に、選ばなかった候補をその後に並べる。
 * @property failedSources 検索できなかった検索先
 * @property aiError AI に選ばせられなかったときの理由(端末内モデルが無いなど)。そのときは検索の上位を候補のまま返す
 */
data class DiscoveryResult(
    val resources: List<DiscoveredResource>,
    val failedSources: Set<ResourceSource>,
    val aiError: Throwable? = null
)

/**
 * テーマに合う資料を Web で探し、AI に役立つものを選ばせる。選んだものは [save] でブックマークに保存する
 * (保存したものは通常どおり本文の取得・要約・タグ付けが進む)。
 *
 * 1. テーマから検索語を作り、選んだ検索先(記事・動画・百科事典・論文)を同時に検索する([ResourceSearcher])
 * 2. テーマの語が出てくる数で候補を絞り、AI に選ばせる(チャットの AI の設定を使う。既定は端末内 AI)
 * 3. AI が使えない・失敗したときは、絞った候補を AI のおすすめ無しで返す(検索の結果は無駄にしない)
 */
class ResourceDiscovery @Inject constructor(
    private val searcher: ResourceSearcher,
    private val llmProviderResolver: LlmProviderResolver,
    private val bookmarkRepository: BookmarkRepository
) {
    /**
     * @param onStep 処理が次の段に進んだときに呼ぶ(画面に「検索しています」「AI が選んでいます」を出す)
     */
    suspend fun discover(
        topic: String,
        sources: Set<ResourceSource>,
        onStep: (DiscoveryStep) -> Unit = {}
    ): DiscoveryResult {
        onStep(DiscoveryStep.SEARCHING)
        val outcome = search(topic, sources)
        val saved = savedUrls()
        val candidates = DiscoverySelection.candidatesForAi(topic, outcome.candidates)
        if (candidates.isEmpty()) return DiscoveryResult(emptyList(), outcome.failedSources)

        onStep(DiscoveryStep.SELECTING)
        val (picks, aiError) = try {
            val provider = llmProviderResolver.resolve(AiTask.CHAT)
            val response = provider.complete(DiscoverySelection.instructions(topic, candidates))
            DiscoverySelection.parsePicks(response, candidates.size) to null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "AI に資料を選ばせられなかった")
            emptyList<ResourcePick>() to e
        }

        val pickedIndices = picks.map { it.index }.toSet()
        val recommended = picks.map { pick ->
            val candidate = candidates[pick.index]
            DiscoveredResource(candidate, isRecommended = true, reason = pick.reason, isSaved = candidate.isIn(saved))
        }
        val others = candidates.filterIndexed { index, _ -> index !in pickedIndices }
            .map { DiscoveredResource(it, isRecommended = false, isSaved = it.isIn(saved)) }
        return DiscoveryResult(recommended + others, outcome.failedSources, aiError)
    }

    /**
     * 選んだ資料をブックマークに保存する。すでに保存してあるものは保存し直さない。新しく保存した件数を返す。
     */
    suspend fun save(candidates: List<ResourceCandidate>): Int =
        candidates.count { candidate ->
            !bookmarkRepository.saveUrlBookmark(candidate.url, candidate.title, memo = null).isDuplicate
        }

    /** 論文は英語の語だけで探す(日本語の語では見つからない)。英語の語が無ければ論文は探さない。 */
    private suspend fun search(topic: String, sources: Set<ResourceSource>): SearchOutcome {
        val others = searcher.search(DiscoverySelection.searchQuery(topic), sources - ResourceSource.PAPER, LIMIT_PER_SOURCE)
        if (ResourceSource.PAPER !in sources) return others
        val paperQuery = DiscoverySelection.paperQuery(topic) ?: return others
        val papers = searcher.search(paperQuery, setOf(ResourceSource.PAPER), LIMIT_PER_SOURCE)
        return SearchOutcome(others.candidates + papers.candidates, others.failedSources + papers.failedSources)
    }

    /** 保存してあるブックマークの URL(比べやすい形にそろえたもの) */
    private suspend fun savedUrls(): Set<String> =
        bookmarkRepository.observeBookmarks().first()
            .mapNotNull { it.contentUri }
            .map { ResourceSearcher.normalizeUrl(it) }
            .toSet()

    private fun ResourceCandidate.isIn(saved: Set<String>): Boolean = ResourceSearcher.normalizeUrl(url) in saved

    private companion object {
        /** 検索先ごとに取る候補の数 */
        const val LIMIT_PER_SOURCE = 8
    }
}
