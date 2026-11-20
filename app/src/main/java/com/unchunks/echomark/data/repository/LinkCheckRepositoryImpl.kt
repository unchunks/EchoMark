package com.unchunks.echomark.data.repository

import com.unchunks.components.core.DispatcherProvider
import com.unchunks.echomark.data.linkcheck.LinkChecker
import com.unchunks.echomark.data.linkcheck.toEntity
import com.unchunks.echomark.data.linkcheck.toState
import com.unchunks.echomark.data.local.dao.BookmarkDao
import com.unchunks.echomark.data.local.dao.LinkCheckDao
import com.unchunks.echomark.data.local.entity.LinkCheckEntity
import com.unchunks.echomark.data.mapper.toDomain
import com.unchunks.echomark.domain.linkcheck.LinkCheckState
import com.unchunks.echomark.domain.repository.DeadLink
import com.unchunks.echomark.domain.repository.LinkCheckRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

class LinkCheckRepositoryImpl @Inject constructor(
    private val linkCheckDao: LinkCheckDao,
    private val bookmarkDao: BookmarkDao,
    private val linkChecker: LinkChecker,
    private val dispatcherProvider: DispatcherProvider
) : LinkCheckRepository {

    override fun observeDeadLinks(): Flow<List<DeadLink>> =
        combine(linkCheckDao.observeDeadBookmarks(), linkCheckDao.observeDead()) { bookmarks, checks ->
            val byId = checks.associateBy { it.bookmarkId }
            bookmarks.mapNotNull { row ->
                val check = byId[row.bookmark.id] ?: return@mapNotNull null
                DeadLink(row.toDomain(), deadAt = check.deadAt ?: return@mapNotNull null, failureCount = check.failureCount, reason = check.lastReason)
            }
        }.flowOn(dispatcherProvider.io)

    override fun observeState(bookmarkId: Long): Flow<LinkCheckState?> =
        linkCheckDao.observe(bookmarkId).map { it?.toState() }.flowOn(dispatcherProvider.io)

    override suspend fun checkNow(bookmarkId: Long): LinkCheckState? = linkChecker.checkOne(bookmarkId)

    override suspend fun setMuted(bookmarkId: Long, muted: Boolean) = withContext(dispatcherProvider.io) {
        if (linkCheckDao.updateMuted(bookmarkId, muted) == 0 && muted && bookmarkDao.exists(bookmarkId)) {
            // まだ確認していないブックマーク: 「確認しない」だけの行を作る
            linkCheckDao.upsert(LinkCheckState(muted = true).toEntity(bookmarkId))
        }
    }
}
