package com.unchunks.echomark.domain.repository

import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkCollection
import com.unchunks.echomark.domain.bookmark.model.CollectionResult
import com.unchunks.echomark.domain.bookmark.model.DeletedCollection
import kotlinx.coroutines.flow.Flow

/** コレクション(#91)の保存と、入れるブックマークの管理。 */
interface CollectionRepository {

    /** 作った順(古いものが先)。入れた件数つき */
    fun observeCollections(): Flow<List<BookmarkCollection>>

    /** 1つのコレクション。削除されたら null */
    fun observeCollection(id: Long): Flow<BookmarkCollection?>

    /** コレクションのブックマーク(入れた順) */
    fun observeBookmarks(collectionId: Long): Flow<List<Bookmark>>

    /** ブックマークが入っているコレクションの ID(追加のシートでチェックを付ける用) */
    fun observeCollectionIdsOf(bookmarkId: Long): Flow<Set<Long>>

    /** [name] で作る。空・長すぎれば [CollectionResult.InvalidName]、同じ名前が既にあれば [CollectionResult.NameTaken] */
    suspend fun create(name: String, description: String? = null): CollectionResult

    /** 名前と説明を直す。説明が空なら説明なしにする */
    suspend fun update(id: Long, name: String, description: String?): CollectionResult

    /** コレクションを削除する(入れていたブックマーク自体は消えない)。戻すための中身を返す。無ければ null */
    suspend fun delete(id: Long): DeletedCollection?

    /**
     * 削除したコレクションを同じ ID・入れていたブックマークで戻す(入れていたブックマークが消えていたら、その分は除く)。
     * 削除のあとに同じ名前のコレクションが作られていたなど、戻せなければ false
     */
    suspend fun restore(deleted: DeletedCollection): Boolean

    /** ブックマークをコレクションに入れる。コレクションかブックマークが無ければ false。すでに入っていても true */
    suspend fun add(collectionId: Long, bookmarkId: Long): Boolean

    /** コレクションから外す(ブックマーク自体は消えない) */
    suspend fun remove(collectionId: Long, bookmarkId: Long)
}
