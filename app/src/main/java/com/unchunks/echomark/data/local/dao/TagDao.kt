package com.unchunks.echomark.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.unchunks.echomark.data.local.entity.BookmarkTagCrossRef
import com.unchunks.echomark.data.local.entity.RemovedTagEntity
import com.unchunks.echomark.data.local.entity.TagAliasEntity
import com.unchunks.echomark.data.local.entity.TagEntity
import com.unchunks.echomark.domain.model.TagNames
import com.unchunks.echomark.domain.model.TagSource
import com.unchunks.echomark.domain.model.TagTree
import kotlinx.coroutines.flow.Flow

/** タグと、そのタグが付いたブックマークの件数(タグ管理画面用)。 */
data class TagWithCountRow(
    val id: Long,
    val name: String,
    val bookmarkCount: Int,
    val isUserCreated: Boolean,
    val parentId: Long?
)

/** 別名とその行き先のタグ名(AI のタグ名を既存のタグにそろえる用)。 */
data class AliasTargetRow(val aliasKey: String, val tagName: String)

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

    /**
     * 同名のタグの ID を返す(無ければ作る)。新しく作ったタグと同じ名前の別名は消す
     * (そのタグが別名の行き先になっていて、同じ名前のタグが 2 つあるように扱われないように)。
     */
    suspend fun getOrCreateTagId(name: String): Long? =
        insertTag(TagEntity(name = name)).takeIf { it != -1L }?.also { deleteAliasByKey(TagNames.key(name)) }
            ?: getTagByName(name)?.id

    // ---- 別名(統合・名前変更した元の名前) ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlias(alias: TagAliasEntity)

    @Query("DELETE FROM tag_aliases WHERE aliasKey = :aliasKey")
    suspend fun deleteAliasByKey(aliasKey: String)

    /** [fromTagId] の別名を [toTagId] の別名にする(統合で、元のタグの別名を引き継ぐ)。 */
    @Query("UPDATE tag_aliases SET tagId = :toTagId WHERE tagId = :fromTagId")
    suspend fun repointAliases(fromTagId: Long, toTagId: Long)

    @Query("SELECT a.aliasKey AS aliasKey, t.name AS tagName FROM tag_aliases AS a INNER JOIN tags AS t ON t.id = a.tagId")
    suspend fun getAliasTargets(): List<AliasTargetRow>

    // ---- ユーザーが外したタグの記録 ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRemoved(removed: RemovedTagEntity)

    @Query("SELECT tagKey FROM removed_tags WHERE bookmarkId = :bookmarkId")
    suspend fun getRemovedKeys(bookmarkId: Long): List<String>

    @Query("DELETE FROM removed_tags WHERE bookmarkId = :bookmarkId AND tagKey = :tagKey")
    suspend fun deleteRemoved(bookmarkId: Long, tagKey: String)

    /** 外したタグの記録のキーを [toKey] に付け替える(統合したタグを、統合先の名前で覚え直す)。既にある記録は残す。 */
    @Query("UPDATE OR IGNORE removed_tags SET tagKey = :toKey, tagName = :toName WHERE tagKey = :fromKey")
    suspend fun renameRemoved(fromKey: String, toKey: String, toName: String)

    @Query("DELETE FROM removed_tags WHERE tagKey = :tagKey")
    suspend fun deleteRemovedByKey(tagKey: String)

    @Query("SELECT bookmarkId FROM bookmark_tag_cross_ref WHERE tagId = :tagId")
    suspend fun getBookmarkIdsByTag(tagId: Long): List<Long>

    @Query("SELECT tagId FROM bookmark_tag_cross_ref WHERE bookmarkId = :bookmarkId")
    suspend fun getTagIdsOfBookmark(bookmarkId: Long): List<Long>

    /**
     * ブックマークにタグを付ける(無いタグは作る)。ブックマークが無ければ何もせず false を返す。
     * 存在確認とタグ・紐付けの書き込みを1つのトランザクションで行い、AI 処理中に削除されても
     * どこにも紐付かないタグを残さない(紐付けの外部キー違反で落ちることもない)。
     *
     * [source] が USER のときは、タグをユーザーのタグにし、AI が先に付けていた紐付けもユーザーのものにする。
     * AI のときは既存の紐付けを変えない(ユーザーが付けたものは USER のまま)。
     * ユーザーが付けたタグは、外したタグの記録から消す(付け直したので、AI がまた付けてよい)。
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
                deleteRemoved(bookmarkId, TagNames.key(name))
            }
        }
        return true
    }

    /**
     * AI が付けたタグを [tagNames] で置き換える(前回の AI の結果は外し、ユーザーが付けたタグには触れない)。
     * - タグ名は既存のタグにそろえる([TagNames.resolve]。表記ゆれ・別名)
     * - このブックマークからユーザーが外したタグは付けない(外したタグの記録)
     * - 親子のどちらも付くときは、親は付けない(親で絞り込むと子のタグのブックマークも出るため)。
     *   ユーザーが付けた子のタグがあるときの親も同じ
     * 外したことでどこにも付かなくなった AI のタグは削除する。ブックマークが無ければ何もせず false を返す。
     */
    @Transaction
    suspend fun replaceAiTags(bookmarkId: Long, tagNames: List<String>): Boolean {
        if (!bookmarkExists(bookmarkId)) return false
        deleteAiCrossRefs(bookmarkId)
        // 外した直後のタグもまだ残っているため、前回と同じ表記を使い回せる
        val aliases = getAliasTargets().associate { it.aliasKey to it.tagName }
        val resolved = TagNames.resolve(tagNames, existing = getTagNamesByPriority(limit = -1), aliases = aliases)
        val removedKeys = getRemovedKeys(bookmarkId).toSet()
        val allowed = resolved.filter { TagNames.key(it) !in removedKeys }

        val tags = getAllTagsOnce()
        val idByName = tags.associate { it.name to it.id }
        val present = allowed.mapNotNull { idByName[it] }.toSet() + getTagIdsOfBookmark(bookmarkId)
        val redundant = TagTree.redundantAncestors(tags.associate { it.id to it.parentId }, present)
        val names = allowed.filter { idByName[it] !in redundant }

        addTagsToBookmark(bookmarkId, names, TagSource.AI)
        deleteOrphanAiTags()
        return true
    }

    /**
     * ブックマークからタグを外す。AI だけが付けていたタグで、どこにも付かなくなったら削除する。
     * 外したことを覚え([removedAt] の時刻)、AI の再処理でそのタグをまた付けないようにする。
     */
    @Transaction
    suspend fun removeTagFromBookmark(bookmarkId: Long, tagName: String, removedAt: Long) {
        val tag = getTagByName(tagName) ?: return
        deleteCrossRef(bookmarkId, tag.id)
        insertRemoved(RemovedTagEntity(bookmarkId, TagNames.key(tag.name), tag.name, removedAt))
        deleteOrphanAiTags()
    }

    /**
     * タグを削除し(紐付けと別名も消える)、付いていたブックマークから外したことを覚える([removedAt] の時刻)。
     * タグ管理でユーザーが消したタグを、AI の再処理でまた付けないため。統合で消えるタグには使わない。
     */
    @Transaction
    suspend fun deleteTagRemembering(tagId: Long, removedAt: Long) {
        val tag = getTagById(tagId) ?: return
        getBookmarkIdsByTag(tagId).forEach { bookmarkId ->
            insertRemoved(RemovedTagEntity(bookmarkId, TagNames.key(tag.name), tag.name, removedAt))
        }
        deleteTagWithRefs(tagId)
    }

    /** タグごとの件数つき一覧。件数 0 のタグも含める(名前の昇順、大文字小文字は区別しない)。 */
    @Query("""
        SELECT t.id AS id, t.name AS name, COUNT(r.bookmarkId) AS bookmarkCount, t.isUserCreated AS isUserCreated,
            t.parentId AS parentId
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

    @Query("UPDATE tags SET parentId = :parentId WHERE parentId = :fromParentId")
    suspend fun moveChildren(fromParentId: Long, parentId: Long?)

    /** タグとその紐付けを削除する(ブックマーク自体は残す)。子のタグは、削除するタグの親(無ければ最上位)につく。 */
    @Transaction
    suspend fun deleteTagWithRefs(tagId: Long) {
        moveChildren(fromParentId = tagId, parentId = getTagById(tagId)?.parentId)
        deleteCrossRefsByTag(tagId)
        deleteTagById(tagId)
    }

    /**
     * [fromTagId] を [intoTagId] に統合して削除する。付いていたブックマークと子のタグは [intoTagId] へ移り、
     * 統合先に親が無ければ、元のタグの親を引き継ぐ(輪になるときは引き継がない)。
     * 統合先が元のタグの子孫のときは、先に統合先を元のタグの親の下へ上げてから子を移す(統合先と子が輪にならないように)。
     */
    @Transaction
    suspend fun absorbTag(fromTagId: Long, intoTagId: Long) {
        copyCrossRefs(fromTagId = fromTagId, toTagId = intoTagId)
        promoteMergedCrossRefs(fromTagId = fromTagId, toTagId = intoTagId)
        val from = getTagById(fromTagId)
        val into = getTagById(intoTagId)
        if (from != null && into != null) {
            val intoIsDescendant = intoTagId in getSubtreeIds(fromTagId)
            val inherited = from.parentId?.takeIf { it != intoTagId && intoTagId !in getAncestorIds(it) }
            if (intoIsDescendant) {
                updateParent(intoTagId, from.parentId)
            } else if (into.parentId == null && inherited != null) {
                updateParent(intoTagId, inherited)
            }
            moveChildren(fromParentId = fromTagId, parentId = intoTagId)
            // 元の名前は別名として覚える(AI がまた出しても統合先に付ける)。外したことの記録も統合先の名前に付け替える
            repointAliases(fromTagId, intoTagId)
            val fromKey = TagNames.key(from.name)
            val intoKey = TagNames.key(into.name)
            if (fromKey != intoKey) {
                insertAlias(TagAliasEntity(aliasKey = fromKey, alias = from.name, tagId = intoTagId))
                renameRemoved(fromKey, intoKey, into.name)
                deleteRemovedByKey(fromKey)
            }
        }
        deleteTagWithRefs(fromTagId)
    }

    @Query("UPDATE tags SET parentId = :parentId WHERE id = :id")
    suspend fun updateParent(id: Long, parentId: Long?)

    /** [tagId] とその子孫のタグの ID(輪になっていても止まる)。 */
    @Query("""
        WITH RECURSIVE subtree(id) AS (
            SELECT :tagId
            UNION
            SELECT t.id FROM tags AS t INNER JOIN subtree AS s ON t.parentId = s.id
        )
        SELECT id FROM subtree
    """)
    suspend fun getSubtreeIds(tagId: Long): List<Long>

    /** [tagId] とその祖先のタグの ID(輪になっていても止まる)。 */
    @Query("""
        WITH RECURSIVE ancestors(id) AS (
            SELECT :tagId
            UNION
            SELECT t.parentId FROM tags AS t INNER JOIN ancestors AS a ON t.id = a.id WHERE t.parentId IS NOT NULL
        )
        SELECT id FROM ancestors
    """)
    suspend fun getAncestorIds(tagId: Long): List<Long>

    /**
     * [tagId] の親を [parentId] にする(null なら最上位)。自分や自分の子孫を親にすると輪になるので何もせず false を返す。
     * タグが無い・親のタグが無いときも false。ユーザーが整理したタグなので、子も親もユーザーのタグにする
     * (AI のタグのままだと、付いているブックマークが無くなったときに消えて階層が崩れる)。
     */
    @Transaction
    suspend fun setParent(tagId: Long, parentId: Long?): Boolean {
        if (getTagById(tagId) == null) return false
        if (parentId != null) {
            if (getTagById(parentId) == null || parentId in getSubtreeIds(tagId)) return false
            markUserCreated(parentId)
        }
        updateParent(tagId, parentId)
        markUserCreated(tagId)
        return true
    }

    @Query("SELECT * FROM tags")
    suspend fun getAllTagsOnce(): List<TagEntity>

    /**
     * タグ名を変更する。同名(完全一致を優先し、無ければ大文字小文字・全角半角の違いを除いて同じ)の別タグが既にあれば、
     * そのタグへ統合(紐付けを移して元のタグを削除)する。
     * 変更後に残ったタグの ID を返す(統合したときは統合先の ID)。
     * ユーザーが名前を決めたタグなので、残ったタグはユーザーのタグにする(件数 0 になっても自動では消さない)。
     */
    @Transaction
    suspend fun renameOrMerge(tagId: Long, newName: String): Long {
        val others = getAllTagsOnce().filter { it.id != tagId }
        val sameName = TagNames.findSame(newName, others.map { it.name })
        val existing = others.firstOrNull { it.name == sameName }
        if (existing == null) {
            val oldName = getTagById(tagId)?.name
            updateTagName(tagId, newName)
            markUserCreated(tagId)
            // 新しい名前と同じ別名は消し、元の名前は別名として覚える(大文字小文字だけの変更は別名にしない)
            deleteAliasByKey(TagNames.key(newName))
            if (oldName != null && TagNames.key(oldName) != TagNames.key(newName)) {
                insertAlias(TagAliasEntity(aliasKey = TagNames.key(oldName), alias = oldName, tagId = tagId))
            }
            return tagId
        }
        absorbTag(fromTagId = tagId, intoTagId = existing.id)
        markUserCreated(existing.id)
        return existing.id
    }

    /**
     * [fromTagIds] のタグを [intoTagId] に統合する(紐付けを移して元のタグを削除。表記ゆれの整理用)。
     * 付けた人は引き継ぐ。名前を付け直すわけではないので、統合先がユーザーのタグになるのは、
     * どれかがユーザーのタグだったときだけ。統合したタグの数を返す(統合先が無ければ 0)。
     */
    @Transaction
    suspend fun mergeTags(intoTagId: Long, fromTagIds: List<Long>): Int {
        val into = getTagById(intoTagId) ?: return 0
        var merged = 0
        var anyUser = into.isUserCreated
        fromTagIds.distinct().filter { it != intoTagId }.forEach { fromId ->
            val from = getTagById(fromId) ?: return@forEach
            absorbTag(fromTagId = fromId, intoTagId = intoTagId)
            anyUser = anyUser || from.isUserCreated
            merged++
        }
        if (anyUser) markUserCreated(intoTagId)
        return merged
    }
}
