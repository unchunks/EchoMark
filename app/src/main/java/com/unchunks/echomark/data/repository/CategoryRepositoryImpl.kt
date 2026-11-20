package com.unchunks.echomark.data.repository

import com.unchunks.components.core.DispatcherProvider
import com.unchunks.echomark.data.local.dao.CategoryDao
import com.unchunks.echomark.data.local.entity.CategoryEntity
import com.unchunks.echomark.domain.model.CategoryEntry
import com.unchunks.echomark.domain.model.CategoryKind
import com.unchunks.echomark.domain.model.CategoryNames
import com.unchunks.echomark.domain.repository.CategoryRepository
import com.unchunks.echomark.domain.repository.CategoryResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import javax.inject.Inject

class CategoryRepositoryImpl @Inject constructor(
    private val categoryDao: CategoryDao,
    private val dispatcherProvider: DispatcherProvider
) : CategoryRepository {

    override fun observeCategories(): Flow<List<CategoryEntry>> =
        combine(categoryDao.observeAll(), categoryDao.observeCounts()) { user, counts ->
            // AI が付けた分類は、大文字小文字・全角半角の違いを1つにして数える
            val countByKey = HashMap<String, Int>()
            val spellings = HashMap<String, MutableList<Pair<String, Int>>>()
            counts.forEach { row ->
                val key = CategoryNames.key(row.name)
                countByKey[key] = (countByKey[key] ?: 0) + row.bookmarkCount
                spellings.getOrPut(key) { mutableListOf() } += row.name to row.bookmarkCount
            }
            val userEntries = user.map {
                CategoryEntry(it.name, it.description, CategoryKind.USER, countByKey[CategoryNames.key(it.name)] ?: 0, it.id)
            }
            val builtInEntries = CategoryNames.BUILT_IN.map {
                CategoryEntry(it.name, it.description, CategoryKind.BUILT_IN, countByKey[CategoryNames.key(it.name)] ?: 0)
            }
            val known = (userEntries + builtInEntries).mapTo(HashSet()) { CategoryNames.key(it.name) }
            val others = countByKey.filterKeys { it !in known }.map { (key, count) ->
                // いちばん多い表記を名前にする
                val name = spellings.getValue(key).maxByOrNull { it.second }!!.first
                CategoryEntry(name, CategoryNames.AI_OTHER_DESCRIPTION, CategoryKind.AI_OTHER, count)
            }.sortedWith(compareByDescending<CategoryEntry> { it.bookmarkCount }.thenBy { it.name })
            userEntries + builtInEntries + others
        }.flowOn(dispatcherProvider.io)

    override suspend fun addCategory(name: String, description: String): CategoryResult =
        withContext(dispatcherProvider.io) {
            val cleaned = CategoryNames.clean(name)
            validate(cleaned, description.trim(), selfId = null)?.let { return@withContext it }
            val id = categoryDao.insert(CategoryEntity(name = cleaned, description = description.trim(), createdAt = System.currentTimeMillis()))
            if (id == -1L) CategoryResult.Duplicate(cleaned) else CategoryResult.Saved(cleaned)
        }

    override suspend fun updateCategory(id: Long, name: String, description: String): CategoryResult =
        withContext(dispatcherProvider.io) {
            if (categoryDao.getById(id) == null) return@withContext CategoryResult.Invalid("カテゴリが見つかりませんでした")
            val cleaned = CategoryNames.clean(name)
            validate(cleaned, description.trim(), selfId = id)?.let { return@withContext it }
            categoryDao.update(id, cleaned, description.trim())
            CategoryResult.Saved(cleaned)
        }

    override suspend fun deleteCategory(id: Long) {
        withContext(dispatcherProvider.io) { categoryDao.delete(id) }
    }

    /** 名前・説明の検査。保存してよければ null、だめなら理由。 */
    private suspend fun validate(name: String, description: String, selfId: Long?): CategoryResult? {
        if (name.isEmpty()) return CategoryResult.Invalid("カテゴリ名を入力してください")
        if (name.length > CategoryNames.MAX_NAME_CHARS) {
            return CategoryResult.Invalid("カテゴリ名は${CategoryNames.MAX_NAME_CHARS}文字までです")
        }
        if (description.length > CategoryNames.MAX_DESCRIPTION_CHARS) {
            return CategoryResult.Invalid("説明は${CategoryNames.MAX_DESCRIPTION_CHARS}文字までです")
        }
        val key = CategoryNames.key(name)
        CategoryNames.BUILT_IN.firstOrNull { CategoryNames.key(it.name) == key }?.let { return CategoryResult.Duplicate(it.name) }
        categoryDao.getAll().firstOrNull { it.id != selfId && CategoryNames.key(it.name) == key }
            ?.let { return CategoryResult.Duplicate(it.name) }
        return null
    }
}
