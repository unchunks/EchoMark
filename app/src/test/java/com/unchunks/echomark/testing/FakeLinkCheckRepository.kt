package com.unchunks.echomark.testing

import com.unchunks.echomark.domain.bookmark.model.Bookmark
import com.unchunks.echomark.domain.linkcheck.LinkCheckState
import com.unchunks.echomark.domain.repository.DeadLink
import com.unchunks.echomark.domain.repository.LinkCheckRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** リンク切れの確認の Fake。状態はメモリ上に持ち、[checkResult] を「今すぐ確認」の結果にする。 */
class FakeLinkCheckRepository : LinkCheckRepository {
    val states = MutableStateFlow<Map<Long, LinkCheckState>>(emptyMap())
    val bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())

    /** 「今すぐ確認」の結果。null なら通信できなかった扱い */
    var checkResult: LinkCheckState? = LinkCheckState(checkedAt = 1)
    var failure: Throwable? = null
    val checkCalls = mutableListOf<Long>()

    override fun observeDeadLinks(): Flow<List<DeadLink>> =
        combine(states, bookmarks) { stateMap, all ->
            all.mapNotNull { bookmark ->
                val state = stateMap[bookmark.id]?.takeIf { it.isDead && !it.muted } ?: return@mapNotNull null
                DeadLink(bookmark, state.deadAt!!, state.failureCount, state.lastReason)
            }.sortedByDescending { it.deadAt }
        }

    override fun observeState(bookmarkId: Long): Flow<LinkCheckState?> = states.map { it[bookmarkId] }

    override suspend fun checkNow(bookmarkId: Long): LinkCheckState? {
        checkCalls += bookmarkId
        failure?.let { throw it }
        checkResult?.let { states.value = states.value + (bookmarkId to it) }
        return checkResult
    }

    override suspend fun setMuted(bookmarkId: Long, muted: Boolean) {
        val current = states.value[bookmarkId] ?: LinkCheckState()
        states.value = states.value + (bookmarkId to current.copy(muted = muted))
    }
}
