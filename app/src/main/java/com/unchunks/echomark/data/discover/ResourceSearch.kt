package com.unchunks.echomark.data.discover

/** 資料の種類(検索先)。label は画面に出す日本語。 */
enum class ResourceSource(val label: String) {
    /** はてなブックマークの全文検索 RSS(日本語の Web 記事。人気順) */
    ARTICLE("記事"),

    /** YouTube の検索(InnerTube。字幕取得と同じ非公開 API) */
    VIDEO("動画"),

    /** 日本語版 Wikipedia の検索 API */
    ENCYCLOPEDIA("百科事典"),

    /** arXiv の API(英語の論文) */
    PAPER("論文")
}

/**
 * 検索で見つかった資料の候補。
 * @property snippet 概要(HTML タグ・実体参照を除いたプレーンテキスト。最大 300 文字)
 * @property popularity 人気の目安(はてなのブックマーク数・YouTube の再生回数など)。分からなければ null
 * @property tags 検索先が付けているタグ(はてなの dc:subject など)。無ければ空
 */
data class ResourceCandidate(
    val url: String,
    val title: String,
    val snippet: String,
    val source: ResourceSource,
    val siteName: String? = null,
    val popularity: Long? = null,
    val publishedAt: String? = null,
    val thumbnailUrl: String? = null,
    val tags: List<String> = emptyList()
)

/** 1つの検索先。失敗(通信・形式の変化)は例外で知らせる(呼び出し側が検索先ごとに握りつぶす)。 */
interface ResourceSearchClient {
    val source: ResourceSource
    suspend fun search(query: String, limit: Int): List<ResourceCandidate>
}
