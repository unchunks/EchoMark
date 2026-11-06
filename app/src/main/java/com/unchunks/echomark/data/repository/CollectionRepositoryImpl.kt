package com.unchunks.echomark.data.repository

import com.unchunks.components.core.DispatcherProvider
import com.unchunks.echomark.data.local.dao.BookmarkDao
import com.unchunks.echomark.data.local.dao.CollectionCountRow
import com.unchunks.echomark.data.local.dao.CollectionDao
import com.unchunks.echomark.data.local.entity.CollectionEntity
import com.unchunks.echomark.data.local.entity.CollectionItemEntity
import com.unchunks.echomark.data.mapper.toDomain
import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkCollection
import com.unchunks.echomark.domain.bookmark.model.CollectionMember
import com.unchunks.echomark.domain.bookmark.model.CollectionNames
import com.unchunks.echomark.domain.bookmark.model.CollectionResult
import com.unchunks.echomark.domain.bookmark.model.DeletedCollection
import com.unchunks.echomark.domain.repository.CollectionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

class CollectionRepositoryImpl @Inject constructor(
    private val collectionDao: CollectionDao,
    private val bookmarkDao: BookmarkDao,
    private val dispatcherProvider: DispatcherProvider
) : CollectionRepository {

    override fun observeCollections(): Flow<List<BookmarkCollection>> =
        collectionDao.observeAllWithCount()
            .map { rows -> rows.map { it.toDomain() } }
            .flowOn(dispatcherProvider.io)

    override fun observeCollection(id: Long): Flow<BookmarkCollection?> =
        // 件数つきの一覧から取り出す(コレクションの数は多くない)。削除されたら null
        collectionDao.observeAllWithCount()
            .map { rows -> rows.firstOrNull { it.id == id }?.toDomain() }
            .flowOn(dispatcherProvider.io)

    override fun observeBookmarks(collectionId: Long): Flow<List<Bookmark>> =
        collectionDao.observeBookmarks(collectionId)
            .map { list -> list.map { it.toDomain() } }
            .flowOn(dispatcherProvider.io)

    override fun observeCollectionIdsOf(bookmarkId: Long): Flow<Set<Long>> =
        collectionDao.observeCollectionIdsOf(bookmarkId)
            .map { it.toSet() }
            .flowOn(dispatcherProvider.io)

    override suspend fun create(name: String, description: String?): CollectionResult =
        withContext(dispatcherProvider.io) {
            val normalized = CollectionNames.normalize(name) ?: return@withContext CollectionResult.InvalidName
            if (isNameTaken(normalized, exceptId = null)) return@withContext CollectionResult.NameTaken
            val id = collectionDao.insert(
                CollectionEntity(
                    name = normalized,
                    description = CollectionNames.cleanDescription(description),
                    createdAt = System.currentTimeMillis()
                )
            )
            // 同じ名前が同時に作られたときは、一意のインデックスが IGNORE して -1 を返す
            if (id < 0) CollectionResult.NameTaken else CollectionResult.Saved(id)
        }

    override suspend fun update(id: Long, name: String, description: String?): CollectionResult =
        withContext(dispatcherProvider.io) {
            val normalized = CollectionNames.normalize(name) ?: return@withContext CollectionResult.InvalidName
            if (collectionDao.getById(id) == null) return@withContext CollectionResult.NotFound
            if (isNameTaken(normalized, exceptId = id)) return@withContext CollectionResult.NameTaken
            collectionDao.update(id, normalized, CollectionNames.cleanDescription(description))
            CollectionResult.Saved(id)
        }

    override suspend fun delete(id: Long): DeletedCollection? = withContext(dispatcherProvider.io) {
        val collection = collectionDao.getById(id) ?: return@withContext null
        val members = collectionDao.getItems(id).map { CollectionMember(it.bookmarkId, it.addedAt) }
        // 入れた記録は連鎖して消える。ブックマーク自体は消えない
        collectionDao.delete(id)
        DeletedCollection(collection.id, collection.name, collection.description, collection.createdAt, members)
    }

    override suspend fun restore(deleted: DeletedCollection): Boolean = withContext(dispatcherProvider.io) {
        if (collectionDao.getById(deleted.id) != null || isNameTaken(deleted.name, exceptId = null)) {
            return@withContext false
        }
        val inserted = collectionDao.insert(
            CollectionEntity(deleted.id, deleted.name, deleted.description, deleted.createdAt)
        )
        if (inserted < 0) return@withContext false
        // 削除のあとに消えたブックマークは、入れ直せない(外部キーで失敗する)ので除く
        val existing = bookmarkDao.getByIds(deleted.members.map { it.bookmarkId }).mapTo(HashSet()) { it.id }
        collectionDao.insertItems(
            deleted.members.filter { it.bookmarkId in existing }.map { CollectionItemEntity(deleted.id, it.bookmarkId, it.addedAt) }
        )
        true
    }

    override suspend fun add(collectionId: Long, bookmarkId: Long): Boolean = withContext(dispatcherProvider.io) {
        if (collectionDao.getById(collectionId) == null || !bookmarkDao.exists(bookmarkId)) return@withContext false
        collectionDao.insertItem(CollectionItemEntity(collectionId, bookmarkId, System.currentTimeMillis()))
        true
    }

    override suspend fun remove(collectionId: Long, bookmarkId: Long) = withContext(dispatcherProvider.io) {
        collectionDao.deleteItem(collectionId, bookmarkId)
    }

    /** 大文字小文字・全角半角を区別せず、[name] と同じ名前のコレクションが([exceptId] 以外に)あるか */
    private suspend fun isNameTaken(name: String, exceptId: Long?): Boolean {
        val key = CollectionNames.key(name)
        return collectionDao.getAll().any { it.id != exceptId && CollectionNames.key(it.name) == key }
    }
}

private fun CollectionCountRow.toDomain() = BookmarkCollection(
    id = id, name = name, description = description, bookmarkCount = bookmarkCount, createdAt = createdAt
)
