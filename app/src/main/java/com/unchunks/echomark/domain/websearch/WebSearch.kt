package com.unchunks.echomark.domain.websearch

import kotlinx.coroutines.flow.Flow
import java.io.IOException

/**
 * Web 検索の結果の1件(検索サービスが返したもの)。
 *
 * 検索サービス(Brave Search API)の規約は、検索結果を保存・キャッシュしないこと(動かすのに要る一時的なものを除く)を
 * 求めている。そのため、これは1回の質問の間だけメモリに持ち、DB・バックアップには入れない。
 * 保存するのは、結果のページを自分で読みに行って得たもの([WebSource])だけにする。
 * @property description 検索サービスの概要(HTML のタグは除いたもの)
 * @property domain URL のホスト名(`www.` を除く)
 */
data class WebSearchResult(
    val url: String,
    val title: String,
    val description: String,
    val domain: String
)

/** Web 検索。失敗は [WebSearchException](オフラインモードなら [com.unchunks.echomark.data.network.OfflineModeException])で知らせる。 */
interface WebSearchClient {
    /** [query] で検索し、上位から最大 [count] 件を返す。キーは保存したものを使う。 */
    suspend fun search(query: String, count: Int): List<WebSearchResult>

    /** [apiKey](空なら保存したもの)で検索できるかを試す。1回分の検索を使う。 */
    suspend fun testKey(apiKey: String?)
}

/** Web 検索の失敗。画面に出す日本語を [message] に持つ。キー・URL は入れない。 */
sealed class WebSearchException(message: String) : IOException(message) {
    /** キーを保存していない */
    class KeyMissing : WebSearchException("Brave Search API のキーを設定してください")

    /** キーが無効(打ち間違い・削除済み・プランが無い) */
    class InvalidKey : WebSearchException("Brave Search API のキーが使えませんでした。キーを確かめてください")

    /** 1秒あたりの上限を超えた。[retryAfterSeconds] 秒ほど後なら使える */
    class RateLimited(val retryAfterSeconds: Long) :
        WebSearchException("Web 検索を続けて使いすぎました。しばらくしてから試してください")

    /** 月の上限(プランのクレジット)を使い切った */
    class QuotaExceeded : WebSearchException("今月の Web 検索の上限に達しました。Brave の管理画面で残りを確かめてください")

    /** 応答が大きすぎる・形がおかしい・サーバーの失敗など */
    class Failed(detail: String) : WebSearchException("Web 検索に失敗しました($detail)")
}

/**
 * 回答の根拠に使った Web のページ。回答と一緒に保存し、引用元の一覧に出す。
 *
 * 検索サービスの結果そのもの(タイトル・概要)は保存しない([WebSearchResult])。ここに入れるのは、ページを読みに行って
 * 得たタイトル・本文の抜粋だけ。ページを読めず検索結果の概要だけで答えたときは、[title] はドメイン、[excerpt] は空にする。
 * @property number 回答の中の引用番号(保存した資料の番号の続き)
 * @property domain ページのホスト名(`www.` を除く)
 * @property excerpt AI に渡したページの抜粋(照らし合わせに使う)。ページを読めなかったときは空
 */
data class WebSource(
    val number: Int,
    val url: String,
    val title: String,
    val domain: String,
    val excerpt: String
)

/**
 * 質問から Web を検索し、結果のページを読んで、回答の根拠にする抜粋を作る。
 * 失敗は [WebSearchException] などの例外で知らせる(結果が0件なら空)。
 */
interface WebSourceProvider {
    /**
     * @param query 検索語(質問から作ったもの)
     * @param keywords 本文から質問に関係する部分を選ぶための語
     * @param limit 返す件数の上限
     * @param excerptChars 抜粋1件の文字数
     */
    suspend fun collect(query: String, keywords: List<String>, limit: Int, excerptChars: Int): List<CollectedWebSource>
}

/**
 * 1回の質問のために集めた Web の資料。
 * @property excerpt AI に渡す抜粋(プロンプトへの指示の混入を和らげる整形済み)
 * @property fromPage ページを読んで得た抜粋か。false なら検索結果の概要(保存しない)
 */
data class CollectedWebSource(
    val url: String,
    val title: String,
    val domain: String,
    val excerpt: String,
    val fromPage: Boolean
) {
    /** 回答と一緒に保存する形。検索結果の概要は保存しないため、ページを読めなかったものはタイトル・抜粋を残さない */
    fun toWebSource(number: Int): WebSource = WebSource(
        number = number,
        url = url,
        title = if (fromPage) title else domain,
        domain = domain,
        excerpt = if (fromPage) excerpt else ""
    )
}

/**
 * チャットで Web 検索を使えるか。
 * @property keyConfigured Brave Search API のキーを保存しているか
 * @property offline オフラインモードか(通信しない)
 */
data class WebSearchAvailability(val keyConfigured: Boolean = false, val offline: Boolean = false) {
    val usable: Boolean get() = keyConfigured && !offline
}

/** Web 検索を使えるか(キー・オフラインモード)の移り変わり。 */
interface WebSearchStatus {
    val availability: Flow<WebSearchAvailability>
}
