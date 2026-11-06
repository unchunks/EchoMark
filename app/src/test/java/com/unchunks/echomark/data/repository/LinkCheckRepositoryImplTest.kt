package com.unchunks.echomark.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.components.testing.TestDispatcherProvider
import com.unchunks.echomark.data.linkcheck.LinkChecker
import com.unchunks.echomark.data.linkcheck.LinkProber
import com.unchunks.echomark.data.linkcheck.NetworkStatus
import com.unchunks.echomark.data.local.AppDatabase
import com.unchunks.echomark.data.local.entity.LinkCheckEntity
import com.unchunks.echomark.data.mapper.toEntity
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** リンク切れの一覧・確認しない・今すぐ確認(実際の Room と、テストのサーバー)。 */
@RunWith(AndroidJUnit4::class)
class LinkCheckRepositoryImplTest {

    private lateinit var db: AppDatabase
    private lateinit var server: MockWebServer
    private lateinit var repository: LinkCheckRepositoryImpl

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        server = MockWebServer()
        server.start()
        val dispatcherProvider = TestDispatcherProvider(Dispatchers.Unconfined)
        val prober = LinkProber(OkHttpClient(), dispatcherProvider) { true }
        val checker = LinkChecker(db.bookmarkDao(), db.linkCheckDao(), prober, object : NetworkStatus {
            override fun isOnline() = true
        })
        repository = LinkCheckRepositoryImpl(db.linkCheckDao(), db.bookmarkDao(), checker, dispatcherProvider)
    }

    @After
    fun tearDown() {
        server.close()
        db.close()
    }

    private suspend fun save(title: String, url: String = "https://example.com/$title"): Long =
        db.bookmarkDao().insert(
            Bookmark(type = BookmarkType.URL, title = title, contentUri = url, createdAt = 1, lastAccessedAt = 1).toEntity()
        )

    private suspend fun markDead(id: Long, deadAt: Long, muted: Boolean = false) =
        db.linkCheckDao().upsert(
            LinkCheckEntity(
                bookmarkId = id, checkedAt = deadAt, failureCount = 3, firstFailedAt = deadAt - 1000, deadAt = deadAt,
                lastReason = "ページが見つかりません(HTTP 404)", muted = muted
            )
        )

    @Test
    fun リンク切れは新しく切れた順に並び_理由と回数が付く() = runBlocking {
        val older = save("古く切れた")
        val newer = save("新しく切れた")
        save("生きている")
        markDead(older, deadAt = 100)
        markDead(newer, deadAt = 200)

        val links = repository.observeDeadLinks().first()

        assertEquals(listOf("新しく切れた", "古く切れた"), links.map { it.bookmark.title })
        assertEquals(3, links.first().failureCount)
        assertEquals("ページが見つかりません(HTTP 404)", links.first().reason)
        assertEquals(200L, links.first().deadAt)
    }

    @Test
    fun 失敗が続いているだけで切れてはいないものは一覧に出さない() = runBlocking {
        val id = save("まだ切れていない")
        db.linkCheckDao().upsert(LinkCheckEntity(bookmarkId = id, checkedAt = 5, failureCount = 2, firstFailedAt = 1))

        assertTrue(repository.observeDeadLinks().first().isEmpty())
    }

    @Test
    fun 確認しないにすると一覧から外れ_戻すと一覧に戻る() = runBlocking {
        val id = save("切れた")
        markDead(id, deadAt = 100)

        repository.setMuted(id, true)
        assertTrue(repository.observeDeadLinks().first().isEmpty())
        // 切れた記録は消さない
        assertNotNull(db.linkCheckDao().get(id)!!.deadAt)

        repository.setMuted(id, false)
        assertEquals(1, repository.observeDeadLinks().first().size)
    }

    @Test
    fun まだ確認していないブックマークも確認しないにでき_それ以外の項目は初期のまま() = runBlocking {
        val id = save("未確認")

        repository.setMuted(id, true)

        val state = db.linkCheckDao().get(id)!!
        assertTrue(state.muted)
        assertEquals(0, state.failureCount)
        assertNull(state.deadAt)
        // 削除済みのブックマークには行を作らない
        repository.setMuted(9_999L, true)
        assertNull(db.linkCheckDao().get(9_999L))
    }

    @Test
    fun 確認しないを解除する対象が無ければ何も作らない() = runBlocking {
        val id = save("未確認")

        repository.setMuted(id, false)

        assertNull(db.linkCheckDao().get(id))
    }

    @Test
    fun ブックマークを削除すると確認の状態も消える() = runBlocking {
        val id = save("切れた")
        markDead(id, deadAt = 100)

        db.bookmarkDao().delete(db.bookmarkDao().getById(id)!!)

        assertNull(db.linkCheckDao().get(id))
        assertTrue(repository.observeDeadLinks().first().isEmpty())
    }

    @Test
    fun 今すぐ確認して開けたら切れの記録が消え_状態が流れる() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).build())
        val id = save("戻った", url = server.url("/page").toString())
        markDead(id, deadAt = 100)

        val state = repository.checkNow(id)

        assertEquals(0, state!!.failureCount)
        assertNull(repository.observeState(id).first()!!.deadAt)
        assertTrue(repository.observeDeadLinks().first().isEmpty())
    }

    @Test
    fun 状態は確認前ならnull() = runBlocking {
        val id = save("未確認")

        assertNull(repository.observeState(id).first())
    }
}
