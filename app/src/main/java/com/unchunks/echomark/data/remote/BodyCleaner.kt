package com.unchunks.echomark.data.remote

import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor

/**
 * Web ページの本文から、記事の中身ではない部分(共有ボタン・関連記事・コメント・Cookie の案内・著作権表示など)を取り除き、
 * 段落の区切りを残した読みやすいテキストにする(#22)。純粋関数(JVM 単体テスト可能)。
 *
 * 取り除く対象は、取り出す範囲([extractText] の root)の中だけにする。root そのものや、root を含む要素は消さない
 * (ページ全体が `social-layout` のようなクラスを持っていても、本文ごと消さないため)。
 */
object BodyCleaner {

    /**
     * [root] の本文を、段落ごとの行にして返す。ノイズの要素を除いた後の本文で、[maxChars] を超える分は切る。
     * 注意: ノイズの要素を除くため、[root] の中身は破壊的に変更される。
     */
    fun extractText(root: Element, maxChars: Int): String {
        root.select("*").toList().filter { it !== root && isNoiseElement(it) }.forEach { it.remove() }
        return cleanLines(blockText(root)).take(maxChars)
    }

    /**
     * 行ごとに、本文でないもの(画面の部品の文言・著作権表示・Cookie の案内・記号だけの行)と、同じ行の繰り返し(メニューの重複)を除く。
     * 前後の空白は削り、空行は除く(段落は1行ずつ)。
     */
    fun cleanLines(text: String): String {
        val seen = mutableSetOf<String>()
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !isBoilerplateLine(it) }
            .filter { line -> line.length < MIN_DEDUPE_CHARS || seen.add(line) }
            .joinToString("\n")
    }

    /** ブロック要素ごとに改行を入れたテキスト。空白は1つにまとめるが、`pre` の中の改行は残す。 */
    internal fun blockText(root: Element): String {
        val out = StringBuilder()
        fun newline() {
            if (out.isNotEmpty() && out.last() != '\n') out.append('\n')
        }
        NodeTraversor.traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                when (node) {
                    is TextNode -> out.append(if (insidePre(node)) node.wholeText else node.wholeText.replace(WHITESPACE, " "))
                    is Element -> if (node.normalName() == "br" || node.isBlock) newline()
                }
            }

            override fun tail(node: Node, depth: Int) {
                if (node is Element && node.isBlock) newline()
            }
        }, root)
        return out.toString()
    }

    private fun insidePre(node: Node): Boolean =
        generateSequence(node.parent()) { it.parent() }.any { it is Element && it.normalName() == "pre" }

    /** クラス名・id の語(英数字の区切りで分けたもの)が画面の部品を表す要素か。非表示の要素も含む。 */
    private fun isNoiseElement(element: Element): Boolean {
        if (element.hasAttr("hidden") || element.attr("aria-hidden") == "true") return true
        if (element.attr("role") in NOISE_ROLES) return true
        val tokens = (element.className() + " " + element.id()).lowercase().split(NON_ALNUM).filter { it.isNotEmpty() }
        return tokens.any { it in NOISE_TOKENS }
    }

    private fun isBoilerplateLine(line: String): Boolean {
        if (line.length <= MAX_SHORT_BOILERPLATE_CHARS && line.lowercase().replace(WHITESPACE, "") in SHORT_BOILERPLATE) return true
        if (COPYRIGHT.containsMatchIn(line)) return true
        if (line.length <= MAX_COOKIE_NOTICE_CHARS && COOKIE_WORD.containsMatchIn(line) && COOKIE_ACTION.containsMatchIn(line)) return true
        return line.length <= MAX_SYMBOL_LINE_CHARS && SYMBOLS_ONLY.matches(line)
    }

    /** これ以上の長さの行だけ、同じ行の2回目以降を除く(短い行は箇条書きなどで正当に重なる) */
    private const val MIN_DEDUPE_CHARS = 4
    private const val MAX_SHORT_BOILERPLATE_CHARS = 30
    private const val MAX_COOKIE_NOTICE_CHARS = 300
    private const val MAX_SYMBOL_LINE_CHARS = 10

    private val WHITESPACE = Regex("""\s+""")
    private val NON_ALNUM = Regex("""[^a-z0-9]+""")

    private val NOISE_ROLES = setOf("navigation", "banner", "contentinfo", "complementary", "dialog", "alertdialog", "search")

    /** 画面の部品を表すクラス名・id の語 */
    private val NOISE_TOKENS = setOf(
        "cookie", "cookies", "consent", "gdpr", "sidebar", "share", "sharing", "sns", "social", "related", "recommend", "recommended",
        "comment", "comments", "advert", "advertisement", "ads", "adsbygoogle", "newsletter", "popup", "modal", "breadcrumb",
        "breadcrumbs", "pagination", "pager", "widget", "promo", "sponsor", "sponsored"
    )

    /** 単独の行なら本文ではない、画面の部品の文言(空白を除いて小文字にしたもの) */
    private val SHORT_BOILERPLATE = setOf(
        "シェア", "ツイート", "tweet", "share", "いいね", "いいね!", "ポスト", "フォロー", "follow", "line", "はてブ", "pocket", "リンクをコピー", "copylink",
        "ログイン", "会員登録", "新規登録", "無料会員登録", "signin", "login", "logout", "signup", "subscribe", "購読", "メニュー", "menu",
        "検索", "search", "トップ", "ホーム", "home", "skiptocontent", "本文へスキップ", "ページトップへ", "ページの先頭へ", "top", "pagetop",
        "もっと見る", "続きを読む", "readmore", "前の記事", "次の記事", "前へ", "次へ", "prev", "next", "関連記事", "あわせて読みたい",
        "人気記事", "ランキング", "おすすめ記事", "この記事をシェア", "目次", "コメント", "広告", "pr", "sponsored", "advertisement"
    )

    private val COPYRIGHT = Regex("""^(?:©|\(c\)|copyright\b)""", RegexOption.IGNORE_CASE)
    private val COOKIE_WORD = Regex("""cookie|クッキー""", RegexOption.IGNORE_CASE)
    private val COOKIE_ACTION = Regex("""同意|許可|承諾|利用して|使用して|accept|agree|consent|allow""", RegexOption.IGNORE_CASE)
    private val SYMBOLS_ONLY = Regex("""[\p{Punct}\s|/>›»‹«・\d]+""")
}
