package com.unchunks.echomark.data.feed

import org.jsoup.Jsoup
import java.net.URI
import java.util.Locale

/**
 * 利用者が入れた URL から、購読するフィードの URL を決める(通信はしない)。
 * - YouTube のチャンネル(`/channel/UC…`)・再生リスト(`?list=…`)・チャンネル ID(`UC…`)は、フィードの URL を直接作る
 * - それ以外は、ページを取得して `<link rel="alternate" type="application/rss+xml">` などから探す([feedUrlFromHtml])。
 *   YouTube の `@名前` のページにも、このリンクがある
 */
object FeedDiscovery {

    /**
     * 入力を URL にする。前後の空白を除き、スキームが無ければ `https://` を足す。
     * http / https 以外、ホストの無いもの、空白を含むものは null。YouTube のチャンネル ID だけの入力は、チャンネルの URL にする。
     */
    fun normalizeInput(input: String): String? {
        val text = input.trim()
        if (text.isEmpty() || text.length > MAX_INPUT_CHARS) return null
        if (CHANNEL_ID.matches(text)) return "https://www.youtube.com/channel/$text"
        if (text.any { it.isWhitespace() }) return null
        val hasScheme = text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true)
        val withScheme = when {
            hasScheme -> text
            "://" in text -> return null
            else -> "https://$text"
        }
        val uri = try {
            URI(withScheme)
        } catch (e: Exception) {
            return null
        }
        val host = uri.host ?: return null
        // スキームを補った入力(「hello」など)は、ドット付きのホスト名でなければ URL とみなさない。スキームを自分で書いた入力は、そのまま受ける
        return withScheme.takeIf { hasScheme || '.' in host }
    }

    /** YouTube のチャンネル・再生リストの URL から、フィードの URL を作る。YouTube の URL でない・作れないときは null。 */
    fun youtubeFeedUrl(url: String): String? {
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            return null
        }
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (host != "youtube.com" && !host.endsWith(".youtube.com")) return null
        val path = uri.path.orEmpty()
        // 既にフィードの URL
        if (path.startsWith("/feeds/videos.xml")) return url
        CHANNEL_PATH.find(path)?.let { return "$FEED_BASE?channel_id=${it.groupValues[1]}" }
        PLAYLIST_ID.find(uri.rawQuery.orEmpty())?.let { return "$FEED_BASE?playlist_id=${it.groupValues[1]}" }
        return null
    }

    /**
     * HTML の `<link rel="alternate">` から、RSS・Atom のフィードの URL を探す(最初に見つけたもの)。
     * 相対 URL は [baseUrl] で解決し、http / https のものだけを返す。無ければ null。
     */
    fun feedUrlFromHtml(html: String, baseUrl: String): String? {
        val document = Jsoup.parse(html, baseUrl)
        for (link in document.select("link[rel~=(?i)alternate][href]")) {
            val type = link.attr("type").lowercase(Locale.ROOT)
            if (type != "application/rss+xml" && type != "application/atom+xml") continue
            val resolved = FeedParser.resolve(baseUrl, link.attr("href"))
            if (resolved != null) return resolved
        }
        return null
    }

    /** YouTube のページの HTML から、チャンネル ID(`UC…`)を探してフィードの URL にする(`<link>` が無いときの代わり)。 */
    fun youtubeFeedUrlFromHtml(html: String): String? {
        val id = CHANNEL_ID_IN_HTML.find(html)?.groupValues?.get(1) ?: return null
        return "$FEED_BASE?channel_id=$id"
    }

    private const val FEED_BASE = "https://www.youtube.com/feeds/videos.xml"
    private const val MAX_INPUT_CHARS = 2000
    private val CHANNEL_ID = Regex("UC[0-9A-Za-z_-]{22}")
    private val CHANNEL_PATH = Regex("^/channel/(UC[0-9A-Za-z_-]{22})(?:/|$)")
    private val PLAYLIST_ID = Regex("(?:^|&)list=([0-9A-Za-z_-]{10,64})(?:&|$)")
    private val CHANNEL_ID_IN_HTML = Regex("\"(?:channelId|externalId)\"\\s*:\\s*\"(UC[0-9A-Za-z_-]{22})\"")
}
