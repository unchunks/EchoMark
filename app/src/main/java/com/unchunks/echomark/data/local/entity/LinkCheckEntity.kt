package com.unchunks.echomark.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * リンク切れの確認(#94)の、ブックマークごとの状態。確認した行だけが作られる(確認前のブックマークには行が無い)。
 * 1回の失敗では切れたことにしない。続けて失敗した回数([failureCount])と、最初に失敗した日時([firstFailedAt])が
 * 決めた基準を超えたときだけ [deadAt] を入れる(基準は `LinkCheckPolicy`)。成功したら失敗の記録は消える。
 * [muted] は利用者が「確認しない」を選んだもの(確認の対象から外し、リンク切れとしても出さない)。
 * ブックマークを削除すると連鎖して消える。確認の状態は端末の中のものなのでバックアップに含めない([muted] の印だけ含める)。
 */
@Entity(
    tableName = "link_checks",
    foreignKeys = [
        ForeignKey(
            entity = BookmarkEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookmarkId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class LinkCheckEntity(
    @PrimaryKey
    val bookmarkId: Long,

    /** 最後に確認した日時。「確認しない」だけを選んだ(まだ確認していない)なら 0 */
    val checkedAt: Long = 0L,

    /** 続けて失敗した回数。成功したら 0 に戻る */
    @ColumnInfo(defaultValue = "0")
    val failureCount: Int = 0,

    /** 今の失敗が続き始めた日時。失敗が続いていなければ null */
    val firstFailedAt: Long? = null,

    /** リンク切れと判断した日時。切れていなければ null */
    val deadAt: Long? = null,

    /** 最後の失敗の理由(「HTTP 404」「サイトが見つからない」など。画面にそのまま出す)。失敗が続いていなければ null */
    val lastReason: String? = null,

    /** 利用者が「確認しない」を選んだ */
    @ColumnInfo(defaultValue = "0")
    val muted: Boolean = false
)
