package com.unchunks.echomark.data.repository

import com.unchunks.components.core.DispatcherProvider
import com.unchunks.echomark.data.local.dao.SmartFolderDao
import com.unchunks.echomark.data.mapper.SmartFolderCodec
import com.unchunks.echomark.data.mapper.smartFolderEntity
import com.unchunks.echomark.data.mapper.toDomain
import com.unchunks.echomark.domain.bookmark.model.BookmarkQuery
import com.unchunks.echomark.domain.bookmark.model.BookmarkSortOrder
import com.unchunks.echomark.domain.bookmark.model.SmartFolder
import com.unchunks.echomark.domain.bookmark.model.SmartFolderNames
import com.unchunks.echomark.domain.bookmark.model.SmartFolderResult
import com.unchunks.echomark.domain.repository.SmartFolderRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

class SmartFolderRepositoryImpl @Inject constructor(
    private val smartFolderDao: SmartFolderDao,
    private val dispatcherProvider: DispatcherProvider
) : SmartFolderRepository {

    override fun observeFolders(): Flow<List<SmartFolder>> =
        smartFolderDao.observeAll()
            .map { list -> list.map { it.toDomain() } }
            .flowOn(dispatcherProvider.io)

    override suspend fun create(name: String, query: BookmarkQuery, sortOrder: BookmarkSortOrder?): SmartFolderResult =
        withContext(dispatcherProvider.io) {
            val normalized = SmartFolderNames.normalize(name) ?: return@withContext SmartFolderResult.InvalidName
            if (isNameTaken(normalized, exceptId = null)) return@withContext SmartFolderResult.NameTaken
            val id = smartFolderDao.insert(smartFolderEntity(normalized, query, sortOrder, System.currentTimeMillis()))
            // 同じ名前が同時に作られたときは、一意のインデックスが IGNORE して -1 を返す
            if (id < 0) SmartFolderResult.NameTaken else SmartFolderResult.Saved(id)
        }

    override suspend fun rename(id: Long, name: String): SmartFolderResult = withContext(dispatcherProvider.io) {
        val normalized = SmartFolderNames.normalize(name) ?: return@withContext SmartFolderResult.InvalidName
        if (isNameTaken(normalized, exceptId = id)) return@withContext SmartFolderResult.NameTaken
        smartFolderDao.updateName(id, normalized)
        SmartFolderResult.Saved(id)
    }

    override suspend fun updateConditions(id: Long, query: BookmarkQuery, sortOrder: BookmarkSortOrder?) =
        withContext(dispatcherProvider.io) {
            smartFolderDao.updateConditions(
                id = id,
                filter = query.filter.name,
                tagIds = SmartFolderCodec.encodeIds(query.tagIds),
                tagMatch = query.tagMatch.name,
                categories = SmartFolderCodec.encodeCategories(query.categories),
                types = SmartFolderCodec.encodeTypes(query.types),
                period = query.period?.name,
                readState = query.readState?.name,
                sortOrder = sortOrder?.name
            )
        }

    override suspend fun delete(id: Long) = withContext(dispatcherProvider.io) {
        smartFolderDao.delete(id)
    }

    /** 大文字小文字・全角半角を区別せず、[name] と同じ名前のフォルダが([exceptId] 以外に)あるか */
    private suspend fun isNameTaken(name: String, exceptId: Long?): Boolean {
        val key = SmartFolderNames.key(name)
        return smartFolderDao.getAll().any { it.id != exceptId && SmartFolderNames.key(it.name) == key }
    }
}
