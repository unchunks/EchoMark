package com.unchunks.echomark.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.unchunks.echomark.data.local.entity.CategoryEntity
import kotlinx.coroutines.flow.Flow

/** カテゴリ名ごとの、そのカテゴリのブックマークの件数。 */
data class CategoryCountRow(val name: String, val bookmarkCount: Int)

@Dao
interface CategoryDao {

    @Query("SELECT * FROM categories ORDER BY createdAt, id")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY createdAt, id")
    suspend fun getAll(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: Long): CategoryEntity?

    /** ブックマークに付いているカテゴリ(AI が付けたものも含む)ごとの件数。空のカテゴリは数えない。 */
    @Query("""
        SELECT category AS name, COUNT(*) AS bookmarkCount FROM bookmarks
        WHERE category IS NOT NULL AND TRIM(category) != ''
        GROUP BY category
    """)
    fun observeCounts(): Flow<List<CategoryCountRow>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(category: CategoryEntity): Long

    @Query("UPDATE categories SET description = :description WHERE id = :id")
    suspend fun updateDescription(id: Long, description: String)

    @Query("UPDATE categories SET name = :name WHERE id = :id")
    suspend fun updateName(id: Long, name: String)

    @Query("UPDATE bookmarks SET category = :to WHERE category = :from")
    suspend fun renameBookmarkCategory(from: String, to: String)

    /** カテゴリ [name] のブックマークを未分類に戻す(AI が次の処理で決め直す。自分で選んだ印も外す)。 */
    @Query("UPDATE bookmarks SET category = NULL, categoryEdited = 0 WHERE category = :name")
    suspend fun clearBookmarkCategory(name: String)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 名前と説明を変える。名前を変えたら、そのカテゴリのブックマークも新しい名前にする。 */
    @Transaction
    suspend fun update(id: Long, name: String, description: String) {
        val current = getById(id) ?: return
        if (current.name != name) {
            updateName(id, name)
            renameBookmarkCategory(from = current.name, to = name)
        }
        if (current.description != description) updateDescription(id, description)
    }

    /** カテゴリを削除し、そのカテゴリのブックマークは未分類に戻す(ブックマーク自体は残る)。 */
    @Transaction
    suspend fun delete(id: Long) {
        val current = getById(id) ?: return
        clearBookmarkCategory(current.name)
        deleteById(id)
    }
}
