package com.unchunks.echomark.data.discover

import com.unchunks.components.websearch.ResourceCandidate
import com.unchunks.components.websearch.ResourceSource
import com.unchunks.echomark.domain.chat.RagSupport
import org.json.JSONObject

/** AI が選んだ候補。[index] は AI に渡した候補の並び(0 始まり)。 */
data class ResourcePick(val index: Int, val reason: String?)

/**
 * 資料を探す機能の、AI に候補を選ばせる部分の純粋な処理(検索語・候補の絞り込み・指示・出力の読み取り)。単体テストできる。
 */
internal object DiscoverySelection {

    /** AI に渡す候補の数。端末内 AI の文脈長(入力と出力の合計 4,096 トークン)に指示と一緒に収まるよう絞る */
    const val MAX_CANDIDATES_FOR_AI = 10

    /** AI に選ばせる最大の件数 */
    const val MAX_PICKS = 5

    /** AI に渡す候補1件の概要の文字数 */
    private const val SNIPPET_CHARS = 100

    /** AI に渡す候補1件のタイトルの文字数 */
    private const val TITLE_CHARS = 80

    /** 選んだ理由の最大文字数(指示より長く書かれても、画面で読める長さにする) */
    private const val MAX_REASON_CHARS = 60

    /**
     * 検索先に渡す検索語。テーマの文から語(英数字・カタカナ・漢字の 2 文字以上の語)を取り出して空白でつなぐ
     * (「Jetpack Compose の画面を速くしたい」→「Jetpack Compose 画面」)。語が取れなければテーマをそのまま使う。
     * はてなブックマークの全文検索は空白区切りの語をすべて含むものを返すため、文のままでは見つかりにくい。
     */
    fun searchQuery(topic: String): String =
        RagSupport.keywordsOf(topic, max = MAX_QUERY_WORDS).joinToString(" ").ifBlank { topic.trim() }

    /**
     * 論文(arXiv)に渡す検索語。arXiv は英語の論文で、日本語の語では見つからないため、テーマのうち英数字の語だけを使う。
     * 英数字の語が無ければ null(論文は探さない)。
     */
    fun paperQuery(topic: String): String? =
        RagSupport.keywordsOf(topic, max = MAX_QUERY_WORDS)
            .filter { word -> word.all { it.code < ASCII_LIMIT } }
            .joinToString(" ")
            .takeIf { it.isNotBlank() }

    private const val MAX_QUERY_WORDS = 4
    private const val ASCII_LIMIT = 128

    /**
     * AI に渡す候補を [max] 件まで選ぶ。テーマの語がタイトル・概要・タグに出てくる数で並べ替え(タイトルは重く数える)、
     * 種類(記事・動画など)ごとの上位を交互に取る(同じ種類ばかりにならないように)。同じ点数なら検索先の並び(人気・関連の順)を保つ。
     */
    fun candidatesForAi(topic: String, candidates: List<ResourceCandidate>, max: Int = MAX_CANDIDATES_FOR_AI): List<ResourceCandidate> {
        val keywords = RagSupport.keywordsOf(topic).map { it.lowercase() }
        val bySource = candidates.groupBy { it.source }
            .mapValues { (_, list) -> list.sortedByDescending { score(it, keywords) } }
        val iterators = ResourceSource.entries.mapNotNull { bySource[it]?.iterator() }
        val result = mutableListOf<ResourceCandidate>()
        while (result.size < max && iterators.any { it.hasNext() }) {
            for (iterator in iterators) {
                if (result.size >= max) break
                if (iterator.hasNext()) result += iterator.next()
            }
        }
        return result
    }

    /** 候補とテーマの近さの目安(語がタイトルに出てくれば 2 点、概要・タグに出てくれば 1 点)。 */
    internal fun score(candidate: ResourceCandidate, keywords: List<String>): Int {
        val title = candidate.title.lowercase()
        val body = (candidate.snippet + " " + candidate.tags.joinToString(" ")).lowercase()
        return keywords.sumOf { keyword ->
            (if (title.contains(keyword)) TITLE_WEIGHT else 0) + (if (body.contains(keyword)) 1 else 0)
        }
    }

    private const val TITLE_WEIGHT = 2

    /** AI に候補を選ばせる指示。候補には 1 から番号を付ける。 */
    fun instructions(topic: String, candidates: List<ResourceCandidate>, maxPicks: Int = MAX_PICKS): String = buildList {
        add("あなたは資料探しを手伝うアシスタントです。ユーザーのテーマの理解に役立つ資料を、下の候補から選び、JSONのみを出力してください。")
        add("説明文やコードブロックは出力しないでください。")
        add("")
        add("出力形式:")
        add("""{"picks": [{"n": 候補の番号, "reason": "選んだ理由(40文字以内の日本語)"}]}""")
        add("")
        add("ルール:")
        add("- テーマに直接役立つものだけを、役立つ順に最大${maxPicks}件選ぶ。関係の薄いもの・宣伝・同じ内容の重複は選ばない。")
        add("- 入門と詳しい解説、記事と動画のように、観点や種類の違うものを組み合わせる。")
        add("- 当てはまるものが無ければ picks は空の配列にする。")
        add("")
        add("テーマ: ${topic.trim()}")
        add("")
        add("候補:")
        candidates.forEachIndexed { i, candidate ->
            val site = candidate.siteName?.takeIf { it.isNotBlank() }?.let { "・$it" }.orEmpty()
            val snippet = candidate.snippet.take(SNIPPET_CHARS).takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
            add("[${i + 1}] (${candidate.source.label}$site) ${candidate.title.take(TITLE_CHARS)}$snippet")
        }
    }.joinToString("\n")

    /**
     * AI の出力から選んだ候補を読む。範囲外の番号・重複は除き、[maxPicks] 件まで。
     * 端末内の小さいモデルは形式を崩しやすいため、`{"picks": [2, 5]}` のような番号だけの配列や、
     * 壊れた JSON の `"n": 3` も読む。何も読めなければ空。
     * @param count AI に渡した候補の数
     */
    fun parsePicks(response: String, count: Int, maxPicks: Int = MAX_PICKS): List<ResourcePick> {
        val picks = parseJson(response) ?: parseLoosely(response)
        return picks
            .filter { it.index in 0 until count }
            .distinctBy { it.index }
            .take(maxPicks)
    }

    private fun parseJson(response: String): List<ResourcePick>? = try {
        val start = response.indexOf('{')
        val end = response.lastIndexOf('}')
        if (start !in 0 until end) {
            null
        } else {
            val array = JSONObject(response.substring(start, end + 1)).optJSONArray("picks")
            array?.let {
                (0 until it.length()).mapNotNull { i ->
                    val item = it.opt(i)
                    when (item) {
                        is JSONObject -> numberOf(item.opt("n"))?.let { n ->
                            ResourcePick(n - 1, reasonOf(item.optString("reason")))
                        }
                        else -> numberOf(item)?.let { n -> ResourcePick(n - 1, null) }
                    }
                }
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun parseLoosely(response: String): List<ResourcePick> =
        LOOSE_PICK.findAll(response).map { match ->
            ResourcePick(match.groupValues[1].toInt() - 1, reasonOf(match.groupValues[2]))
        }.toList()

    /** 数値・"3"・"[3]" のような番号を読む */
    private fun numberOf(value: Any?): Int? = when (value) {
        is Number -> value.toInt()
        is String -> NUMBER.find(value)?.value?.toIntOrNull()
        else -> null
    }

    private fun reasonOf(text: String?): String? =
        text?.trim()?.takeIf { it.isNotEmpty() && it != "null" }?.take(MAX_REASON_CHARS)

    private val NUMBER = Regex("""\d+""")

    /** 壊れた JSON の `"n": 3, "reason": "…"`(理由は無くてもよい) */
    private val LOOSE_PICK = Regex(""""n"\s*:\s*"?(\d+)"?(?:\s*,\s*"reason"\s*:\s*"([^"]*))?""")
}
