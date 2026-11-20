package com.unchunks.echomark.data.feed

import com.unchunks.echomark.domain.subscription.SubscriptionType
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream
import java.net.URI
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * フィードの1件の記事・動画。
 * @property key 同じ記事を見分ける目印(記事の ID。無ければ URL)
 * @property url 記事のページの URL(http / https)
 */
data class FeedItem(val key: String, val url: String, val title: String?, val publishedAt: Long?)

/** 読み取ったフィード */
data class ParsedFeed(val type: SubscriptionType, val title: String, val siteUrl: String?, val items: List<FeedItem>)

/**
 * RSS 2.0・RSS 1.0(RDF)・Atom のフィード(YouTube のチャンネル・再生リストのフィードも Atom)を読む。
 *
 * フィードは信頼できない入力なので、次のように扱う:
 * - XML は jsoup の XML パーサーで読む(外部の実体・DTD を取りに行かない。本文を読むだけ)
 * - 記事は先頭から [MAX_ITEMS_PER_FEED] 件までしか読まない。タイトルは [MAX_TITLE_CHARS] 文字までに切る
 * - 記事の URL は http / https だけ(`javascript:` やスキームの無い相対 URL は、フィードの URL で解決してから確かめる)
 * - HTML や、フィードでない XML は null
 */
object FeedParser {

    fun parse(xml: String, feedUrl: String): ParsedFeed? = parse(xml.toByteArray(Charsets.UTF_8), "UTF-8", feedUrl)

    /** [bytes] を [charsetName](分からなければ null。XML の宣言から判定する)で読む。 */
    fun parse(bytes: ByteArray, charsetName: String?, feedUrl: String): ParsedFeed? {
        val document = try {
            Jsoup.parse(ByteArrayInputStream(bytes), charsetName, feedUrl, Parser.xmlParser())
        } catch (e: Exception) {
            return null
        }
        val root = document.children().firstOrNull() ?: return null
        val type = if (isYouTubeFeedUrl(feedUrl)) SubscriptionType.YOUTUBE else SubscriptionType.RSS
        return when (root.tagName().lowercase(Locale.ROOT)) {
            "rss", "rdf:rdf" -> parseRss(root, feedUrl, type)
            "feed" -> parseAtom(root, feedUrl, type)
            else -> null
        }
    }

    private fun parseRss(root: Element, feedUrl: String, type: SubscriptionType): ParsedFeed {
        val channel = root.getElementsByTag("channel").firstOrNull() ?: root
        val title = channel.childText("title")?.let(::clean) ?: hostOf(feedUrl) ?: feedUrl
        val siteUrl = channel.childText("link")?.let { resolve(feedUrl, it) }
        val items = root.getElementsByTag("item").take(MAX_ITEMS_PER_FEED).mapNotNull { item ->
            val guid = item.childText("guid")
            // feedburner の元の URL があればそれを使う(計測用の中継 URL より元のページの方がよい)
            val link = item.childText("feedburner:origlink", "link")
                ?: guid?.takeIf { it.startsWith("http", ignoreCase = true) }
            val url = link?.let { resolve(feedUrl, it) } ?: return@mapNotNull null
            FeedItem(
                key = keyOf(guid ?: url),
                url = url,
                title = item.childText("title")?.let(::clean),
                publishedAt = parseFeedDate(item.childText("pubdate", "dc:date", "published", "updated"))
            )
        }
        return ParsedFeed(type, title, siteUrl, items)
    }

    private fun parseAtom(root: Element, feedUrl: String, type: SubscriptionType): ParsedFeed {
        val title = root.childText("title")?.let(::clean) ?: hostOf(feedUrl) ?: feedUrl
        val siteUrl = root.alternateLink()?.let { resolve(feedUrl, it) }
        val items = root.children().filter { it.tagName().equals("entry", ignoreCase = true) }
            .take(MAX_ITEMS_PER_FEED)
            .mapNotNull { entry ->
                val videoId = entry.childText("yt:videoid")
                val link = entry.alternateLink()
                    ?: videoId?.let { "https://www.youtube.com/watch?v=$it" }
                val url = link?.let { resolve(feedUrl, it) } ?: return@mapNotNull null
                FeedItem(
                    key = keyOf(entry.childText("id") ?: url),
                    url = url,
                    title = entry.childText("title")?.let(::clean),
                    publishedAt = parseFeedDate(entry.childText("published", "updated", "dc:date"))
                )
            }
        return ParsedFeed(type, title, siteUrl, items)
    }

    // ---- 部品 ----

    /** 直下の子のうち、名前(接頭辞つきでもよい。大文字小文字は区別しない)が [names] のどれかの最初の要素の文字。空なら null */
    private fun Element.childText(vararg names: String): String? =
        names.firstNotNullOfOrNull { name ->
            children().firstOrNull { it.tagName().equals(name, ignoreCase = true) }?.text()?.trim()?.takeIf { it.isNotEmpty() }
        }

    /** Atom の `<link rel="alternate" href>`(rel が無ければ alternate とみなす)。無ければ null */
    private fun Element.alternateLink(): String? =
        children().filter { it.tagName().equals("link", ignoreCase = true) }
            .firstOrNull { val rel = it.attr("rel"); rel.isEmpty() || rel.equals("alternate", ignoreCase = true) }
            ?.attr("href")?.trim()?.takeIf { it.isNotEmpty() }

    /** 文字列から、改行・連続した空白をまとめ、タグを除き、[MAX_TITLE_CHARS] 文字までにする */
    internal fun clean(text: String): String {
        val plain = if (text.contains('<')) Jsoup.parse(text).text() else text
        return plain.replace(Regex("\\s+"), " ").trim().take(MAX_TITLE_CHARS)
    }

    /** 記事の目印。空白・改行は1つの空白にし、長すぎるものは切る(見た記事の目印として保存するため) */
    internal fun keyOf(raw: String): String = raw.replace(Regex("\\s+"), " ").trim().take(MAX_KEY_CHARS)

    /** [link] を [base] で解決し、http / https の絶対 URL だけを返す。それ以外は null。 */
    internal fun resolve(base: String, link: String): String? {
        val trimmed = link.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_URL_CHARS || trimmed.any { it.isWhitespace() }) return null
        val resolved = try {
            URI(base).resolve(URI(trimmed))
        } catch (e: Exception) {
            return null
        }
        val scheme = resolved.scheme?.lowercase(Locale.ROOT)
        if ((scheme != "http" && scheme != "https") || resolved.host.isNullOrEmpty()) return null
        return resolved.toString()
    }

    private fun hostOf(url: String): String? = try {
        URI(url).host
    } catch (e: Exception) {
        null
    }

    /** RFC 822 / RFC 1123(`Tue, 03 Jun 2008 11:05:30 GMT`)と ISO 8601 の日時を、エポックミリ秒にする。読めなければ null */
    internal fun parseFeedDate(raw: String?): Long? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        return tryParse { OffsetDateTime.parse(text).toInstant().toEpochMilli() }
            ?: tryParse { Instant.parse(text).toEpochMilli() }
            ?: tryParse { ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
            ?: tryParse { ZonedDateTime.parse(text, RFC_822_WITH_ZONE_NAME).toInstant().toEpochMilli() }
            ?: tryParse { LocalDateTime.parse(text.replace(' ', 'T')).toInstant(ZoneOffset.UTC).toEpochMilli() }
    }

    private inline fun tryParse(block: () -> Long): Long? = try {
        block().takeIf { it > 0 }
    } catch (e: DateTimeParseException) {
        null
    } catch (e: ArithmeticException) {
        null
    }

    /** `Tue, 3 Jun 2008 11:05 EST` のように、秒が無い・タイムゾーンが略称の日時 */
    private val RFC_822_WITH_ZONE_NAME: DateTimeFormatter =
        DateTimeFormatter.ofPattern("[EEE, ]d MMM yyyy HH:mm[:ss] z", Locale.ENGLISH)

    /** フィードの URL が YouTube のフィード(チャンネル・再生リスト)か */
    fun isYouTubeFeedUrl(url: String): Boolean = isYouTubeHost(url) && url.contains("/feeds/videos.xml")

    private fun isYouTubeHost(url: String): Boolean {
        val host = hostOf(url)?.lowercase(Locale.ROOT) ?: return false
        return host == "youtube.com" || host.endsWith(".youtube.com")
    }

    /** 1つのフィードから読む記事の最大数(巨大なフィードで時間とメモリを使わない) */
    const val MAX_ITEMS_PER_FEED = 100
    private const val MAX_TITLE_CHARS = 300
    private const val MAX_KEY_CHARS = 300
    private const val MAX_URL_CHARS = 2000
}
