package com.unchunks.echomark.data.remote

import org.jsoup.nodes.Document

/** Jsoup の Document からタイトル・概要・本文・OG 画像・サイト名を取り出す純粋関数(JVM 単体テスト可能)。 */
object HtmlContentExtractor {
    const val MAX_TEXT_CHARS = 5000

    /** 注意: ノイズ要素を除去するため、渡された [document] は破壊的に変更される。 */
    fun extract(document: Document): FetchedContent {
        val title = (metaContent(document, "meta[property=og:title]") ?: document.title())
            .trim().takeIf { it.isNotEmpty() }
        val description = (metaContent(document, "meta[property=og:description]")
            ?: metaContent(document, "meta[name=description]"))
            ?.trim()?.takeIf { it.isNotEmpty() }
        val imageUrl = imageUrl(document)
        val siteName = (metaContent(document, "meta[property=og:site_name]")
            ?: metaContent(document, "meta[name=application-name]"))
            ?.trim()?.takeIf { it.isNotEmpty() }

        // ノイズ要素を除去してから本文を取り出す
        document.select("script, style, noscript, nav, header, footer, aside, form, iframe, svg").remove()
        val root = document.selectFirst("article") ?: document.selectFirst("main") ?: document.body()
        val text = root?.text().orEmpty().trim().take(MAX_TEXT_CHARS)

        return FetchedContent(
            title = title,
            description = description,
            text = text,
            imageUrl = imageUrl,
            siteName = siteName
        )
    }

    /**
     * og:image(無ければ twitter:image)を、Document の baseUri を基準に絶対 URL にして返す。
     * 相対 URL で基準が無い場合や、http/https 以外(data: など)は使えないので null。
     */
    private fun imageUrl(document: Document): String? {
        val element = listOf(
            "meta[property=og:image:secure_url]",
            "meta[property=og:image]",
            "meta[name=twitter:image]"
        ).firstNotNullOfOrNull { selector ->
            document.selectFirst(selector)?.takeIf { it.attr("content").isNotBlank() }
        } ?: return null
        val raw = element.attr("content").trim()
        val absolute = element.absUrl("content").ifBlank { raw }
        return absolute.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    }

    private fun metaContent(document: Document, selector: String): String? =
        document.selectFirst(selector)?.attr("content")?.takeIf { it.isNotBlank() }
}
