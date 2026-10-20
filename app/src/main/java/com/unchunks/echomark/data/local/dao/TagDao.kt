package com.unchunks.echomark.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.unchunks.echomark.data.local.entity.BookmarkTagCrossRef
import com.unchunks.echomark.data.local.entity.TagEntity
import com.unchunks.echomark.domain.model.TagNames
import com.unchunks.echomark.domain.model.TagSource
import kotlinx.coroutines.flow.Flow

/** タグと、そのタグが付いたブックマークの件数(タグ管理画面用)。 */
data class TagWithCountRow(
    val id: Long,
    val name: String,
    val bookmarkCount: Int,
    val isUserCreated: Boolean
)

@Dao
interface TagDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTag(tag: TagEntity): Long

    @Query("SELECT * FROM tags WHERE name = :name LIMIT 1")
    suspend fun getTagByName(name: String): TagEntity?

    @Query("SELECT * FROM tags ORDER BY name")
    fun getAllTags(): Flow<List<TagEntity>>

    /**
     * タグ名を優先度の順に返す(AI に既存のタグを伝える・表記ゆれをそろえるため)。
     * ユーザーのタグ → 付いているブックマークが多い順 → 名前順。[limit] が負なら全件。
     */
    @Query("""
        SELECT t.name FROM tags AS t
        LEFT JOIN bookmark_tag_cross_ref AS r ON r.tagId = t.id
        GROUP BY t.id
        ORDER BY t.isUserCreated DESC, COUNT(r.bookmarkId) DESC, t.name COLLATE NOCASE
        LIMIT :limit
    """)
    suspend fun getTagNamesByPriority(limit: Int): List<String>

    @Query("UPDATE tags SET isUserCreated = 1 WHERE id = :id")
    suspend fun markUserCreated(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCrossRef(crossRef: BookmarkTagCrossRef)

    @Query("DELETE FROM bookmark_tag_cross_ref WHERE bookmarkId = :bookmarkId AND tagId = :tagId")
    suspend fun deleteCrossRef(bookmarkId: Long, tagId: Long)

    @Query("UPDATE bookmark_tag_cross_ref SET source = 'USER' WHERE bookmarkId = :bookmarkId AND tagId = :tagId")
    suspend fun promoteCrossRefToUser(bookmarkId: Long, tagId: Long)

    @Query("DELETE FROM bookmark_tag_cross_ref WHERE bookmarkId = :bookmarkId AND source = 'AI'")
    suspend fun deleteAiCrossRefs(bookmarkId: Long)

    /**
     * AI だけが付けたタグのうち、どのブックマークにも付いていないものを削除する(削除した数を返す)。
     * ユーザーのタグ(isUserCreated)は件数 0 でも残す。
     */
    @Query("""
        DELETE FROM tags
        WHERE isUserCreated = 0
          AND NOT EXISTS (SELECT 1 FROM bookmark_tag_cross_ref AS r WHERE r.tagId = tags.id)
    """)
    suspend fun deleteOrphanAiTags(): Int

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE id = :bookmarkId)")
    suspend fun bookmarkExists(bookmarkId: Long): Boolean

    /** 同名のタグの ID を返す(無ければ作る)。 */
    suspend fun getOrCreateTagId(name: String): Long? =
        insertTag(TagEntity(name = name)).takeIf { it != -1L } ?: getTagByName(name)?.id

    /**
     * ブックマークにタグを付ける(無いタグは作る)。ブックマークが無ければ何もせず false を返す。
     * 存在確認とタグ・紐付けの書き込みを1つのトランザクションで行い、AI 処理中に削除されても
     * どこにも紐付かないタグを残さない(紐付けの外部キー違反で落ちることもない)。
     *
     * [source] が USER のときは、タグをユーザーのタグにし、AI が先に付けていた紐付けもユーザーのものにする。
     * AI のときは既存の紐付けを変えない(ユーザーが付けたものは USER のまま)。
     */
    @Transaction
    suspend fun addTagsToBookmark(bookmarkId: Long, tagNames: List<String>, source: TagSource): Boolean {
        if (!bookmarkExists(bookmarkId)) return false
        tagNames.forEach { name ->
            val tagId = getOrCreateTagId(name) ?: return@forEach
            insertCrossRef(BookmarkTagCrossRef(bookmarkId, tagId, source))
            if (source == TagSource.USER) {
                markUserCreated(tagId)
                promoteCrossRefToUser(bookmarkId, tagId)
            }
        }
        return true
    }

    /**
     * AI が付けたタグを [tagNames] で置き換える(前回の AI の結果は外し、ユーザーが付けたタグには触れない)。
     * タグ名は表記ゆれの範囲で既存のタグにそろえる([TagNames.resolve])。
     * 外したことでどこにも付かなくなった AI のタグは削除する。ブックマークが無ければ何もせず false を返す。
     */
    @Transaction
    suspend fun replaceAiTags(bookmarkId: Long, tagNames: List<String>): Boolean {
        if (!bookmarkExists(bookmarkId)) return false
        deleteAiCrossRefs(bookmarkId)
        // 外した直後のタグもまだ残っているため、前回と同じ表記を使い回せる
        val names = TagNames.resolve(tagNames, existing = getTagNamesByPriority(limit = -1))
        addTagsToBookmark(bookmarkId, names, TagSource.AI)
        deleteOrphanAiTags()
        return true
    }

    /** ブックマークからタグを外す。AI だけが付けていたタグで、どこにも付かなくなったら削除する。 */
    @Transaction
    suspend fun removeTagFromBookmark(bookmarkId: Long, tagName: String) {
        val tag = getTagByName(tagName) ?: return
        deleteCrossRef(bookmarkId, tag.id)
        deleteOrphanAiTags()
    }

    /** タグごとの件数つき一覧。件数 0 のタグも含める(名前の昇順、大文字小文字は区別しない)。 */
    @Query("""
        SELECT t.id AS id, t.name AS name, COUNT(r.bookmarkId) AS bookmarkCount, t.isUserCreated AS isUserCreated
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

    /** [fromTagId] の付いたブックマークに [toTagId] も付ける(付けた人も引き継ぐ。既に付いていれば何もしない)。 */
    @Query("""
        INSERT OR IGNORE INTO bookmark_tag_cross_ref (bookmarkId, tagId, source)
        SELECT bookmarkId, :toTagId, source FROM bookmark_tag_cross_ref WHERE tagId = :fromTagId
    """)
    suspend fun copyCrossRefs(fromTagId: Long, toTagId: Long)

    /** [fromTagId] をユーザーが付けていたブックマークでは、[toTagId] の紐付けもユーザーのものにする(統合用)。 */
    @Query("""
        UPDATE bookmark_tag_cross_ref SET source = 'USER'
        WHERE tagId = :toTagId AND bookmarkId IN (
            SELECT bookmarkId FROM bookmark_tag_cross_ref WHERE tagId = :fromTagId AND source = 'USER'
        )
    """)
    suspend fun promoteMergedCrossRefs(fromTagId: Long, toTagId: Long)

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
     * ユーザーが名前を決めたタグなので、残ったタグはユーザーのタグにする(件数 0 になっても自動では消さない)。
     */
    @Transaction
    suspend fun renameOrMerge(tagId: Long, newName: String): Long {
        val existing = getTagByName(newName)
        if (existing == null || existing.id == tagId) {
            updateTagName(tagId, newName)
            markUserCreated(tagId)
            return tagId
        }
        copyCrossRefs(fromTagId = tagId, toTagId = existing.id)
        promoteMergedCrossRefs(fromTagId = tagId, toTagId = existing.id)
        deleteTagWithRefs(tagId)
        markUserCreated(existing.id)
        return existing.id
    }
}
