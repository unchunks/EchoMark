package com.unchunks.echomark.testing

import com.unchunks.echomark.domain.repository.ReadingListRepository
import com.unchunks.echomark.domain.repository.ReviewRepository
import com.unchunks.echomark.domain.review.ReviewCard
import com.unchunks.echomark.domain.review.ReviewGrade
import com.unchunks.echomark.domain.review.SpacedRepetition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.ZoneId

/** 復習の Fake。カードをメモリに持ち、時刻は [now]、日付の区切りは [zone] で決める。[failure] があれば書き込みで投げる。 */
class FakeReviewRepository : ReviewRepository {
    val cards = MutableStateFlow<List<ReviewCard>>(emptyList())
    var now: Long = 0L
    var zone: ZoneId = ZoneId.of("Asia/Tokyo")
    var failure: Throwable? = null

    /** 呼ばれた操作(順に) */
    val calls = mutableListOf<String>()

    override fun observeCards(): Flow<List<ReviewCard>> = cards.map { list -> list.sortedWith(compareBy({ it.dueAt }, { it.bookmarkId })) }

    override suspend fun enroll(bookmarkId: Long) {
        failure?.let { throw it }
        calls += "enroll $bookmarkId"
        if (cards.value.none { it.bookmarkId == bookmarkId }) cards.value = cards.value + SpacedRepetition.newCard(bookmarkId, now, zone)
    }

    override suspend fun unenroll(bookmarkId: Long) {
        failure?.let { throw it }
        calls += "unenroll $bookmarkId"
        cards.value = cards.value.filter { it.bookmarkId != bookmarkId }
    }

    override suspend fun dueCards(limit: Int): List<ReviewCard> = SpacedRepetition.due(cards.value, now).take(limit)

    override suspend fun countDue(): Int = SpacedRepetition.due(cards.value, now).size

    override suspend fun grade(bookmarkId: Long, grade: ReviewGrade): ReviewCard? {
        failure?.let { throw it }
        calls += "grade $bookmarkId $grade"
        val card = cards.value.firstOrNull { it.bookmarkId == bookmarkId } ?: return null
        val next = SpacedRepetition.next(card, grade, now, zone)
        cards.value = cards.value.map { if (it.bookmarkId == bookmarkId) next else it }
        return next
    }
}

/** 「あとで読む」の Fake。入れた ID を [ids] に持つ。 */
class FakeReadingListRepository : ReadingListRepository {
    val ids = mutableListOf<Long>()
    var failure: Throwable? = null

    override suspend fun add(bookmarkId: Long) {
        failure?.let { throw it }
        if (bookmarkId !in ids) ids += bookmarkId
    }

    override suspend fun remove(bookmarkId: Long) {
        failure?.let { throw it }
        ids -= bookmarkId
    }
}
