package com.unchunks.echomark.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.unchunks.echomark.data.local.entity.BookmarkTagCrossRef
import com.unchunks.echomark.data.local.entity.TagEntity
import kotlinx.coroutines.flow.Flow

/** タグと、そのタグが付いたブックマークの件数(タグ管理画面用)。 */
data class TagWithCountRow(
    val id: Long,
    val name: String,
    val bookmarkCount: Int
)

@Dao
interface TagDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTag(tag: TagEntity): Long

    @Query("SELECT * FROM tags WHERE name = :name LIMIT 1")
    suspend fun getTagByName(name: String): TagEntity?

    @Query("SELECT * FROM tags ORDER BY name")
    fun getAllTags(): Flow<List<TagEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCrossRef(crossRef: BookmarkTagCrossRef)

    @Query("DELETE FROM bookmark_tag_cross_ref WHERE bookmarkId = :bookmarkId AND tagId = :tagId")
    suspend fun deleteCrossRef(bookmarkId: Long, tagId: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE id = :bookmarkId)")
    suspend fun bookmarkExists(bookmarkId: Long): Boolean

    /**
     * ブックマークにタグを付ける(無いタグは作る)。ブックマークが無ければ何もせず false を返す。
     * 存在確認とタグ・紐付けの書き込みを1つのトランザクションで行い、AI 処理中に削除されても
     * どこにも紐付かないタグを残さない(紐付けの外部キー違反で落ちることもない)。
     */
    @Transaction
    suspend fun addTagsToBookmark(bookmarkId: Long, tagNames: List<String>): Boolean {
        if (!bookmarkExists(bookmarkId)) return false
        tagNames.forEach { name ->
            val tagId = insertTag(TagEntity(name = name)).takeIf { it != -1L }
                ?: getTagByName(name)?.id
                ?: return@forEach
            insertCrossRef(BookmarkTagCrossRef(bookmarkId, tagId))
        }
        return true
    }

    /** タグごとの件数つき一覧。件数 0 のタグも含める(名前の昇順、大文字小文字は区別しない)。 */
    @Query("""
        SELECT t.id AS id, t.name AS name, COUNT(r.bookmarkId) AS bookmarkCount
        FROM tags AS t
        LEFT JOIN bookmark_tag_cross_ref AS r ON r.tagId = t.id
        GROUP BY t.id
        ORDER BY t.name COLLATE NOCASE
    """)
    fun observeTagsWithCount(): Flow<List<TagWithCountRow>>

    @Query("SELECT * FROM tags WHERE id = :id")
    suspend fun getTagById(id: Long): TagEntity?

    @Query("UPDATE tags SET name = :name WHERE id = :id")
    suspend fun updateTagName(id: Long, name: String)

    /** [fromTagId] の付いたブックマークに [toTagId] も付ける(既に付いていれば何もしない)。 */
    @Query("""
        INSERT OR IGNORE INTO bookmark_tag_cross_ref (bookmarkId, tagId)
        SELECT bookmarkId, :toTagId FROM bookmark_tag_cross_ref WHERE tagId = :fromTagId
    """)
    suspend fun copyCrossRefs(fromTagId: Long, toTagId: Long)

    @Query("DELETE FROM bookmark_tag_cross_ref WHERE tagId = :tagId")
    suspend fun deleteCrossRefsByTag(tagId: Long)

    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun deleteTagById(id: Long)

    /** タグとその紐付けを削除する(ブックマーク自体は残す)。 */
    @Transaction
    suspend fun deleteTagWithRefs(tagId: Long) {
        deleteCrossRefsByTag(tagId)
        deleteTagById(tagId)
    }

    /**
     * タグ名を変更する。同名の別タグが既にあれば、そのタグへ統合(紐付けを移して元のタグを削除)する。
     * 変更後に残ったタグの ID を返す(統合したときは統合先の ID)。
     */
    @Transaction
    suspend fun renameOrMerge(tagId: Long, newName: String): Long {
        val existing = getTagByName(newName)
        if (existing == null || existing.id == tagId) {
            updateTagName(tagId, newName)
            return tagId
        }
        copyCrossRefs(fromTagId = tagId, toTagId = existing.id)
        deleteTagWithRefs(tagId)
        return existing.id
    }
}
