package com.unchunks.echomark.domain.repository

import com.unchunks.echomark.domain.model.TagWithCount
import kotlinx.coroutines.flow.Flow

/** タグ名の変更結果。 */
sealed interface TagRenameResult {
    /** 名前を変更した */
    data class Renamed(val tagId: Long) : TagRenameResult

    /** 同名の既存タグ [intoTagId] に統合した(元のタグは削除済み) */
    data class Merged(val intoTagId: Long) : TagRenameResult

    /** 名前が変わらないので何もしていない */
    data object Unchanged : TagRenameResult

    /** 空の名前や存在しないタグなど、変更できなかった */
    data object Invalid : TagRenameResult
}

/** 親タグの設定結果。 */
sealed interface TagParentResult {
    /** 親を設定した(親を外して最上位にしたときも) */
    data object Updated : TagParentResult

    /** 自分や自分の子孫を親にしようとした(階層が輪になる) */
    data object Cycle : TagParentResult

    /** タグや親のタグが無い */
    data object Invalid : TagParentResult
}

/** タグ管理(一覧・名前変更・統合・表記ゆれの整理・階層・削除)。ブックマークへのタグの付け外しは [BookmarkRepository] で行う。 */
interface TagRepository {
    /** 件数つきのタグ一覧(件数 0 のタグも含む。名前順)。ユーザーのタグか AI のタグかも返す。 */
    fun observeTagsWithCount(): Flow<List<TagWithCount>>

    /**
     * 名前を変更する。同名の別タグ(大文字小文字・全角半角の違いは無視)があればそちらへ統合する。
     * 名前は [com.unchunks.echomark.domain.model.TagNames.clean] で整える(前後の空白や全角英数など)。
     * 変更後のタグはユーザーのタグになる(AI のタグでも、件数 0 になったときに自動で消さない)。
     */
    suspend fun renameTag(tagId: Long, newName: String): TagRenameResult

    /**
     * [tagId] の親を [parentId] にする(null なら最上位に戻す)。階層が輪になるときは何もしない。
     * 階層に組み込んだタグは、AI のタグでもユーザーのタグになる(件数 0 になっても自動では消さない)。
     */
    suspend fun setParent(tagId: Long, parentId: Long?): TagParentResult

    /**
     * [fromTagIds] のタグを [intoTagId] に統合する(付いていたブックマークは統合先に付け替え、元のタグは削除する)。
     * 表記ゆれの整理に使う。統合したタグの数を返す(統合先が無ければ 0)。
     */
    suspend fun mergeTags(intoTagId: Long, fromTagIds: List<Long>): Int

    /** タグを削除する。付いていたブックマークからは外れるが、ブックマーク自体は残る。 */
    suspend fun deleteTag(tagId: Long)
}
