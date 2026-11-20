package com.unchunks.echomark.data.repository

import com.unchunks.components.core.DispatcherProvider
import com.unchunks.echomark.data.local.dao.BookmarkDao
import com.unchunks.echomark.data.local.dao.ReviewCardDao
import com.unchunks.echomark.data.local.entity.ReviewCardEntity
import com.unchunks.echomark.domain.repository.ReadingListRepository
import com.unchunks.echomark.domain.repository.ReviewRepository
import com.unchunks.echomark.domain.review.ReviewCard
import com.unchunks.echomark.domain.review.ReviewGrade
import com.unchunks.echomark.domain.review.SpacedRepetition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.time.ZoneId
import javax.inject.Inject

class ReviewRepositoryImpl @Inject constructor(
    private val reviewCardDao: ReviewCardDao,
    private val bookmarkDao: BookmarkDao,
    private val dispatcherProvider: DispatcherProvider
) : ReviewRepository {

    /** 現在時刻(テストで差し替える) */
    internal var clock: () -> Long = System::currentTimeMillis

    /** 次の復習の日時を日付の区切りにそろえるタイムゾーン(テストで差し替える) */
    internal var zone: ZoneId = ZoneId.systemDefault()

    override fun observeCards(): Flow<List<ReviewCard>> =
        reviewCardDao.observeAll().map { list -> list.map { it.toDomain() } }.flowOn(dispatcherProvider.io)

    override suspend fun enroll(bookmarkId: Long): Unit = withContext(dispatcherProvider.io) {
        if (reviewCardDao.getByBookmark(bookmarkId) != null) return@withContext
        // 削除済みのブックマークには入れない(外部キーの制約で失敗するため)
        if (!bookmarkDao.exists(bookmarkId)) return@withContext
        reviewCardDao.insert(SpacedRepetition.newCard(bookmarkId, clock(), zone).toEntity())
    }

    override suspend fun unenroll(bookmarkId: Long): Unit = withContext(dispatcherProvider.io) {
        reviewCardDao.delete(bookmarkId)
    }

    override suspend fun dueCards(limit: Int): List<ReviewCard> = withContext(dispatcherProvider.io) {
        reviewCardDao.getDue(clock(), limit).map { it.toDomain() }
    }

    override suspend fun countDue(): Int = withContext(dispatcherProvider.io) {
        reviewCardDao.getDue(clock(), Int.MAX_VALUE).size
    }

    override suspend fun grade(bookmarkId: Long, grade: ReviewGrade): ReviewCard? = withContext(dispatcherProvider.io) {
        val card = reviewCardDao.getByBookmark(bookmarkId)?.toDomain() ?: return@withContext null
        SpacedRepetition.next(card, grade, clock(), zone).also { reviewCardDao.insert(it.toEntity()) }
    }
}

class ReadingListRepositoryImpl @Inject constructor(
    private val bookmarkDao: BookmarkDao,
    private val dispatcherProvider: DispatcherProvider
) : ReadingListRepository {

    internal var clock: () -> Long = System::currentTimeMillis

    override suspend fun add(bookmarkId: Long): Unit = withContext(dispatcherProvider.io) {
        val existing = bookmarkDao.getById(bookmarkId) ?: return@withContext
        // すでに入っていれば、入れた日時(並ぶ順)は変えない
        if (existing.readingListAt == null) bookmarkDao.setReadingListAt(bookmarkId, clock())
    }

    override suspend fun remove(bookmarkId: Long): Unit = withContext(dispatcherProvider.io) {
        bookmarkDao.setReadingListAt(bookmarkId, null)
    }
}

internal fun ReviewCardEntity.toDomain() = ReviewCard(
    bookmarkId = bookmarkId, dueAt = dueAt, intervalDays = intervalDays, easePermille = easePermille,
    repetitions = repetitions, lapses = lapses, lastReviewedAt = lastReviewedAt, createdAt = createdAt
)

internal fun ReviewCard.toEntity() = ReviewCardEntity(
    bookmarkId = bookmarkId, dueAt = dueAt, intervalDays = intervalDays, easePermille = easePermille,
    repetitions = repetitions, lapses = lapses, lastReviewedAt = lastReviewedAt, createdAt = createdAt
)
