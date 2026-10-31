package com.unchunks.echomark.domain.chat

import com.unchunks.echomark.domain.model.ChatMessage
import com.unchunks.components.llm.ChatRole
import com.unchunks.echomark.domain.model.Tag
import java.text.Normalizer

/**
 * チャット(RAG)で使う純粋なヘルパー群。Android 依存なしで単体テストできる。
 */
object RagSupport {

    /**
     * 文脈として採用する最小の類似度(コサイン類似度)は埋め込みモデルごとに異なるため、
     * [com.unchunks.components.embedding.EmbeddingModelProfile.minRagSimilarity] を使う。
     * ObjectBox の COSINE は「類似度」ではなく距離(1 - cos類似度、0〜2)を返すため、
     * [distanceToSimilarity] で変換してから比較する。
     */

    /** 検索する近傍の最大件数。 */
    const val SEARCH_LIMIT = 5

    /** プロンプトに含める会話履歴の最大件数。 */
    const val HISTORY_LIMIT = 6

    /** 文脈1件あたりの本文の最大文字数。 */
    const val MAX_SNIPPET_CHARS = 300

    /** 自動タイトルの最大文字数。 */
    const val TITLE_MAX_CHARS = 30

    /** ObjectBox COSINE の距離をコサイン類似度へ変換する。 */
    fun distanceToSimilarity(distance: Double): Double = 1.0 - distance

    /**
     * (ID, 距離) のリストから、類似度が [minSimilarity] 以上のものだけを
     * 近い順に残して ID を返す。同一 ID は最初のものだけ残す。
     */
    fun selectRelevantIds(
        hits: List<Pair<Long, Double>>,
        minSimilarity: Double
    ): List<Long> = hits
        .filter { distanceToSimilarity(it.second) >= minSimilarity }
        .sortedBy { it.second }
        .map { it.first }
        .distinct()

    /** 質問に含まれていればそのタグの保存を文脈に加える、タグ名の最小の文字数(1文字のタグは偶然含まれやすい) */
    const val MIN_TAG_MATCH_CHARS = 2

    /**
     * 名前が質問に含まれるタグ(大文字・小文字は区別しない)。
     * 「「技術」の保存をまとめて」のように、意味の近さ(埋め込み)では見つからない、タグで絞る質問に使う。
     */
    fun tagsMentionedIn(message: String, tags: List<Tag>): List<Tag> =
        tags.filter { it.name.trim().length >= MIN_TAG_MATCH_CHARS && message.contains(it.name.trim(), ignoreCase = true) }

    private const val DAY_MILLIS = 24 * 60 * 60 * 1000L

    /** 改行・連続する空白(1つの空白にまとめる) */
    private val WHITESPACE = Regex("\\s+")

    /**
     * 文脈に載せるタイトル。いつ保存したかを添え、「最近保存したもの」「今週保存したもの」のような質問に答えられるようにする
     * (AI は今日の日付を知らないため、日付ではなく何日前かで示す)。例: "タイトル(3日前に保存)"
     */
    fun contextTitle(title: String, createdAt: Long, now: Long): String {
        val days = ((now - createdAt) / DAY_MILLIS).coerceAtLeast(0)
        val saved = if (days == 0L) "今日保存" else "${days}日前に保存"
        return "$title($saved)"
    }

    /** 引用しやすい "[n] タイトル: 要約or本文冒頭" 形式の文脈1件を作る。 */
    fun formatContextEntry(index: Int, title: String, body: String?, maxChars: Int = MAX_SNIPPET_CHARS): String {
        val text = body.orEmpty().trim().replace(WHITESPACE, " ").take(maxChars)
        return if (text.isEmpty()) "[$index] $title" else "[$index] $title: $text"
    }

    /** 回答の中の引用番号([1]・[1, 2]) */
    private val CITATION = Regex("""\s*\[\d{1,3}(?:\s*[,、]\s*\d{1,3})*]""")

    /**
     * 回答から引用番号を除いた発言。番号はその回答のときの資料の番号で、次の質問の資料の番号とは別のものなので、
     * 履歴や要約に残すと、今の資料を指しているように読めてしまう。
     */
    fun withoutCitations(message: ChatMessage): ChatMessage =
        if (message.role == ChatRole.ASSISTANT) message.copy(content = message.content.replace(CITATION, "")) else message

    /** 「このブックマークについて質問」で固定する1件の本文の最大文字数(要約と本文を合わせて)。 */
    const val PINNED_SNIPPET_CHARS = 1000

    /** 質問の対象として固定したブックマークの印(文脈の先頭に付ける)。 */
    const val PINNED_MARK = "(質問の対象)"

    /**
     * 質問の対象として固定したブックマークの文脈1件。要約と本文の両方を、通常より長めに含める。
     * 例: "[1] (質問の対象) タイトル: 要約 本文: ..."
     */
    fun formatPinnedContextEntry(
        index: Int,
        title: String,
        summary: String?,
        content: String?,
        maxChars: Int = PINNED_SNIPPET_CHARS
    ): String {
        val body = listOfNotNull(
            summary?.takeIf { it.isNotBlank() },
            content?.takeIf { it.isNotBlank() && it != summary }?.let { "本文: $it" }
        ).joinToString(" ")
        return formatContextEntry(index, "$PINNED_MARK $title", body, maxChars)
    }

    /**
     * 固定したもの([pinned])を先頭に置き、検索結果([others])から重複を除いて続ける。合計 [limit] 件まで。
     */
    fun <T> pinFirst(pinned: T?, others: List<T>, idOf: (T) -> Long, limit: Int = SEARCH_LIMIT): List<T> {
        if (pinned == null) return others.distinctBy(idOf).take(limit)
        val pinnedId = idOf(pinned)
        return (listOf(pinned) + others.filter { idOf(it) != pinnedId }).distinctBy(idOf).take(limit)
    }

    /**
     * 固定したブックマークがあるときの検索クエリ。「要約して」のような短い質問でも
     * 関連するものが見つかるよう、対象のタイトルを添える。
     */
    fun searchQueryFor(userMessage: String, pinnedTitle: String?): String =
        if (pinnedTitle.isNullOrBlank()) userMessage else "$pinnedTitle $userMessage"

    /** 質問から取り出す検索語の最大数 */
    const val MAX_KEYWORDS = 5

    /**
     * 質問から、キーワード検索に使う語を出てきた順に取り出す(重複・質問によく出るだけの語は除く)。
     * 日本語は単語に分けられないため、英数字の語・カタカナの語・漢字の語(2 文字以上)を語とみなす
     * (「Kotlin のコルーチンの例外処理は？」→ Kotlin・コルーチン・例外処理)。
     * 質問の文全体で探すと、文と同じ並びの文字列が無い限り何も見つからないため、語ごとに探す。
     */
    fun keywordsOf(message: String, max: Int = MAX_KEYWORDS): List<String> {
        val normalized = Normalizer.normalize(message, Normalizer.Form.NFKC)
        return KEYWORD.findAll(normalized)
            .flatMap { splitQuestionWords(it.value) }
            .distinctBy { it.lowercase() }
            .take(max)
            .toList()
    }

    /**
     * 漢字・カタカナの語から、質問によく出るだけの語([QUESTION_WORDS])を除いた残り(2 文字以上)。
     * 漢字は続けて書かれるため「最近保存」のように1つの語になるので、中に含まれるものも除く。
     */
    private fun splitQuestionWords(word: String): List<String> {
        if (word.lowercase() in QUESTION_WORDS) return emptyList()
        if (word.first().isLetterOrDigit() && word.first().code < ASCII_LIMIT) return listOf(word)
        var rest = word
        QUESTION_WORDS.forEach { rest = rest.replace(it, " ") }
        return rest.split(' ').filter { it.length >= 2 }
    }

    private const val ASCII_LIMIT = 128

    /** 英数字の語(C++・C#・Node.js なども)、カタカナの語、漢字の語 */
    private val KEYWORD = Regex("""[A-Za-z0-9][A-Za-z0-9+#._-]*[A-Za-z0-9+#]|[ァ-ヶー]{2,}|[一-龠々〆ヵヶ]{2,}""")

    /** 保存全体についての質問によく出るだけで、検索の手がかりにならない語 */
    private val QUESTION_WORDS = setOf(
        "保存", "記事", "動画", "内容", "要点", "要約", "最近", "今日", "昨日", "今週", "先週", "全部", "一覧",
        "説明", "質問", "以前", "前回", "関連", "情報", "資料", "ブックマーク", "メモ", "ページ", "何件"
    )

    /** これ以下の長さの質問は、前の質問の続き(「もっと詳しく」「他には？」)とみなす */
    private const val FOLLOW_UP_MAX_CHARS = 12

    /** 前の話を指す言葉(「それ」「その記事」「2つ目」など) */
    private val FOLLOW_UP_WORDS = Regex("""それ|これ|あれ|その|あの|もっと|詳しく|続き|ほかに|他に|さっき|先ほど|前の|上の|[1-9一二三]つ目|[1-9]番""")

    /** 前の話の続きの質問か(短い・前の話を指す言葉がある)。続きなら、文脈の検索に前の質問も使う。 */
    fun isFollowUp(message: String): Boolean {
        val trimmed = message.trim()
        return trimmed.length <= FOLLOW_UP_MAX_CHARS || FOLLOW_UP_WORDS.containsMatchIn(trimmed)
    }

    /**
     * 文脈の検索に使う文。前の話の続きの質問([isFollowUp])なら、直前のユーザーの質問を前に足す
     * (「それの使い方は？」だけでは何についての質問か分からず、関係のない保存が文脈になるため)。
     * @param history 直近の会話履歴(古い順。今回の質問は含まない)
     */
    fun searchQueryWithHistory(userMessage: String, history: List<ChatMessage>): String {
        if (!isFollowUp(userMessage)) return userMessage
        val previous = history.lastOrNull { it.role == ChatRole.USER }?.content
            ?: return userMessage
        return "$previous $userMessage"
    }

    /**
     * 前の話の続きの質問なら、直前の回答で使った(引用した)ブックマークの ID。続きでなければ空。
     * 「それについてもっと詳しく」に、前の回答と同じ保存を根拠に答えられるよう、文脈の候補に足す。
     */
    fun previousReferenceIds(userMessage: String, history: List<ChatMessage>): List<Long> {
        if (!isFollowUp(userMessage)) return emptyList()
        return history.lastOrNull { it.role == ChatRole.ASSISTANT }
            ?.referencedBookmarkIds.orEmpty()
    }

    /** 本文から取り出す「関連する部分」の最大文字数 */
    const val PASSAGE_CHARS = 200

    /**
     * 本文のうち、[keywords] が最も多く出てくる [maxChars] 文字の部分。どの語も出てこなければ null。
     * 要約だけでは答えられない細かい質問(「その記事で紹介されていたコマンドは？」)に、本文の該当する部分で答えられるようにする。
     * 本文が [maxChars] 以下なら、部分ではなく本文そのものが文脈に入るため null。
     */
    fun bestPassage(content: String?, keywords: List<String>, maxChars: Int = PASSAGE_CHARS): String? {
        val text = content?.trim()?.replace(WHITESPACE, " ") ?: return null
        if (keywords.isEmpty() || text.length <= maxChars) return null
        val lower = text.lowercase()
        val keys = keywords.map { it.lowercase() }
        var best: Pair<Int, Int>? = null
        val step = (maxChars / 2).coerceAtLeast(1)
        var start = 0
        while (start < text.length) {
            val window = lower.substring(start, minOf(text.length, start + maxChars))
            val score = keys.count { window.contains(it) }
            if (score > 0 && (best == null || score > best.second)) best = start to score
            if (start + maxChars >= text.length) break
            start += step
        }
        val from = best?.first ?: return null
        val passage = text.substring(from, minOf(text.length, from + maxChars)).trim()
        return (if (from > 0) "…" else "") + passage + (if (from + maxChars < text.length) "…" else "")
    }

    /**
     * 要約に本文の「関連する部分」を添えた、文脈1件の本文。部分が無ければ要約(無ければ本文の冒頭)だけ。
     * 例: "要約 関連する部分: 「…該当する本文…」"
     */
    fun contextBody(summary: String?, content: String?, passage: String?): String? {
        val base = summary?.takeIf { it.isNotBlank() } ?: return content
        return if (passage == null) base else "$base 関連する部分: 「$passage」"
    }

    /** 対象のブックマークの外の保存を聞いている言葉 */
    private val ASKS_FOR_RELATED = Regex("""関連|他の保存|ほかの保存|他に保存|似た|同じ話題|同じテーマ|比べ|比較""")

    /**
     * 「このブックマークについて質問」で、対象の外の保存も探す質問か。それ以外の質問は対象だけを根拠に答える
     * (文脈が短い端末内 AI では、関係の薄い保存が対象の本文の分を奪うため)。
     */
    fun asksForRelated(message: String): Boolean = ASKS_FOR_RELATED.containsMatchIn(message)

    /** 長い本文から取り出す部分の数 */
    private const val PINNED_PASSAGES = 3

    /** 取り出した部分の間の区切り */
    private const val PASSAGE_SEPARATOR = " … "

    /**
     * 対象のブックマークの本文を [maxChars] に収める。長ければ、[keywords] が多く出てくる部分を本文の順に並べる。
     * 語が無い・出てこない質問(「要約して」)には、冒頭だけでなく全体が分かるよう、最初・中ほど・最後を渡す。
     */
    fun pinnedPassages(content: String?, keywords: List<String>, maxChars: Int): String? {
        val text = content?.trim()?.replace(WHITESPACE, " ") ?: return null
        if (text.length <= maxChars) return text
        val size = (maxChars - PASSAGE_SEPARATOR.length * (PINNED_PASSAGES - 1)) / PINNED_PASSAGES
        val keys = keywords.map { it.lowercase() }
        val lower = text.lowercase()
        val matches = (0..(text.length - size) step size)
            .map { start -> start to keys.count { lower.substring(start, start + size).contains(it) } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(PINNED_PASSAGES)
        val starts = matches.ifEmpty { listOf(0, (text.length - size) / 2, text.length - size) }
        return starts.sorted().joinToString(PASSAGE_SEPARATOR) { text.substring(it, it + size).trim() }
    }

    /** 「このブックマークについて質問」で作る会話の既定タイトル。 */
    fun aboutBookmarkTitle(bookmarkTitle: String, maxChars: Int = TITLE_MAX_CHARS): String =
        titleFrom(bookmarkTitle, maxChars - ABOUT_SUFFIX.length) + ABOUT_SUFFIX

    private const val ABOUT_SUFFIX = "について"

    /** 最初のユーザー発言から自動タイトルを作る(改行は空白化し、超過分は省略記号)。 */
    fun titleFrom(firstUserMessage: String, maxChars: Int = TITLE_MAX_CHARS): String {
        val oneLine = firstUserMessage.trim().replace(WHITESPACE, " ")
        return if (oneLine.length <= maxChars) oneLine else oneLine.take(maxChars) + "…"
    }
}
