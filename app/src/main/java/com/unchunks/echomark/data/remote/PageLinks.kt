package com.unchunks.echomark.data.remote

import org.jsoup.nodes.Document

/** ページ内のリンク(絶対 URL と、リンクの文字)。資料を探すときの候補に使う。 */
data class PageLink(val url: String, val text: String)

/** HTML からリンクを取り出す。http/https だけを残し、同じ URL は1つにまとめる。文字が空のリンクは除く。 */
object PageLinks {
    /** 1ページから取り出すリンクの上限(候補が多すぎると、端末内 AI の文脈に収まらない) */
    const val MAX_LINKS = 200

    /** リンクの文字の上限 */
    private const val MAX_TEXT_CHARS = 120

    /** [document] の基準 URL(取得した先の URL)で相対リンクを絶対 URL にする。 */
    fun collect(document: Document, limit: Int = MAX_LINKS): List<PageLink> {
        val seen = HashSet<String>()
        val links = mutableListOf<PageLink>()
        for (anchor in document.select("a[href]")) {
            if (links.size >= limit) break
            // フラグメント(#以降)だけが違う URL は同じ資料として扱う
            val url = anchor.absUrl("href").substringBefore('#')
            if (!url.startsWith("http://") && !url.startsWith("https://")) continue
            val text = anchor.text().replace(WHITESPACE, " ").trim().take(MAX_TEXT_CHARS)
            if (text.isEmpty() || !seen.add(url)) continue
            links += PageLink(url, text)
        }
        return links
    }

    private val WHITESPACE = Regex("\\s+")
}
