package com.unchunks.echomark.data.remote

import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.nodes.Document
import java.net.URI

/** Jsoup の Document からタイトル・概要・本文・OG 画像・サイト名を取り出す純粋関数(JVM 単体テスト可能)。 */
object HtmlContentExtractor {
    const val MAX_TEXT_CHARS = 5000

    /** YouTube のウォッチページ(インライン JSON)から取り出した動画情報。 */
    private data class YouTubeDetails(
        val title: String?,
        val author: String?,
        val description: String?,
        val keywords: List<String>
    )

    /** 注意: ノイズ要素を除去するため、渡された [document] は破壊的に変更される。 */
    fun extract(document: Document): FetchedContent {
        var title = (metaContent(document, "meta[property=og:title]") ?: document.title())
            .trim().takeIf { it.isNotEmpty() }
        var description = (metaContent(document, "meta[property=og:description]")
            ?: metaContent(document, "meta[name=description]"))
            ?.trim()?.takeIf { it.isNotEmpty() }
        val imageUrl = imageUrl(document)
        val siteName = (metaContent(document, "meta[property=og:site_name]")
            ?: metaContent(document, "meta[name=application-name]"))
            ?.trim()?.takeIf { it.isNotEmpty() }

        // script を除去する前に、YouTube なら動画の説明文などをインライン JSON から取り出す
        val youtube = youtubeDetails(document, siteName)
        val youtubeText = youtube?.let { youtubeText(it) }

        // ノイズ要素を除去してから本文を取り出す
        document.select("script, style, noscript, nav, header, footer, aside, form, iframe, svg").remove()
        val root = document.selectFirst("article") ?: document.selectFirst("main") ?: document.body()
        var text = root?.text().orEmpty().trim().take(MAX_TEXT_CHARS)

        if (youtube != null && youtubeText != null) {
            // YouTube のページ本体は JS アプリで本文が空に近い。og:title が無ければ動画タイトルを使う
            if (metaContent(document, "meta[property=og:title]").isNullOrBlank()) {
                title = youtube.title ?: title
            }
            text = youtubeText
            // UrlFetchWorker は description と text を連結して保存するため、完全な説明文を text に入れる場合は
            // 切り詰め済みの og:description(や共通の meta description)を重複して入れないよう null にする
            if (!youtube.description.isNullOrBlank()) description = null
        }

        return FetchedContent(
            title = title,
            description = description,
            text = text,
            imageUrl = imageUrl,
            siteName = siteName
        )
    }

    /** チャンネル名・説明文・キーワードを AI に渡す本文にまとめる。キーワードは末尾に置き、切り詰めで先に落とす。 */
    private fun youtubeText(details: YouTubeDetails): String? {
        val parts = listOfNotNull(
            details.author?.let { "チャンネル: $it" },
            details.description,
            details.keywords.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "キーワード: ")
        )
        // 説明文もチャンネル名も無い動画は、汎用の抽出結果にフォールバックする
        if (details.author == null && details.description == null) return null
        return parts.joinToString("\n\n").take(MAX_TEXT_CHARS)
    }

    /**
     * YouTube のページなら、インライン script の `ytInitialPlayerResponse` から videoDetails を取り出す。
     * YouTube でない・JSON が見つからない・壊れている場合は null(呼び出し側で汎用の抽出にフォールバックする)。
     */
    private fun youtubeDetails(document: Document, siteName: String?): YouTubeDetails? = try {
        if (isYouTube(document, siteName)) {
            document.select("script").asSequence()
                .map { it.data() }
                .filter { it.contains(PLAYER_RESPONSE_KEY) }
                .firstNotNullOfOrNull { parseVideoDetails(it) }
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }

    private fun isYouTube(document: Document, siteName: String?): Boolean {
        val host = try {
            URI(document.location()).host?.lowercase()
        } catch (_: Exception) {
            null
        }
        val hostMatches = host != null && (host == "youtube.com" || host.endsWith(".youtube.com"))
        return hostMatches || siteName.equals("YouTube", ignoreCase = true)
    }

    /**
     * script 内の `ytInitialPlayerResponse = {...}` を JSON として読み、videoDetails を返す。
     * m.youtube.com では先に `var ytInitialPlayerResponse = null;` が別 script に出てくるため、
     * 「= の直後が { で始まる」代入だけを対象にする。
     */
    private fun parseVideoDetails(script: String): YouTubeDetails? {
        for (match in PLAYER_RESPONSE_ASSIGNMENT.findAll(script)) {
            // JSONTokener は最初の値の終わり(閉じ括弧)で読み取りを止めるので、後続の `;` や JS は無視される
            val json = try {
                JSONTokener(script.substring(match.range.last)).nextValue() as? JSONObject
            } catch (_: Exception) {
                null
            } ?: continue
            val details = json.optJSONObject("videoDetails") ?: continue
            val keywords = details.optJSONArray("keywords")?.let { array ->
                (0 until array.length()).mapNotNull { index -> array.optString(index).trim().takeIf { it.isNotEmpty() } }
            }.orEmpty()
            return YouTubeDetails(
                title = stringOrNull(details, "title"),
                author = stringOrNull(details, "author"),
                description = stringOrNull(details, "shortDescription"),
                keywords = keywords
            )
        }
        return null
    }

    // 値が無い・null のときに optString が "" や "null" を返す実装差を避ける
    private fun stringOrNull(json: JSONObject, key: String): String? =
        if (json.isNull(key)) null else json.optString(key).trim().takeIf { it.isNotEmpty() }

    private const val PLAYER_RESPONSE_KEY = "ytInitialPlayerResponse"
    private val PLAYER_RESPONSE_ASSIGNMENT = Regex("""ytInitialPlayerResponse\s*=\s*\{""")

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
