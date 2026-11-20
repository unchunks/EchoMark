package com.unchunks.echomark.domain.repository

import com.unchunks.echomark.domain.review.ReviewCard
import com.unchunks.echomark.domain.review.ReviewGrade
import kotlinx.coroutines.flow.Flow

/**
 * 復習(間隔をあけて思い出す。#90)。復習に入れるのは利用者が選んだブックマークだけ(自動では入れない)。
 * カードはバックアップに含め、ブックマークを削除すると消える。
 */
interface ReviewRepository {
    /** 復習に入れたすべてのカード(次の復習の日時が早い順) */
    fun observeCards(): Flow<List<ReviewCard>>

    /** 復習に入れる(すでに入っていれば何もしない。最初の復習は翌日から)。ブックマークが無ければ何もしない。 */
    suspend fun enroll(bookmarkId: Long)

    /** 復習から外す(次に入れ直すときは、最初から始める)。 */
    suspend fun unenroll(bookmarkId: Long)

    /** 日時が来ているカード(早い順)。[limit] 件まで */
    suspend fun dueCards(limit: Int): List<ReviewCard>

    /** 日時が来ているカードの数 */
    suspend fun countDue(): Int

    /** [bookmarkId] のカードに [grade] で答え、次の復習の日時などを更新する。更新後のカード(カードが無ければ null)。 */
    suspend fun grade(bookmarkId: Long, grade: ReviewGrade): ReviewCard?
}

/** 「あとで読む」(#92)に入れる・外す。中身の一覧は、ブックマークの一覧から [com.unchunks.echomark.domain.review.ReadingList] で作る。 */
interface ReadingListRepository {
    /** 「あとで読む」に入れる(すでに入っていれば、入れた日時は変えない)。 */
    suspend fun add(bookmarkId: Long)

    /** 「あとで読む」から外す(読んだことにはしない)。 */
    suspend fun remove(bookmarkId: Long)
}
