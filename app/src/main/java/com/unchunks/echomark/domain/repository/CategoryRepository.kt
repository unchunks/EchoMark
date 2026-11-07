package com.unchunks.echomark.domain.repository

import com.unchunks.echomark.domain.model.CategoryEntry
import kotlinx.coroutines.flow.Flow

/** カテゴリを作る・変える結果。 */
sealed interface CategoryResult {
    /** 保存した([name] は整えた名前) */
    data class Saved(val name: String) : CategoryResult

    /** 同じ名前のカテゴリ([existing])が既にある(標準のカテゴリを含む。大文字小文字・全角半角は区別しない) */
    data class Duplicate(val existing: String) : CategoryResult

    /** 名前が空・長すぎる、説明が長すぎる、カテゴリが無いなどで保存できなかった。[message] はそのまま画面に出せる文言 */
    data class Invalid(val message: String) : CategoryResult
}

/** カテゴリの管理(ユーザーが作るカテゴリと、一覧)。ブックマークのカテゴリを決めるのは [BookmarkRepository]。 */
interface CategoryRepository {
    /**
     * カテゴリの一覧(件数つき)。ユーザーが作ったもの(作った順)→ 標準のもの → AI が自由に付けた分類(件数の多い順)。
     * 標準のカテゴリは件数 0 でも含める。
     */
    fun observeCategories(): Flow<List<CategoryEntry>>

    /** カテゴリを作る。名前は整える。説明は AI が分類に使う。 */
    suspend fun addCategory(name: String, description: String): CategoryResult

    /** 名前と説明を変える。名前を変えたら、そのカテゴリのブックマークも新しい名前になる。 */
    suspend fun updateCategory(id: Long, name: String, description: String): CategoryResult

    /** カテゴリを削除する。そのカテゴリのブックマークは未分類に戻り、AI が次の処理で決め直す(ブックマーク自体は残る)。 */
    suspend fun deleteCategory(id: Long)
}
