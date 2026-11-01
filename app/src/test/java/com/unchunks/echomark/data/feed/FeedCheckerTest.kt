package com.unchunks.echomark.data.feed

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.components.testing.TestDispatcherProvider
import com.unchunks.echomark.data.local.AppDatabase
import com.unchunks.echomark.data.local.dao.SubscriptionDao
import com.unchunks.echomark.data.local.entity.SubscriptionEntity
import com.unchunks.echomark.domain.subscription.INITIAL_ITEMS_PER_FEED
import com.unchunks.echomark.domain.subscription.MAX_NEW_ITEMS_PER_FEED
import com.unchunks.echomark.domain.subscription.MAX_NEW_ITEMS_PER_RUN
import com.unchunks.echomark.domain.subscription.SubscriptionType
import com.unchunks.echomark.testing.FakeBookmarkRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** 購読しているフィードの確認(新着の判定・上限・失敗の扱い)。本物の DB とローカルのサーバーで確かめる。 */
@RunWith(AndroidJUnit4::class)
class FeedCheckerTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: SubscriptionDao
    private lateinit var server: MockWebServer
    private lateinit var repository: FakeBookmarkRepository
    private lateinit var checker: FeedChecker

    private val hour = TimeUnit.HOURS.toMillis(1)
    private val day = TimeUnit.DAYS.toMillis(1)

    /** 購読を始めた日時。確認の日時はこれより後にする */
    private val base = 1_700_000_000_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        dao = db.subscriptionDao()
        server = MockWebServer()
        server.start()
        repository = FakeBookmarkRepository()
        val fetcher = FeedFetcher(OkHttpClient(), TestDispatcherProvider(Dispatchers.Unconfined)) { true }
        checker = FeedChecker(dao, fetcher) { repository }
    }

    @After
    fun tearDown() {
        server.close()
        db.close()
    }

    /** [items] は (記事の ID, 公開日時)。新しいものが先 */
    private fun rss(title: String = "開発ブログ", items: List<Pair<String, Long?>>): String {
        val body = items.joinToString("") { (id, time) ->
            val date = time?.let { "<pubDate>${DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC))}</pubDate>" }.orEmpty()
            "<item><title>記事 $id</title><link>https://example.com/posts/$id</link><guid isPermaLink=\"false\">$id</guid>$date</item>"
        }
        return "<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>$title</title><link>https://example.com/</link>$body</channel></rss>"
    }

    private fun enqueueFeed(xml: String, code: Int = 200) {
        server.enqueue(MockResponse.Builder().code(code).addHeader("Content-Type", "application/rss+xml").body(xml).build())
    }

    private suspend fun subscribe(path: String = "/feed.xml", type: SubscriptionType = SubscriptionType.RSS, enabled: Boolean = true): Long =
        dao.insertIgnore(
            SubscriptionEntity(
                type = type.name, feedUrl = server.url(path).toString(), title = "登録時のタイトル", createdAt = base, enabled = enabled
            )
        )

    private fun savedUrls() = repository.importedLinks.map { it.url }

    @Test
    fun 初めての確認では最新の数件だけ保存し_ほかは見たことにする() = runBlocking {
        val id = subscribe()
        enqueueFeed(rss(items = (1..10).map { "p$it" to base + it * hour }))

        val summary = checker.checkAll(now = base + day)

        assertEquals(INITIAL_ITEMS_PER_FEED, summary.added)
        // 新しい順(日付の大きい順)
        assertEquals(listOf("p10", "p9", "p8").map { "https://example.com/posts/$it" }, savedUrls())
        val row = dao.getById(id)!!
        assertEquals(10, row.seenKeys.split('\n').size)
        assertEquals(base + day, row.lastSuccessAt)
        assertEquals("開発ブログ", row.title)
        assertEquals("https://example.com/", row.siteUrl)
        assertNull(row.lastError)
    }

    @Test
    fun 二回目からは見ていない新着だけ保存する() = runBlocking {
        subscribe()
        enqueueFeed(rss(items = (1..5).map { "p$it" to base + it * hour }))
        checker.checkAll(now = base + day)
        repository.importedLinks.clear()

        enqueueFeed(rss(items = listOf("p7" to base + day + 2 * hour, "p6" to base + day + hour) + (1..5).map { "p$it" to base + it * hour }))
        val summary = checker.checkAll(now = base + 2 * day)

        assertEquals(2, summary.added)
        assertEquals(listOf("p7", "p6").map { "https://example.com/posts/$it" }, savedUrls())
    }

    @Test
    fun 利用者が削除した記事は次の確認で戻らない() = runBlocking {
        subscribe()
        val items = (1..5).map { "p$it" to base + it * hour }
        enqueueFeed(rss(items = items))
        checker.checkAll(now = base + day)
        // 保存した3件をすべて削除した状態にする
        repository.bookmarks.value = emptyList()
        repository.importedLinks.clear()

        enqueueFeed(rss(items = items))
        val summary = checker.checkAll(now = base + 2 * day)

        assertEquals(0, summary.added)
        assertTrue(repository.importedLinks.isEmpty())
    }

    @Test
    fun 古い日付の見ていない記事は保存せず見たことにする() = runBlocking {
        val id = subscribe()
        enqueueFeed(rss(items = listOf("p1" to base + hour)))
        checker.checkAll(now = base + day)
        repository.importedLinks.clear()

        // フィードの ID が振り直されて、購読を始める前の古い記事が「新しい ID」で並んだ
        enqueueFeed(rss(items = listOf("new" to base + day + hour, "old1" to base - 30 * day, "old2" to base - 60 * day, "p1" to base + hour)))
        val summary = checker.checkAll(now = base + 2 * day)

        assertEquals(1, summary.added)
        assertEquals(listOf("https://example.com/posts/new"), savedUrls())
        val seen = dao.getById(id)!!.seenKeys.split('\n').toSet()
        assertTrue(seen.containsAll(listOf("old1", "old2", "new", "p1")))
    }

    @Test
    fun 日付の無い記事も新着として扱い_目印で二重に保存しない() = runBlocking {
        subscribe()
        enqueueFeed(rss(items = listOf("a" to null)))
        checker.checkAll(now = base + day)
        repository.importedLinks.clear()

        enqueueFeed(rss(items = listOf("b" to null, "a" to null)))
        val first = checker.checkAll(now = base + 2 * day)
        enqueueFeed(rss(items = listOf("b" to null, "a" to null)))
        val second = checker.checkAll(now = base + 3 * day)

        assertEquals(1, first.added)
        assertEquals(0, second.added)
        assertEquals(listOf("https://example.com/posts/b"), savedUrls())
    }

    @Test
    fun 一つのフィードから一回に保存する数に上限がある_残りは次の確認に回る() = runBlocking {
        val id = subscribe()
        enqueueFeed(rss(items = listOf("seed" to base)))
        checker.checkAll(now = base + day)
        repository.importedLinks.clear()
        val burst = (1..25).map { "n$it" to base + day + it * hour }.reversed()

        enqueueFeed(rss(items = burst + ("seed" to base)))
        assertEquals(MAX_NEW_ITEMS_PER_FEED, checker.checkAll(now = base + 2 * day).added)
        enqueueFeed(rss(items = burst + ("seed" to base)))
        assertEquals(MAX_NEW_ITEMS_PER_FEED, checker.checkAll(now = base + 3 * day).added)
        enqueueFeed(rss(items = burst + ("seed" to base)))
        assertEquals(5, checker.checkAll(now = base + 4 * day).added)

        assertEquals(25, savedUrls().toSet().size)
        assertNotNull(dao.getById(id))
    }

    @Test
    fun 全体で一回に保存する数に上限がある_確認しなかったフィードは次に先に確認する() = runBlocking {
        // 4 つのフィードに、それぞれ新着が 10 件ずつ
        val ids = (1..4).map { subscribe("/feed$it.xml") }
        // 前回確認した日時を変えて、確認の順番を決める(古いものが先)
        ids.forEachIndexed { index, id -> dao.recordSuccess(id, "f$index", null, base + index * hour, null, null, "seed$index") }
        repeat(3) { index ->
            enqueueFeed(rss("f$index", (1..10).map { "f$index-$it" to base + day + it * hour }))
        }

        val summary = checker.checkAll(now = base + 2 * day)

        assertEquals(MAX_NEW_ITEMS_PER_RUN, summary.added)
        assertEquals(3, summary.checked)
        // 4 つ目のフィードは上限に達したので確認しなかった(取得もしていない)
        assertEquals(3, server.requestCount)
        assertEquals(base + 3 * hour, dao.getById(ids[3])!!.lastCheckedAt)
    }

    @Test
    fun 保存するときは購読のタイトルとYouTubeのタグを付ける() = runBlocking {
        subscribe()
        subscribe("/yt.xml", SubscriptionType.YOUTUBE)
        enqueueFeed(rss("ブログ A", listOf("a1" to base + hour)))
        enqueueFeed(rss("チャンネル B", listOf("b1" to base + hour)))

        checker.checkAll(now = base + day)

        val byUrl = repository.importedLinks.associateBy { it.url }
        assertEquals(listOf("ブログ A"), byUrl.getValue("https://example.com/posts/a1").tags)
        assertEquals(listOf("チャンネル B", "YouTube"), byUrl.getValue("https://example.com/posts/b1").tags)
        // 一覧の先頭に新着として出るよう、保存した日時は元の公開日時を引き継がない
        assertTrue(repository.importedLinks.all { it.savedAt == null })
        assertEquals(false, repository.importedFolderAsTag)
    }

    @Test
    fun すべての新着は一回の保存にまとめる() = runBlocking {
        subscribe("/a.xml")
        subscribe("/b.xml")
        enqueueFeed(rss("A", listOf("a1" to base + hour)))
        enqueueFeed(rss("B", listOf("b1" to base + hour)))

        checker.checkAll(now = base + day)

        assertEquals(1, repository.importLinksCalls)
        assertEquals(2, repository.importedLinks.size)
    }

    @Test
    fun 変わっていなければ確認の記録だけ更新する() = runBlocking {
        val id = subscribe()
        dao.recordSuccess(id, "t", null, base, "\"v1\"", "Mon, 01 Jan 2024 00:00:00 GMT", "x")
        server.enqueue(MockResponse.Builder().code(304).build())

        val summary = checker.checkAll(now = base + day)

        assertEquals(0, summary.added)
        assertEquals(0, summary.failed)
        val row = dao.getById(id)!!
        assertEquals(base + day, row.lastCheckedAt)
        assertEquals("x", row.seenKeys)
        assertEquals("\"v1\"", server.takeRequest().headers["If-None-Match"])
    }

    @Test
    fun 取得に失敗したら理由と回数を記録して他のフィードを続ける() = runBlocking {
        val bad = subscribe("/bad.xml")
        val good = subscribe("/good.xml")
        server.enqueue(MockResponse.Builder().code(500).build())
        enqueueFeed(rss("良いフィード", listOf("g1" to base + hour)))

        val summary = checker.checkAll(now = base + day)

        assertEquals(1, summary.failed)
        assertEquals(1, summary.added)
        val failedRow = dao.getById(bad)!!
        assertEquals(1, failedRow.failureCount)
        assertTrue(failedRow.lastError!!.contains("500"))
        assertNull(dao.getById(good)!!.lastError)
    }

    @Test
    fun フィードでないページは失敗として記録する() = runBlocking {
        val id = subscribe()
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "text/html").body("<html><body>ページです</body></html>").build())

        val summary = checker.checkAll(now = base + day)

        assertEquals(1, summary.failed)
        assertTrue(dao.getById(id)!!.lastError!!.contains("フィード"))
    }

    @Test
    fun 成功したら失敗の記録を消す() = runBlocking {
        val id = subscribe()
        dao.recordFailure(id, base, "前の失敗")
        enqueueFeed(rss(items = listOf("p1" to base + hour)))

        checker.checkAll(now = base + day, manual = true)

        val row = dao.getById(id)!!
        assertEquals(0, row.failureCount)
        assertNull(row.lastError)
    }

    @Test
    fun 続けて失敗しているフィードは夜間の確認では間引き_手動なら確認する() = runBlocking {
        val id = subscribe()
        repeat(FeedChecker.BACKOFF_FAILURES) { dao.recordFailure(id, base, "失敗") }
        enqueueFeed(rss(items = listOf("p1" to base + hour)))

        val night = checker.checkAll(now = base + day)
        assertEquals(0, night.checked)
        assertEquals(0, server.requestCount)

        // 間引きの間隔を過ぎたら確認する
        val later = checker.checkAll(now = base + FeedChecker.BACKOFF_MILLIS + day)
        assertEquals(1, later.checked)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun 手動の確認は間引きを無視する() = runBlocking {
        val id = subscribe()
        repeat(FeedChecker.BACKOFF_FAILURES) { dao.recordFailure(id, base, "失敗") }
        enqueueFeed(rss(items = listOf("p1" to base + hour)))

        val summary = checker.checkOne(id, now = base + day)

        assertEquals(1, summary.checked)
    }

    @Test
    fun オフにした購読は確認しない() = runBlocking {
        val id = subscribe(enabled = false)

        val all = checker.checkAll(now = base + day)
        val one = checker.checkOne(id, now = base + day)

        assertEquals(0, all.checked)
        assertEquals(0, one.checked)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun 保存に失敗したら見た記事の目印を更新せず_次の確認でもう一度保存する() = runBlocking {
        val id = subscribe()
        enqueueFeed(rss(items = (1..3).map { "p$it" to base + it * hour }))
        repository.failImportLinks = IllegalStateException("保存できない")

        val failed = checker.checkAll(now = base + day)

        assertEquals(0, failed.added)
        assertEquals(1, failed.failed)
        val row = dao.getById(id)!!
        assertEquals("", row.seenKeys)
        assertNull(row.lastSuccessAt)
        assertEquals(1, row.failureCount)

        repository.failImportLinks = null
        enqueueFeed(rss(items = (1..3).map { "p$it" to base + it * hour }))
        assertEquals(3, checker.checkAll(now = base + 2 * day, manual = true).added)
    }

    @Test
    fun 見た記事の目印は上限の数までしか覚えない() {
        val seen = (1..FeedChecker.MAX_SEEN_KEYS + 100).joinToString("\n") { "old$it" }
        val subscription = SubscriptionEntity(type = "RSS", feedUrl = "https://example.com/f", title = "t", createdAt = 0L, seenKeys = seen, lastSuccessAt = 1L)
        val item = FeedItem("fresh", "https://example.com/fresh", "新しい", publishedAt = null)

        val plan = FeedChecker.planNewItems(subscription, listOf(item), now = 10L, limit = 10)

        val keys = plan.seenKeys.split('\n')
        assertEquals(FeedChecker.MAX_SEEN_KEYS, keys.size)
        assertEquals("fresh", keys.last())
        assertFalse("old1" in keys)
    }
}
