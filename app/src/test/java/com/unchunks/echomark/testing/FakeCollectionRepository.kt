package com.unchunks.echomark.testing

import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.bookmark.model.BookmarkCollection
import com.unchunks.echomark.domain.bookmark.model.CollectionMember
import com.unchunks.echomark.domain.bookmark.model.CollectionNames
import com.unchunks.echomark.domain.bookmark.model.CollectionResult
import com.unchunks.echomark.domain.bookmark.model.DeletedCollection
import com.unchunks.echomark.domain.export.ExportFormat
import com.unchunks.echomark.domain.export.ExportResult
import com.unchunks.echomark.domain.repository.CollectionRepository
import com.unchunks.echomark.domain.repository.ExportRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** コレクションの Fake。メモリ上に持つ。ブックマークは [bookmarks] に入れたものだけが入れられる。 */
class FakeCollectionRepository : CollectionRepository {
    private data class Stored(val id: Long, val name: String, val description: String?, val createdAt: Long)

    private val stored = MutableStateFlow<List<Stored>>(emptyList())

    /** コレクションの ID → 入れているブックマーク(入れた順) */
    private val members = MutableStateFlow<Map<Long, List<CollectionMember>>>(emptyMap())
    val bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    private var nextId = 1L

    private fun collections(): Flow<List<BookmarkCollection>> =
        kotlinx.coroutines.flow.combine(stored, members) { list, memberMap ->
            list.map { BookmarkCollection(it.id, it.name, it.description, memberMap[it.id].orEmpty().size, it.createdAt) }
        }

    override fun observeCollections(): Flow<List<BookmarkCollection>> = collections()

    override fun observeCollection(id: Long): Flow<BookmarkCollection?> = collections().map { list -> list.firstOrNull { it.id == id } }

    override fun observeBookmarks(collectionId: Long): Flow<List<Bookmark>> =
        kotlinx.coroutines.flow.combine(members, bookmarks) { memberMap, all ->
            memberMap[collectionId].orEmpty().mapNotNull { member -> all.firstOrNull { it.id == member.bookmarkId } }
        }

    override fun observeCollectionIdsOf(bookmarkId: Long): Flow<Set<Long>> =
        members.map { memberMap -> memberMap.filterValues { list -> list.any { it.bookmarkId == bookmarkId } }.keys }

    override suspend fun create(name: String, description: String?): CollectionResult {
        val normalized = CollectionNames.normalize(name) ?: return CollectionResult.InvalidName
        if (stored.value.any { CollectionNames.key(it.name) == CollectionNames.key(normalized) }) return CollectionResult.NameTaken
        val id = nextId++
        stored.value += Stored(id, normalized, CollectionNames.cleanDescription(description), id)
        return CollectionResult.Saved(id)
    }

    override suspend fun update(id: Long, name: String, description: String?): CollectionResult {
        val normalized = CollectionNames.normalize(name) ?: return CollectionResult.InvalidName
        if (stored.value.none { it.id == id }) return CollectionResult.NotFound
        if (stored.value.any { it.id != id && CollectionNames.key(it.name) == CollectionNames.key(normalized) }) return CollectionResult.NameTaken
        stored.value = stored.value.map {
            if (it.id == id) it.copy(name = normalized, description = CollectionNames.cleanDescription(description)) else it
        }
        return CollectionResult.Saved(id)
    }

    override suspend fun delete(id: Long): DeletedCollection? {
        val collection = stored.value.firstOrNull { it.id == id } ?: return null
        val deleted = DeletedCollection(collection.id, collection.name, collection.description, collection.createdAt, members.value[id].orEmpty())
        stored.value = stored.value.filterNot { it.id == id }
        members.value = members.value - id
        return deleted
    }

    override suspend fun restore(deleted: DeletedCollection): Boolean {
        if (stored.value.any { it.id == deleted.id || CollectionNames.key(it.name) == CollectionNames.key(deleted.name) }) return false
        stored.value += Stored(deleted.id, deleted.name, deleted.description, deleted.createdAt)
        members.value = members.value + (deleted.id to deleted.members)
        return true
    }

    override suspend fun add(collectionId: Long, bookmarkId: Long): Boolean {
        if (stored.value.none { it.id == collectionId }) return false
        val current = members.value[collectionId].orEmpty()
        if (current.none { it.bookmarkId == bookmarkId }) {
            members.value = members.value + (collectionId to (current + CollectionMember(bookmarkId, current.size.toLong())))
        }
        return true
    }

    override suspend fun remove(collectionId: Long, bookmarkId: Long) {
        members.value = members.value + (collectionId to members.value[collectionId].orEmpty().filterNot { it.bookmarkId == bookmarkId })
    }
}

/** 書き出しの Fake。呼び出しを記録し、[failure] があれば投げる。 */
class FakeExportRepository : ExportRepository {
    var result = ExportResult(exported = 1, skipped = 0)
    var failure: Throwable? = null
    val collectionExports = mutableListOf<Triple<Long, ExportFormat, String>>()
    val bookmarkExports = mutableListOf<Triple<Long, ExportFormat, String>>()

    override suspend fun exportCollection(collectionId: Long, format: ExportFormat, uri: String): ExportResult {
        collectionExports += Triple(collectionId, format, uri)
        failure?.let { throw it }
        return result
    }

    override suspend fun exportBookmark(bookmarkId: Long, format: ExportFormat, uri: String): ExportResult {
        bookmarkExports += Triple(bookmarkId, format, uri)
        failure?.let { throw it }
        return result
    }
}
