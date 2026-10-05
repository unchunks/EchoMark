package com.unchunks.echomark.data.repository

import com.unchunks.echomark.data.local.dao.TagDao
import com.unchunks.echomark.di.DispatcherProvider
import com.unchunks.echomark.domain.model.TagWithCount
import com.unchunks.echomark.domain.repository.TagRenameResult
import com.unchunks.echomark.domain.repository.TagRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

class TagRepositoryImpl @Inject constructor(
    private val tagDao: TagDao,
    private val dispatcherProvider: DispatcherProvider
) : TagRepository {

    override fun observeTagsWithCount(): Flow<List<TagWithCount>> =
        tagDao.observeTagsWithCount()
            .map { rows -> rows.map { TagWithCount(it.id, it.name, it.bookmarkCount, isUserTag = it.isUserCreated) } }
            .flowOn(dispatcherProvider.io)

    override suspend fun renameTag(tagId: Long, newName: String): TagRenameResult =
        withContext(dispatcherProvider.io) {
            val name = newName.trim()
            val current = tagDao.getTagById(tagId)
            when {
                name.isEmpty() || current == null -> TagRenameResult.Invalid
                current.name == name -> TagRenameResult.Unchanged
                else -> {
                    val resultId = tagDao.renameOrMerge(tagId, name)
                    if (resultId == tagId) TagRenameResult.Renamed(tagId) else TagRenameResult.Merged(resultId)
                }
            }
        }

    override suspend fun deleteTag(tagId: Long) =
        withContext(dispatcherProvider.io) {
            tagDao.deleteTagWithRefs(tagId)
        }
}
