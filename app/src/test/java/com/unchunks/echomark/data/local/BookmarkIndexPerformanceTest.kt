package com.unchunks.echomark.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.data.local.entity.BookmarkEntity
import com.unchunks.echomark.domain.bookmark.model.AiStatus
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * bookmarks のインデックス(v30: aiStatus・lastAccessedAt・createdAt)の効き目を測る。
 * 5,000 件(本文 約 2,000 字、処理待ち 32 件・失敗 10 件)のファイルの DB で、よく読み直す問い合わせを、インデックスありと、
 * 同じ DB からインデックスを消したもの(v29 まで)とで比べる。結果が同じことも確かめる。
 */
@RunWith(AndroidJUnit4::class)
class BookmarkIndexPerformanceTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // 端末と同じくファイルの DB で測る(メモリ上の DB では、本文のはみ出したページを読み飛ばす手間がほとんど見えない)
        context.deleteDatabase(DB_NAME)
        db = Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME).build()
    }

    @After
    fun tearDown() {
        db.close()
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(DB_NAME)
    }

    private suspend fun seed(count: Int) {
        val body = "本文の文章です。".repeat(BODY_REPEAT)
        db.withTransaction {
            repeat(count) { i ->
                db.bookmarkDao().insert(
                    BookmarkEntity(
                        type = BookmarkType.URL,
                        content = body,
                        contentUri = "https://example.com/posts/$i",
                        title = "記事 $i",
                        // 保存した順と開いた順はばらばら
                        createdAt = (i * 7919L) % 100_000 + 1,
                        lastAccessedAt = (i * 104_729L) % 100_000 + 1,
                        aiStatus = when {
                            i % 160 == 0 -> AiStatus.PENDING
                            i % 500 == 1 -> AiStatus.FAILED
                            else -> AiStatus.DONE
                        },
                        readAt = if (i % 3 == 0) 1L else null
                    )
                )
            }
        }
    }

    private class Measured(val name: String, val millis: Double, val result: List<Long>)

    private fun measure(name: String, block: suspend () -> List<Long>): Measured {
        var result = emptyList<Long>()
        repeat(WARMUP) { runBlocking { result = block() } }
        val times = (1..RUNS).map {
            val start = System.nanoTime()
            runBlocking { result = block() }
            (System.nanoTime() - start) / 1_000_000.0
        }
        return Measured(name, times.sorted()[RUNS / 2], result)
    }

    private fun measureAll(): List<Measured> {
        val dao = db.bookmarkDao()
        return listOf(
            measure("再発見ウィジェットの候補(古い順 30 件)") { dao.observeLeastRecentlyAccessed(30).first().map { it.id } },
            measure("再発見の通知の候補(getStale)") { dao.getStale(threshold = 50_000, limit = 30).map { it.id } },
            measure("次に読むの候補(未読の新しい順 30 件)") { dao.observeUnread(30).first().map { it.bookmark.id } },
            measure("最近読んだもの(20 件)") { dao.observeRecentlyRead(20).first().map { it.bookmark.id } },
            measure("処理の状況(処理待ち・処理中)") { dao.observeProcessingQueue().first().map { it.id } },
            measure("処理に失敗したもの(20 件)") { dao.observeRecentFailures(20).first().map { it.id } }
        )
    }

    private fun plan(sql: String): String =
        db.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN $sql").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("detail"))) }
        }.joinToString(" / ")

    @Test
    fun インデックスでよく読み直す問い合わせが速くなり_結果は変わらない() = runBlocking {
        seed(BOOKMARKS)
        val queueSql = "SELECT id FROM bookmarks WHERE aiStatus IN ('PENDING', 'PROCESSING')"
        val widgetSql = "SELECT id FROM bookmarks ORDER BY lastAccessedAt ASC, id ASC LIMIT 30"
        val withPlans = listOf(plan(queueSql), plan(widgetSql))
        val with = measureAll()

        db.openHelper.writableDatabase.apply {
            execSQL("DROP INDEX `index_bookmarks_aiStatus`")
            execSQL("DROP INDEX `index_bookmarks_lastAccessedAt`")
            execSQL("DROP INDEX `index_bookmarks_createdAt`")
        }
        val withoutPlans = listOf(plan(queueSql), plan(widgetSql))
        val without = measureAll()

        println("[benchmark] bookmarks のインデックス(既存 $BOOKMARKS 件。インデックスなし → あり、中央値)")
        with.zip(without).forEach { (w, wo) ->
            println("[benchmark]   %s: %.1fms → %.1fms".format(w.name, wo.millis, w.millis))
            assertEquals(w.name, wo.result, w.result)
        }
        println("[benchmark]   処理の状況の計画: ${withoutPlans[0]} → ${withPlans[0]}")
        println("[benchmark]   再発見の候補の計画: ${withoutPlans[1]} → ${withPlans[1]}")
        assertTrue(withPlans[0], withPlans[0].contains("index_bookmarks_aiStatus"))
        assertTrue(withPlans[1], withPlans[1].contains("index_bookmarks_lastAccessedAt"))
    }

    private companion object {
        const val DB_NAME = "index-benchmark.db"
        const val BOOKMARKS = 5_000
        const val BODY_REPEAT = 250
        const val WARMUP = 2
        const val RUNS = 5
    }
}
