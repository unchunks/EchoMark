package com.unchunks.echomark.data.subscription

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.components.testing.TestDispatcherProvider
import com.unchunks.echomark.data.feed.FeedChecker
import com.unchunks.echomark.data.feed.FeedFetcher
import com.unchunks.echomark.data.local.AppDatabase
import com.unchunks.echomark.data.local.dao.SubscriptionDao
import com.unchunks.echomark.data.local.entity.SubscriptionEntity
import com.unchunks.echomark.domain.subscription.MAX_SUBSCRIPTIONS
import com.unchunks.echomark.domain.subscription.SubscribeOutcome
import com.unchunks.echomark.domain.subscription.SubscriptionType
import com.unchunks.echomark.testing.FakeBookmarkRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 購読の追加(フィードの探し方)・オン/オフ・削除・いま確認する。本物の DB とローカルのサーバーで確かめる。 */
@RunWith(AndroidJUnit4::class)
class SubscriptionRepositoryImplTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: SubscriptionDao
    private lateinit var server: MockWebServer
    private lateinit var bookmarks: FakeBookmarkRepository
    private lateinit var repository: SubscriptionRepositoryImpl

    private val dispatchers = TestDispatcherProvider(Dispatchers.Unconfined)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        dao = db.subscriptionDao()
        server = MockWebServer()
        server.start()
        bookmarks = FakeBookmarkRepository()
        repository = repositoryWith(FeedFetcher(OkHttpClient(), dispatchers) { true })
    }

    private fun repositoryWith(fetcher: FeedFetcher) =
        SubscriptionRepositoryImpl(dao, fetcher, FeedChecker(dao, fetcher) { bookmarks }, dispatchers)

    @After
    fun tearDown() {
        server.close()
        db.close()
    }

    private val rss = "<rss version=\"2.0\"><channel><title>開発ブログ</title><link>https://example.com/</link>" +
        "<item><title>記事</title><link>https://example.com/1</link><pubDate>Tue, 03 Jun 2008 11:05:30 GMT</pubDate></item></channel></rss>"

    private fun feedResponse(xml: String = rss) =
        MockResponse.Builder().addHeader("Content-Type", "application/rss+xml").body(xml).build()

    private fun pageResponse(html: String) =
        MockResponse.Builder().addHeader("Content-Type", "text/html; charset=utf-8").body(html).build()

    @Test
    fun フィードのURLを直接入れると購読できる() = runBlocking {
        server.enqueue(feedResponse())
        val url = server.url("/feed.xml").toString()

        val outcome = repository.subscribe(url)

        val added = outcome as SubscribeOutcome.Added
        assertEquals("開発ブログ", added.title)
        assertEquals(SubscriptionType.RSS, added.type)
        val row = dao.getById(added.id)!!
        assertEquals(url, row.feedUrl)
        assertEquals("https://example.com/", row.siteUrl)
        assertTrue(row.enabled)
        assertEquals(null, row.lastCheckedAt)
    }

    @Test
    fun サイトのURLからフィードを探して購読する() = runBlocking {
        server.enqueue(
            pageResponse(
                "<html><head><link rel=\"alternate\" type=\"application/rss+xml\" href=\"/rss.xml\"><title>サイト</title></head></html>"
            )
        )
        server.enqueue(feedResponse())

        val outcome = repository.subscribe(server.url("/blog/").toString())

        val added = outcome as SubscribeOutcome.Added
        // 保存するのは、ページではなく見つけたフィードの URL
        assertEquals(server.url("/rss.xml").toString(), dao.getById(added.id)!!.feedUrl)
    }

    @Test
    fun フィードが見つからないページは追加できない() = runBlocking {
        server.enqueue(pageResponse("<html><head><title>ただのページ</title></head><body>リンクなし</body></html>"))

        val outcome = repository.subscribe(server.url("/page").toString())

        assertTrue(outcome is SubscribeOutcome.Failed)
        assertTrue((outcome as SubscribeOutcome.Failed).message.contains("見つかりません"))
        assertEquals(0, dao.count())
    }

    @Test
    fun 見つけたリンク先がフィードでなければ追加できない() = runBlocking {
        server.enqueue(pageResponse("<link rel=\"alternate\" type=\"application/rss+xml\" href=\"/not-a-feed\">"))
        server.enqueue(pageResponse("<html>これもページ</html>"))

        val outcome = repository.subscribe(server.url("/page").toString())

        assertTrue(outcome is SubscribeOutcome.Failed)
        assertEquals(0, dao.count())
    }

    @Test
    fun 同じフィードを二重に購読しない_リダイレクト先が同じでも() = runBlocking {
        server.enqueue(feedResponse())
        val url = server.url("/feed.xml").toString()
        repository.subscribe(url)
        server.enqueue(MockResponse.Builder().code(301).addHeader("Location", "/feed.xml").build())
        server.enqueue(feedResponse())

        val outcome = repository.subscribe(server.url("/old-feed.xml").toString())

        assertEquals(SubscribeOutcome.Duplicate("開発ブログ"), outcome)
        assertEquals(1, dao.count())
    }

    @Test
    fun URLの形が正しくない入力は断る() = runBlocking {
        listOf("", "ただの文章", "ftp://example.com/feed", "javascript:alert(1)").forEach { input ->
            val outcome = repository.subscribe(input)
            assertTrue(input, outcome is SubscribeOutcome.Failed)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun 取得に失敗したら理由つきで断る() = runBlocking {
        server.enqueue(MockResponse.Builder().code(404).build())

        val outcome = repository.subscribe(server.url("/missing.xml").toString())

        assertTrue((outcome as SubscribeOutcome.Failed).message.contains("404"))
    }

    @Test
    fun 購読の数の上限を超えたら追加しない() = runBlocking {
        repeat(MAX_SUBSCRIPTIONS) { dao.insertIgnore(SubscriptionEntity(type = "RSS", feedUrl = "https://example.com/f$it", title = "f$it", createdAt = it.toLong())) }

        val outcome = repository.subscribe(server.url("/feed.xml").toString())

        assertTrue((outcome as SubscribeOutcome.Failed).message.contains("$MAX_SUBSCRIPTIONS"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun ローカルネットワークのフィードは購読できない() = runBlocking {
        // 制限を外していない本番の設定
        val production = repositoryWith(FeedFetcher(OkHttpClient(), dispatchers))
        server.enqueue(feedResponse())

        val outcome = production.subscribe(server.url("/feed.xml").toString())

        assertTrue(outcome is SubscribeOutcome.Failed)
        assertEquals(0, dao.count())
    }

    @Test
    fun オンオフと削除と一覧() = runBlocking {
        server.enqueue(feedResponse())
        val id = (repository.subscribe(server.url("/feed.xml").toString()) as SubscribeOutcome.Added).id

        repository.setEnabled(id, false)
        assertFalse(repository.observeAll().first().single().enabled)

        repository.remove(id)
        assertTrue(repository.observeAll().first().isEmpty())
    }

    @Test
    fun いま確認すると新着を保存する() = runBlocking {
        server.enqueue(feedResponse())
        val id = (repository.subscribe(server.url("/feed.xml").toString()) as SubscribeOutcome.Added).id
        server.enqueue(feedResponse())

        val summary = repository.checkNow(id)

        assertEquals(1, summary.added)
        assertEquals(listOf("https://example.com/1"), bookmarks.importedLinks.map { it.url })
    }

    @Test
    fun すべてを確認すると無効な購読は確認しない() = runBlocking {
        server.enqueue(feedResponse())
        val on = (repository.subscribe(server.url("/on.xml").toString()) as SubscribeOutcome.Added).id
        server.enqueue(feedResponse("<rss version=\"2.0\"><channel><title>別</title></channel></rss>"))
        val off = (repository.subscribe(server.url("/off.xml").toString()) as SubscribeOutcome.Added).id
        repository.setEnabled(off, false)
        server.enqueue(feedResponse())

        val summary = repository.checkNow(null)

        assertEquals(1, summary.checked)
        assertEquals(1, summary.added)
        assertEquals(on, dao.getEnabled().single().id)
    }
}
