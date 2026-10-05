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

/** タグ管理(一覧・名前変更・統合・削除)。ブックマークへのタグの付け外しは [BookmarkRepository] で行う。 */
interface TagRepository {
    /** 件数つきのタグ一覧(件数 0 のタグも含む。名前順)。ユーザーのタグか AI のタグかも返す。 */
    fun observeTagsWithCount(): Flow<List<TagWithCount>>

    /**
     * 名前を変更する。同名の別タグがあればそちらへ統合する。前後の空白は除く。
     * 変更後のタグはユーザーのタグになる(AI のタグでも、件数 0 になったときに自動で消さない)。
     */
    suspend fun renameTag(tagId: Long, newName: String): TagRenameResult

    /** タグを削除する。付いていたブックマークからは外れるが、ブックマーク自体は残る。 */
    suspend fun deleteTag(tagId: Long)
}
