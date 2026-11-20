package com.unchunks.echomark.data.linkcheck

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.components.testing.TestDispatcherProvider
import com.unchunks.echomark.data.local.AppDatabase
import com.unchunks.echomark.data.local.entity.LinkCheckEntity
import com.unchunks.echomark.data.mapper.toEntity
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.linkcheck.LinkCheckPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
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
import java.util.concurrent.TimeUnit

/** 夜間のリンクの確認(続けて失敗したときだけ切れたことにする・サイトごとの間隔と上限・通信が切れたときは記録しない)。 */
@RunWith(AndroidJUnit4::class)
class LinkCheckerTest {

    private lateinit var db: AppDatabase
    private lateinit var server: MockWebServer
    private val network = FakeNetworkStatus()
    private val day = TimeUnit.DAYS.toMillis(1)
    private val t0 = 1_700_000_000_000L
    private var clock = t0
    private val waits = mutableListOf<Long>()
    private lateinit var checker: LinkChecker

    private class FakeNetworkStatus : NetworkStatus {
        var online = true
        override fun isOnline() = online
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/gone" -> MockResponse.Builder().code(404).build()
                "/slow-error" -> MockResponse.Builder().code(503).build()
                else -> MockResponse.Builder().code(200).build()
            }
        }
        server.start()
        // 127.0.0.1 のテストサーバーに接続するため、アドレスの制限を外す
        val prober = LinkProber(OkHttpClient(), TestDispatcherProvider(Dispatchers.Unconfined)) { true }
        checker = LinkChecker(db.bookmarkDao(), db.linkCheckDao(), prober, network).also {
            it.sleeper = { millis -> waits += millis }
        }
    }

    @After
    fun tearDown() {
        server.close()
        db.close()
    }

    private suspend fun save(url: String, type: BookmarkType = BookmarkType.URL): Long =
        db.bookmarkDao().insert(
            Bookmark(type = type, title = url, contentUri = url.takeIf { type == BookmarkType.URL }, createdAt = 1, lastAccessedAt = 1).toEntity()
        )

    private fun urlOf(path: String) = server.url(path).toString()

    private suspend fun state(id: Long) = db.linkCheckDao().get(id)

    @Test
    fun 開けたリンクは記録され_次の確認までは確認しない() = runBlocking {
        val id = save(urlOf("/ok"))

        val summary = checker.checkDue { clock }

        assertEquals(1, summary.checked)
        assertEquals(0, summary.failed)
        assertEquals(LinkCheckEntity(bookmarkId = id, checkedAt = clock), state(id))
        // 次の夜(1日後)には、まだ確認の時期ではない
        clock += day
        assertEquals(0, checker.checkDue { clock }.checked)
        assertEquals(0, checker.countDue(clock))
    }

    @Test
    fun 一回開けなかっただけではリンク切れにしない() = runBlocking {
        val id = save(urlOf("/gone"))

        val summary = checker.checkDue { clock }

        assertEquals(1, summary.failed)
        assertEquals(0, summary.newlyDead)
        val stored = state(id)!!
        assertEquals(1, stored.failureCount)
        assertNull(stored.deadAt)
        assertTrue(stored.lastReason!!.contains("404"))
    }

    @Test
    fun 三晩続けて開けなかったときに初めてリンク切れにする() = runBlocking {
        val id = save(urlOf("/gone"))

        assertEquals(0, checker.checkDue { clock }.newlyDead)
        clock += day
        assertEquals(0, checker.checkDue { clock }.newlyDead)
        assertNull(state(id)!!.deadAt)
        clock += day
        val third = checker.checkDue { clock }

        assertEquals(1, third.newlyDead)
        assertEquals(clock, state(id)!!.deadAt)
        assertEquals(3, state(id)!!.failureCount)
    }

    @Test
    fun 間に開けた夜があれば失敗の数え直しになる() = runBlocking {
        // 同じ ID のまま、サーバーの応答だけが変わった想定: /gone を /ok に書き換える代わりに、状態を直接作る
        val id = save(urlOf("/ok"))
        db.linkCheckDao().upsert(
            LinkCheckEntity(bookmarkId = id, checkedAt = t0 - day, failureCount = 2, firstFailedAt = t0 - 3 * day, lastReason = "ページが見つかりません(HTTP 404)")
        )

        checker.checkDue { clock }

        val stored = state(id)!!
        assertEquals(0, stored.failureCount)
        assertNull(stored.firstFailedAt)
        assertNull(stored.deadAt)
        assertNull(stored.lastReason)
    }

    @Test
    fun 切れたリンクが開けるようになったら切れの印は消える() = runBlocking {
        val id = save(urlOf("/ok"))
        db.linkCheckDao().upsert(
            LinkCheckEntity(
                bookmarkId = id, checkedAt = t0 - 10 * day, failureCount = 3, firstFailedAt = t0 - 14 * day,
                deadAt = t0 - 12 * day, lastReason = "ページが見つかりません(HTTP 404)"
            )
        )

        checker.checkDue { clock }

        assertNull(state(id)!!.deadAt)
    }

    @Test
    fun 通信が切れていたときの失敗は記録せず_途中でやめる() = runBlocking {
        val first = save(urlOf("/ok"))
        val second = save("http://127.0.0.1:1/unreachable") // 接続できない
        val third = save(urlOf("/ok2"))
        network.online = false

        val summary = checker.checkDue { clock }

        assertTrue(summary.offline)
        // 接続できなかった時点でやめる。その失敗は記録しない
        assertNull(state(second))
        assertNull(state(third))
        // やめる前に確認できたものは記録される
        assertEquals(1, summary.checked)
        assertNotNull(state(first))
    }

    @Test
    fun 端末が通信できているなら_接続できないリンクの失敗は記録する() = runBlocking {
        val id = save("http://127.0.0.1:1/unreachable")

        val summary = checker.checkDue { clock }

        assertFalse(summary.offline)
        assertEquals(1, summary.failed)
        assertEquals(1, state(id)!!.failureCount)
    }

    @Test
    fun 確認しないリンクとリンクでないブックマークは確認しない() = runBlocking {
        val muted = save(urlOf("/gone"))
        db.linkCheckDao().upsert(LinkCheckEntity(bookmarkId = muted, muted = true))
        val memo = save("unused", type = BookmarkType.TEXT)

        val summary = checker.checkDue { clock }

        assertEquals(0, summary.checked)
        assertEquals(0, server.requestCount)
        assertNull(state(memo))
        assertEquals(0, state(muted)!!.failureCount)
    }

    @Test
    fun 同じサイトへの確認は間隔をあけ_別のサイトは待たない() = runBlocking {
        // localhost と 127.0.0.1 は別のサイトとして数える(どちらもテストサーバーに届く)
        save("http://localhost:${server.port}/a")
        save("http://localhost:${server.port}/b")
        save("http://127.0.0.1:${server.port}/c")

        checker.checkDue { clock }

        // 時計が進まない(間隔の分は待つ)ので、同じサイトの2件目の前にだけ間隔ぶん待つ
        assertEquals(listOf(LinkCheckPolicy.HOST_INTERVAL_MILLIS), waits)
    }

    @Test
    fun 同じサイトへの確認は一晩に上限まで_残りは次の夜に回る() = runBlocking {
        val ids = (1..8).map { save("http://localhost:${server.port}/page$it") }

        val first = checker.checkDue { clock }
        assertEquals(LinkCheckPolicy.MAX_PER_HOST_PER_RUN, first.checked)

        // 次の夜は、確認前のものから先に確認する
        clock += day
        val second = checker.checkDue { clock }
        assertEquals(3, second.checked)
        assertTrue(ids.all { state(it) != null })
    }

    @Test
    fun ローカルネットワークのリンクは確認せず_失敗にも数えない() = runBlocking {
        // 制限を外していない(本番と同じ)確認器
        val guarded = LinkChecker(
            db.bookmarkDao(), db.linkCheckDao(), LinkProber(OkHttpClient(), TestDispatcherProvider(Dispatchers.Unconfined)), network
        ).also { it.sleeper = { } }
        val id = save(urlOf("/gone"))

        val summary = guarded.checkDue { clock }

        assertEquals(0, server.requestCount)
        assertEquals(0, summary.failed)
        val stored = state(id)!!
        assertEquals(0, stored.failureCount)
        assertNull(stored.deadAt)
        assertEquals(clock, stored.checkedAt)
    }

    @Test
    fun 手動の確認は時期や確認しないの印を見ずに今すぐ確認する() = runBlocking {
        val id = save(urlOf("/gone"))
        db.linkCheckDao().upsert(LinkCheckEntity(bookmarkId = id, checkedAt = t0, muted = true))

        val result = checker.checkOne(id, now = t0 + 1_000)

        assertNotNull(result)
        assertEquals(1, result!!.failureCount)
        assertTrue(result.muted)
        assertEquals(1, state(id)!!.failureCount)
    }

    @Test
    fun 手動の確認でも一回では切れたことにならず_開ければ記録は消える() = runBlocking {
        val id = save(urlOf("/gone"))

        repeat(3) { checker.checkOne(id, now = t0 + it * 1_000L) }
        assertNull(state(id)!!.deadAt)

        // 2日以上たってからの失敗で、初めて切れたことになる
        checker.checkOne(id, now = t0 + 2 * day)
        assertNotNull(state(id)!!.deadAt)

        val ok = save(urlOf("/ok"))
        db.linkCheckDao().upsert(LinkCheckEntity(bookmarkId = ok, checkedAt = t0, failureCount = 2, firstFailedAt = t0 - day))
        assertEquals(0, checker.checkOne(ok, now = t0 + 1_000)!!.failureCount)
    }

    @Test
    fun 手動の確認はリンクでないものと通信できないときは記録しない() = runBlocking {
        val memo = save("unused", type = BookmarkType.TEXT)
        assertNull(checker.checkOne(memo))
        assertNull(checker.checkOne(9_999L))

        val unreachable = save("http://127.0.0.1:1/unreachable")
        network.online = false
        assertNull(checker.checkOne(unreachable))
        assertNull(state(unreachable))
    }
}
