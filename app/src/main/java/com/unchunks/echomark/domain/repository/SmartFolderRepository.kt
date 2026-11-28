package com.unchunks.echomark.domain.repository

import com.unchunks.echomark.domain.bookmark.model.BookmarkQuery
import com.unchunks.echomark.domain.bookmark.model.BookmarkSortOrder
import com.unchunks.echomark.domain.bookmark.model.SmartFolder
import com.unchunks.echomark.domain.bookmark.model.SmartFolderResult
import kotlinx.coroutines.flow.Flow

/** スマートフォルダ(#34)の保存。 */
interface SmartFolderRepository {

    /** 作った順(古いものが先)。 */
    fun observeFolders(): Flow<List<SmartFolder>>

    /** 条件 [query]([sortOrder] は開いたときの並び順。null なら今のまま)を [name] で保存する。 */
    suspend fun create(name: String, query: BookmarkQuery, sortOrder: BookmarkSortOrder?): SmartFolderResult

    suspend fun rename(id: Long, name: String): SmartFolderResult

    /** フォルダの条件を [query] と [sortOrder] で置き換える(名前は変えない)。 */
    suspend fun updateConditions(id: Long, query: BookmarkQuery, sortOrder: BookmarkSortOrder?)

    suspend fun delete(id: Long)
}
