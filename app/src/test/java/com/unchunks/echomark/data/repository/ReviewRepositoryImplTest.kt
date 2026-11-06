package com.unchunks.echomark.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unchunks.components.testing.TestDispatcherProvider
import com.unchunks.echomark.data.local.AppDatabase
import com.unchunks.echomark.data.local.entity.BookmarkEntity
import com.unchunks.echomark.domain.bookmark.model.BookmarkType
import com.unchunks.echomark.domain.review.ReviewGrade
import com.unchunks.echomark.domain.review.SpacedRepetition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class ReviewRepositoryImplTest {

    private lateinit var db: AppDatabase
    private lateinit var review: ReviewRepositoryImpl
    private lateinit var readingList: ReadingListRepositoryImpl

    private val zone = ZoneId.of("Asia/Tokyo")
    private var now = at(6, 10, 15)

    private fun at(month: Int, day: Int, hour: Int) =
        LocalDateTime.of(2026, month, day, hour, 0).atZone(zone).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        val dispatchers = TestDispatcherProvider(Dispatchers.Unconfined)
        review = ReviewRepositoryImpl(db.reviewCardDao(), db.bookmarkDao(), dispatchers).also {
            it.clock = { now }
            it.zone = zone
        }
        readingList = ReadingListRepositoryImpl(db.bookmarkDao(), dispatchers).also { it.clock = { now } }
    }

    @After
    fun tearDown() = db.close()

    private suspend fun insert(id: Long, readAt: Long? = null) = db.bookmarkDao().insert(
        BookmarkEntity(id = id, type = BookmarkType.TEXT, title = "題$id", createdAt = id, lastAccessedAt = id, readAt = readAt)
    )

    // ---- 復習 ----

    @Test
    fun 復習に入れると翌日から出る() = runBlocking {
        insert(1)
        review.enroll(1)

        val card = review.observeCards().first().single()
        assertEquals(1L, card.bookmarkId)
        assertEquals(at(6, 11, 0), card.dueAt)
        assertEquals(0, review.countDue())

        now = at(6, 11, 1)
        assertEquals(1, review.countDue())
        assertEquals(listOf(1L), review.dueCards(10).map { it.bookmarkId })
    }

    @Test
    fun 入れ直しても状態は変わらず_外すと最初からになる() = runBlocking {
        insert(1)
        review.enroll(1)
        now = at(6, 11, 9)
        review.grade(1, ReviewGrade.GOOD)
        val before = review.observeCards().first().single()

        review.enroll(1)
        assertEquals(before, review.observeCards().first().single())

        review.unenroll(1)
        assertTrue(review.observeCards().first().isEmpty())
        review.enroll(1)
        assertEquals(0, review.observeCards().first().single().repetitions)
    }

    @Test
    fun 無いブックマークは復習に入れられない_落ちない() = runBlocking {
        review.enroll(999)
        assertTrue(review.observeCards().first().isEmpty())
    }

    @Test
    fun 答えるとカードが更新される() = runBlocking {
        insert(1)
        review.enroll(1)
        now = at(6, 11, 9)

        val updated = review.grade(1, ReviewGrade.GOOD)!!
        assertEquals(1, updated.intervalDays)
        assertEquals(1, updated.repetitions)
        assertEquals(at(6, 12, 0), updated.dueAt)
        assertEquals(now, updated.lastReviewedAt)
        assertEquals(updated, review.observeCards().first().single())
        // 次の日が来るまでは出ない
        assertEquals(0, review.countDue())

        val again = review.grade(1, ReviewGrade.AGAIN)!!
        assertEquals(0, again.intervalDays)
        assertEquals(1, again.lapses)
        assertEquals(now + SpacedRepetition.RELEARN_MINUTES * 60_000L, again.dueAt)
    }

    @Test
    fun カードの無いブックマークに答えても_nullで何もしない() = runBlocking {
        insert(1)
        assertNull(review.grade(1, ReviewGrade.GOOD))
        assertTrue(review.observeCards().first().isEmpty())
    }

    @Test
    fun 日時の来たものを早い順に件数まで返す() = runBlocking {
        listOf(1L, 2L, 3L).forEach { insert(it) }
        listOf(1L, 2L, 3L).forEach { review.enroll(it) }
        // 3 → 1 → 2 の順に日時を変える
        db.reviewCardDao().insert(db.reviewCardDao().getByBookmark(1)!!.copy(dueAt = 200))
        db.reviewCardDao().insert(db.reviewCardDao().getByBookmark(2)!!.copy(dueAt = 300))
        db.reviewCardDao().insert(db.reviewCardDao().getByBookmark(3)!!.copy(dueAt = 100))
        assertEquals(listOf(3L, 1L), review.dueCards(2).map { it.bookmarkId })
        assertEquals(3, review.countDue())
    }

    @Test
    fun ブックマークを削除するとカードも消える() = runBlocking {
        insert(1)
        review.enroll(1)
        db.bookmarkDao().delete(db.bookmarkDao().getById(1)!!)
        assertTrue(review.observeCards().first().isEmpty())
    }

    // ---- あとで読む ----

    @Test
    fun あとで読むに入れると日時が入り_入れ直しても日時は変わらない() = runBlocking {
        insert(1)
        now = 1_000
        readingList.add(1)
        assertEquals(1_000L, db.bookmarkDao().getById(1)!!.readingListAt)

        now = 5_000
        readingList.add(1)
        assertEquals(1_000L, db.bookmarkDao().getById(1)!!.readingListAt)

        readingList.remove(1)
        assertNull(db.bookmarkDao().getById(1)!!.readingListAt)
    }

    @Test
    fun あとで読むから外しても読んだことにはならない() = runBlocking {
        insert(1)
        readingList.add(1)
        readingList.remove(1)
        assertNull(db.bookmarkDao().getById(1)!!.readAt)
    }

    @Test
    fun 無いブックマークを入れても落ちない() = runBlocking {
        readingList.add(404)
        assertNull(db.bookmarkDao().getById(404))
    }

    @Test
    fun 詳細画面を開く_読んだことにすると_あとで読むから外れ_未読に戻しても入り直さない() = runBlocking {
        insert(1)
        insert(2)
        insert(3)
        listOf(1L, 2L, 3L).forEach { readingList.add(it) }

        db.bookmarkDao().markOpened(1, now)
        db.bookmarkDao().markRead(2, now)

        assertNull(db.bookmarkDao().getById(1)!!.readingListAt)
        assertNull(db.bookmarkDao().getById(2)!!.readingListAt)
        assertNotNull(db.bookmarkDao().getById(3)!!.readingListAt)
        // 未読に戻しても、「あとで読む」には入り直さない
        db.bookmarkDao().markUnread(1)
        assertNull(db.bookmarkDao().getById(1)!!.readingListAt)
    }
}
