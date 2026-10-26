package com.unchunks.echomark.data.ai.discover

import com.unchunks.echomark.data.remote.PageLink
import com.unchunks.echomark.data.remote.UrlContentFetcher
import javax.inject.Inject

/**
 * 「資料を探す」の入口。調べたいことと、資料一覧のページ(検索結果・一覧・まとめのページなど)の URL を受け取り、
 * そのページのリンクから AI が選んで保存する。ページの取得は通常の URL 保存と同じ安全な取得(公開アドレスだけ)を使う。
 */
class ResourceFinder @Inject constructor(
    private val fetcher: UrlContentFetcher,
    private val selector: ResourceSelector
) {
    /**
     * @return 選んで保存した資料。取得に失敗したときは例外を投げる(呼び出し側で表示する)。
     */
    suspend fun find(topic: String, listPageUrl: String): List<PageLink> {
        val page = fetcher.fetch(listPageUrl).getOrThrow()
        // 一覧ページ自身へのリンク(ページ内の並べ替えなど)は候補にしない
        val candidates = page.links.filter { it.url != listPageUrl }
        return selector.selectAndSave(topic, candidates)
    }
}
