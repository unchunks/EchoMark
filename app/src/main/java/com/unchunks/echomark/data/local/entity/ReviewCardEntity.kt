package com.unchunks.echomark.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * 復習(間隔をあけて思い出す。#90)に入れたブックマークの、次に復習する日時などの状態。
 * 1 件のブックマークに 1 つだけ。復習に入れたブックマークだけに行がある。ブックマークを削除すると連鎖して消える。
 * 利用者のデータなのでバックアップに含める。
 *
 * @param dueAt 次に復習する日時(epoch ミリ秒。この日時以降に「復習する」に出る)
 * @param intervalDays 今の間隔(日)。0 は「もう一度」を選んだ直後(同じ日の中でもう一度出す)
 * @param easePermille 間隔の伸びやすさ(1000 で 1.0 倍)。「かんたん」で上がり「むずかしい」「もう一度」で下がる
 * @param repetitions 続けて思い出せた回数(「もう一度」で 0 に戻る)
 * @param lapses 「もう一度」を選んだ(忘れた)回数
 * @param lastReviewedAt 最後に復習した日時。まだ一度も復習していなければ null
 */
@Entity(
    tableName = "review_cards",
    foreignKeys = [
        ForeignKey(
            entity = BookmarkEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookmarkId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ReviewCardEntity(
    @PrimaryKey
    val bookmarkId: Long,
    val dueAt: Long,
    val intervalDays: Int,
    val easePermille: Int,
    val repetitions: Int,
    val lapses: Int,
    val lastReviewedAt: Long?,
    val createdAt: Long
)
