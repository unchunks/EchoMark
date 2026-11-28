package com.unchunks.echomark.domain.repository

import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.linkcheck.LinkCheckState
import kotlinx.coroutines.flow.Flow

/** リンク切れと判断したブックマーク([deadAt] から。[failureCount] 回続けて開けなかった。[reason] は最後の失敗の理由)。 */
data class DeadLink(
    val bookmark: Bookmark,
    val deadAt: Long,
    val failureCount: Int,
    val reason: String?
)

/**
 * リンク切れの確認(#94)の結果の参照と操作。確認そのものは夜間の仕事(`LinkCheckNightlyTask`)が行う。
 * 切れたと判断するのは、続けて失敗したときだけ([com.unchunks.echomark.domain.linkcheck.LinkCheckPolicy])。
 */
interface LinkCheckRepository {

    /** リンク切れと判断したブックマーク(新しく切れたものが先)。「確認しない」を選んだものは含めない */
    fun observeDeadLinks(): Flow<List<DeadLink>>

    /** ブックマークの確認の状態。まだ確認していなければ null */
    fun observeState(bookmarkId: Long): Flow<LinkCheckState?>

    /**
     * 今すぐ確認する(「もう一度確認」)。結果の状態を返す。通信できない・リンクでない・ブックマークが無いときは記録せず null。
     * 開けたら、リンク切れの記録は消える。開けなくても、1回で切れたことにはしない(続けて失敗したときだけ)。
     */
    suspend fun checkNow(bookmarkId: Long): LinkCheckState?

    /** 「確認しない」にする / 戻す。確認しない間は、夜の確認の対象から外し、リンク切れとしても出さない */
    suspend fun setMuted(bookmarkId: Long, muted: Boolean)
}
