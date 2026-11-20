package com.unchunks.echomark.data.ai.discover

import com.unchunks.components.web.PageLink

/**
 * AI に選ばせる資料の候補。Web のリンクと端末内のファイルを同じ形で扱う。
 * @property ref 保存に使う参照(Web は URL、端末内は content:// の URI 文字列)
 * @property title 候補の名前(リンクの文字・ファイル名)。AI にも見せる
 */
data class ResourceCandidate(
    val source: Source,
    val ref: String,
    val title: String
) {
    enum class Source { WEB, FILE }

    companion object {
        fun web(link: PageLink) = ResourceCandidate(Source.WEB, link.url, link.text)

        fun file(uri: String, displayName: String) = ResourceCandidate(Source.FILE, uri, displayName)
    }
}
