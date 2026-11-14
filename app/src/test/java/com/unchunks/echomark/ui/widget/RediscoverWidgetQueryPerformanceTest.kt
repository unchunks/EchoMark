package com.unchunks.echomark.ui.widget

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.echomark.data.local.AppDatabase
import com.unchunks.echomark.data.local.entity.BookmarkEntity
import com.unchunks.echomark.data.mapper.toDomain
import com.unchunks.echomark.domain.bookmark.model.AiStatus
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.model.TagSource
import com.unchunks.echomark.domain.rediscover.RediscoverSelector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * 再発見ウィジェットの候補の読み方を比べる。5,000 件(本文 約 2,000 字・タグ 2 つ)のある DB で、
 * これまでの全件の読み込み(getAllWithTags。本文・タグごと)と、古い順の 30 件を出すのに要る列だけ読むもの
 * (observeLeastRecentlyAccessed)とで、選ばれるものが同じで、1回の読み直しがどれだけ軽くなるかを測る。
 * ウィジェットの更新は、ブックマークが変わるたびにこれを読み直す。
 */
@RunWith(AndroidJUnit4::class)
class RediscoverWidgetQueryPerformanceTest {

    private lateinit var db: AppDatabase
    private val day = TimeUnit.DAYS.toMillis(1)
    private val now = TimeUnit.DAYS.toMillis(20_000)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** 最後に開いた日時がばらばら(同じ日時のものもある)で、1割はアーカイブ済み */
    private suspend fun seed(count: Int) {
        val body = "本文の文章です。".repeat(BODY_REPEAT)
        db.withTransaction {
            repeat(count) { i ->
                val accessed = now - ((i * 7919L) % 400) * day
                val id = db.bookmarkDao().insert(
                    BookmarkEntity(
                        type = if (i % 5 == 0) BookmarkType.TEXT else BookmarkType.URL,
                        content = body,
                        contentUri = if (i % 5 == 0) null else "https://site$i.example.com/posts/$i",
                        title = "記事 $i",
                        siteName = if (i % 3 == 0) "サイト $i" else null,
                        createdAt = accessed,
                        lastAccessedAt = accessed,
                        isArchived = i % 10 == 0,
                        aiStatus = AiStatus.DONE
                    )
                )
                db.tagDao().addTagsToBookmark(id, listOf("タグ${i % 50}", "分野${i % 7}"), TagSource.USER)
            }
        }
    }

    private fun medianMillis(block: () -> Unit): Double {
        repeat(WARMUP) { block() }
        val times = (1..RUNS).map {
            val start = System.nanoTime()
            block()
            (System.nanoTime() - start) / 1_000_000.0
        }
        return times.sorted()[RUNS / 2]
    }

    @Test
    fun 古い順の候補だけを読んでも全件から選ぶのと同じものを選び_読み直しが軽い() = runBlocking {
        seed(BOOKMARKS)
        val dao = db.bookmarkDao()

        // 日をずらしても(候補の回り方が変わっても)同じものを選ぶ
        for (offset in 0 until DAYS_TO_COMPARE) {
            val today = now + offset * day
            val fromAll = RediscoverWidgetData.select(dao.getAllWithTags().first().map { it.toDomain() }, today)
            val fromLight = RediscoverWidgetData.select(
                dao.observeLeastRecentlyAccessed(RediscoverWidgetData.CANDIDATE_LIMIT).first().map { it.toDomain() },
                today
            )
            assertEquals(RediscoverWidgetData.toItems(fromAll), RediscoverWidgetData.toItems(fromLight))
            assertEquals(RediscoverSelector.MAX_ITEMS, fromLight.size)
        }

        var allChars = 0L
        val allMillis = medianMillis {
            runBlocking { allChars = dao.getAllWithTags().first().sumOf { (it.bookmark.content?.length ?: 0).toLong() } }
        }
        var lightRows = 0
        val lightMillis = medianMillis {
            runBlocking { lightRows = dao.observeLeastRecentlyAccessed(RediscoverWidgetData.CANDIDATE_LIMIT).first().size }
        }
        println(
            "[benchmark] 再発見ウィジェットの候補の読み直し1回(既存 $BOOKMARKS 件): 全件 %.1fms(本文 %,d 字・タグ込み)→ 古い順 %d 件の列だけ %.1fms".format(
                allMillis, allChars, lightRows, lightMillis
            )
        )
        assertEquals(RediscoverWidgetData.CANDIDATE_LIMIT, lightRows)
        assertTrue("軽い読み方 %.1fms・全件 %.1fms".format(lightMillis, allMillis), lightMillis < allMillis)
    }

    private companion object {
        const val BOOKMARKS = 5_000
        const val BODY_REPEAT = 250
        const val DAYS_TO_COMPARE = 5
        const val WARMUP = 2
        const val RUNS = 5
    }
}
