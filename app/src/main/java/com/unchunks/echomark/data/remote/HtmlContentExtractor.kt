package com.unchunks.echomark.data.remote

import org.jsoup.nodes.Document

/** Jsoup の Document からタイトル・概要・本文を取り出す純粋関数(JVM 単体テスト可能)。 */
object HtmlContentExtractor {
    const val MAX_TEXT_CHARS = 5000

    /** 注意: ノイズ要素を除去するため、渡された [document] は破壊的に変更される。 */
    fun extract(document: Document): FetchedContent {
        val title = (metaContent(document, "meta[property=og:title]") ?: document.title())
            .trim().takeIf { it.isNotEmpty() }
        val description = (metaContent(document, "meta[property=og:description]")
            ?: metaContent(document, "meta[name=description]"))
            ?.trim()?.takeIf { it.isNotEmpty() }

        // ノイズ要素を除去してから本文を取り出す
        document.select("script, style, noscript, nav, header, footer, aside, form, iframe, svg").remove()
        val root = document.selectFirst("article") ?: document.selectFirst("main") ?: document.body()
        val text = root?.text().orEmpty().trim().take(MAX_TEXT_CHARS)

        return FetchedContent(title = title, description = description, text = text)
    }

    private fun metaContent(document: Document, selector: String): String? =
        document.selectFirst(selector)?.attr("content")?.takeIf { it.isNotBlank() }
}
