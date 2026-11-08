package com.unchunks.echomark.data.feed

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 大きなフィード(記事 100 件・本文入りで 2MB 近く)を読む時間を測る。
 * 購読の確認では1回に最大 100 件のフィードを読むので、これがメインスレッドで動くと画面が止まる(FeedChecker は背景で動かす)。
 */
class FeedParserPerformanceTest {

    private fun largeFeed(): String {
        val description = "<p>記事の本文です。段落が続きます。</p>".repeat(DESCRIPTION_REPEAT)
        val items = (1..ITEMS).joinToString("") { i ->
            "<item><title>記事 $i</title><link>https://example.com/posts/$i</link><guid>post-$i</guid>" +
                "<pubDate>Tue, 03 Jun 2025 11:05:30 GMT</pubDate><description><![CDATA[$description]]></description></item>"
        }
        return "<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>大きなブログ</title>" +
            "<link>https://example.com/</link>$items</channel></rss>"
    }

    @Test
    fun 大きなフィードを読む時間() {
        val bytes = largeFeed().toByteArray(Charsets.UTF_8)
        repeat(WARMUP) { FeedParser.parse(bytes, "UTF-8", FEED_URL) }
        val times = (1..RUNS).map {
            val start = System.nanoTime()
            val parsed = FeedParser.parse(bytes, "UTF-8", FEED_URL)
            val elapsed = (System.nanoTime() - start) / 1_000_000.0
            assertEquals(ITEMS, parsed?.items?.size)
            elapsed
        }
        println(
            "[benchmark] FeedParser.parse(${bytes.size / 1024}KB, $ITEMS 件): 中央値 %.1fms, 最大 %.1fms".format(
                times.sorted()[RUNS / 2], times.max()
            )
        )
    }

    private companion object {
        const val FEED_URL = "https://example.com/feed.xml"
        const val ITEMS = 100
        const val DESCRIPTION_REPEAT = 300
        const val WARMUP = 3
        const val RUNS = 7
    }
}
